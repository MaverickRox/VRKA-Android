package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.mvrk.vrka.engine.*
import com.mvrk.vrka.update.AppUpdateManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class HistoryDeletionResult(
    val deletedRecordsCount: Int,
    val deletedFilesCount: Int,
    val failedFilesCount: Int = 0,
)

data class ActiveFallbackState(
    val jobId: String,
    val job: DownloadJob,
    val engine: FallbackEngine,
    val isVisible: Boolean = true,
)

class DownloadExecutionException(
    message: String,
    val exitCode: Int,
    val outputTail: List<String>,
    val transferStarted: Boolean,
) : RuntimeException(message)

class VrkaDownloadManager internal constructor(
    private val context: Context,
    val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    customStore: JobStore? = null,
    customPublisher: OutputPublisher? = null,
) {
    constructor(
        context: Context,
        settingsRepository: SettingsRepository,
        scope: CoroutineScope,
    ) : this(context, settingsRepository, scope, null, null)

    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private val pendingIds = ConcurrentHashMap.newKeySet<String>()
    private val activeRunningJobs = ConcurrentHashMap<String, Job>()
    private val schedulerTrigger = Channel<Unit>(Channel.CONFLATED)
    private val schedulerMutex = Mutex()
    // GeckoRuntimeManager owns one runtime-wide media bridge. Keep fallback discovery
    // sessions serial so two concurrent fallback jobs cannot clear or consume each
    // other's candidate stream.
    private val browserFallbackMutex = Mutex()
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val activeFallbackEngines = ConcurrentHashMap<String, FallbackEngine>()
    private val activeEngineTokens = ConcurrentHashMap<String, EngineCancellationToken>()
    private val activeMediaProcesses = ConcurrentHashMap<String, Process>()
    private val initialized = AtomicBoolean(false)
    private val store = customStore ?: JobStore(context)
    /** Set when a user-initiated transfer job is pending or has already started this process. */
    private val transferJobWasScheduled =
        DownloadTransferRuntime.wasTransferJobInvoked || DownloadTransferScheduler.isScheduled(context)
    internal val publisher = customPublisher ?: OutputPublisher(context, settingsRepository)
    private val stagingRoot by lazy { File(context.getExternalFilesDir(null), "staging") }
    private val speedPattern = Regex("""\bat\s+([^\s]+/s)""", RegexOption.IGNORE_CASE)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(
        runCatching {
            store.load(recoverActive = transferJobWasScheduled)
                .sortedByDescending(DownloadJob::createdAt)
        }.getOrDefault(emptyList())
    )
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _activeFallback = MutableStateFlow<ActiveFallbackState?>(null)
    val activeFallback: StateFlow<ActiveFallbackState?> = _activeFallback.asStateFlow()

    private val _runtime = MutableStateFlow(RuntimeStatus())
    val runtime: StateFlow<RuntimeStatus> = _runtime.asStateFlow()

    val diagnosticStore = DiagnosticStore(context, scope)
    val diagnostics: StateFlow<List<DiagnosticEntry>> = diagnosticStore.entries

    val appUpdateManager = AppUpdateManager.getInstance(context, settingsRepository)
    val componentUpdateManager = ComponentUpdateManager.getInstance(context)
    val subtitleManager = com.mvrk.vrka.subtitle.VrkaSubtitleManager(context)
    val ytDlpRuntimeManager = com.mvrk.vrka.runtime.YtDlpRuntimeManager(context, scope)
    private val sizeEstimator = SizeEstimator(
        runtimeLabel = {
            val name = ytDlpRuntimeManager.activeDescription()
            val version = normalizedVersion(ytDlpRuntimeManager.activeVersion())
            if (version.isBlank()) name else "$name • yt-dlp $version"
        },
    )
    private val metadataRepository = MetadataRepository(
        probe = { request -> sizeEstimator.probe(request) },
        ensureReady = { ensureInitialized() },
        onFailure = { request, preflight ->
            if (preflight.estimate.detail != "custom-command") {
                val runtimeVersion = normalizedVersion(ytDlpRuntimeManager.activeVersion())
                if (rememberEstimateDiagnostic(MetadataEstimateCache.keyFor(request, runtimeVersion))) {
                    recordSizeDiagnostic(request, preflight)
                }
            }
        },
    )
    private val recordedEstimateDiagnostics = ConcurrentHashMap.newKeySet<String>()
    private val reportedStoreIssues = ConcurrentHashMap.newKeySet<String>()

    fun clearDiagnostics() {
        scope.launch(Dispatchers.IO) {
            diagnosticStore.clear()
        }
    }

    fun dismissFallbackView() {
        _activeFallback.value = _activeFallback.value?.copy(isVisible = false)
    }

    fun showFallbackView(jobId: String) {
        val current = _activeFallback.value
        if (current != null && current.jobId == jobId) {
            _activeFallback.value = current.copy(isVisible = true)
        }
    }

    init {
        ytDlpRuntimeManager.setBusyPredicate {
            _jobs.value.any { it.state.isForegroundWork }
        }
        // The component card resolves the installed version metadata-first and only then
        // asks the active runtime, so a first install reports the shipped version instead
        // of "Unavailable". The probe is bounded by YtDlpRuntimeManager.
        componentUpdateManager.runtimeVersionProvider = {
            ytDlpRuntimeManager.activeVersion().takeIf { it.isNotBlank() }
        }
        componentUpdateManager.boundedVersionProbe = {
            ytDlpRuntimeManager.probeVersion(ytDlpRuntimeManager.activeBinary())
        }
        ytDlpRuntimeManager.onActiveRuntimeChanged = {
            refreshRuntimeStatus()
            metadataRepository.clear()
        }
        scope.launch(Dispatchers.IO) {
            val initial = runCatching {
                store.load(recoverActive = transferJobWasScheduled)
                    .sortedByDescending(DownloadJob::createdAt)
            }.getOrDefault(emptyList())
            synchronized(_jobs) {
                val existingIds = _jobs.value.map { it.id }.toSet()
                val missing = initial.filterNot { it.id in existingIds }
                if (missing.isNotEmpty()) {
                    _jobs.value = (_jobs.value + missing).sortedByDescending(DownloadJob::createdAt)
                }
            }
            reconcilePublicationJournalOnStartup()
            // Runs after persisted state is merged so the plan sees every record.
            resumeInterruptedWorkIfNeeded()
            reportStoreLoadStatus(store.lastLoadResult)
            for (ignored in persistRequests) {
                persistJobsNow()
            }
        }
        scope.launch(Dispatchers.IO) {
            for (ignored in schedulerTrigger) {
                pumpQueue()
            }
        }
        scope.launch(Dispatchers.IO) {
            settingsRepository.settings
                .map { it.concurrentDownloadsEnabled to it.maxConcurrentDownloads }
                .distinctUntilChanged()
                .collect {
                    triggerScheduler()
                }
        }
        scope.launch(Dispatchers.IO) {
            _jobs.map { jobs -> jobs.any { it.state.isForegroundWork } }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        DownloadTransferScheduler.schedule(context)
                    } else {
                        DownloadTransferScheduler.cancel(context)
                    }
                }
        }
    }

    /**
     * Called once per process. Persisted in-flight jobs are revived only when a transfer job
     * is pending, which means Android (or the user returning to the app) expects the work to
     * continue. Publishing-phase jobs fail closed because their outputs may already exist.
     */
    /**
     * Reconciles in-flight publication transactions after a process restart or crash.
     * Enforces strict durability invariants:
     * 1. Inspect journal load status: abort if CORRUPT or READ_FAILED.
     * 2. Discover provider objects created before URI persistence using deterministic pendingFilename.
     * 3. Validate entire batch completeness; never mark an incomplete playlist DONE.
     * 4. Complete files are verified and advanced to FINALIZED.
     * 5. Incomplete files are safely removed from provider.
     * 6. In-memory job state is updated (DONE for full batches, FAILED for incomplete).
     * 7. Terminal JobStore state is persisted and checked.
     * 8. ONLY if persistence succeeds are journal entries marked RECONCILED and staging cleaned up.
     * 9. Safe fallback for legacy in-flight jobs without journal records.
     */
    internal fun reconcilePublicationJournalOnStartup() {
        val loadResult = runCatching { publisher.activeJournal.loadWithStatus() }.getOrElse { error ->
            PublicationJournalLoadResult(
                emptyList(),
                PublicationJournalLoadState.READ_FAILED,
                detail = error.message.orEmpty(),
            )
        }
        when (loadResult.state) {
            PublicationJournalLoadState.CORRUPT,
            PublicationJournalLoadState.READ_FAILED -> {
                reportStoreIssue(
                    stage = "Publication journal recovery",
                    summary = "Publication journal is corrupted or unreadable",
                    detail = loadResult.detail,
                )
                return
            }
            PublicationJournalLoadState.MISSING,
            PublicationJournalLoadState.EMPTY -> {
                handleLegacyInFlightJobs()
                return
            }
            PublicationJournalLoadState.OK -> Unit
        }

        val unreconciled = runCatching { publisher.activeJournal.unreconciledEntries() }.getOrDefault(emptyList())
        if (unreconciled.isEmpty()) {
            handleLegacyInFlightJobs()
            return
        }

        val byJob = unreconciled.groupBy { it.jobId }
        val fullyReconciledJobIds = mutableListOf<String>()
        val jobsToUpdate = mutableMapOf<String, DownloadJob>()

        for ((jobId, entries) in byJob) {
            val existingJob = _jobs.value.firstOrNull { it.id == jobId }

            // If job is already DONE in JobStore, verify and mark reconciled
            if (existingJob != null && existingJob.state == JobState.DONE) {
                fullyReconciledJobIds.add(jobId)
                continue
            }

            // Step 1: Discover any provider objects created before their URI was recorded
            val adoptedEntries = entries.map { entry ->
                if (entry.providerUri == null) {
                    val discovered = when (entry.providerType) {
                        PublicationProviderType.MEDIA_STORE -> publisher.findPendingMediaStoreUri(entry.pendingFilename)
                        PublicationProviderType.SAF -> {
                            runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()?.let { treeUri ->
                                publisher.findPendingSafUri(treeUri, entry.pendingFilename)
                            }
                        }
                    }
                    if (discovered != null) {
                        publisher.activeJournal.updateProviderCreated(entry.transactionId, discovered.toString())
                        entry.copy(
                            providerUri = discovered.toString(),
                            publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                        )
                    } else {
                        entry
                    }
                } else {
                    entry
                }
            }

            val totalOutputs = adoptedEntries.firstOrNull()?.totalOutputs ?: 1
            val isAllPresent = adoptedEntries.size == totalOutputs

            val allOutputsComplete = isAllPresent && adoptedEntries.all { entry ->
                val phase = entry.publicationPhase
                phase == PublicationPhase.FINALIZED ||
                    phase == PublicationPhase.RECORDED ||
                    (phase == PublicationPhase.DATA_COPIED && entry.copiedBytes == entry.expectedSizeBytes && entry.expectedSizeBytes > 0L)
            }

            if (allOutputsComplete) {
                // Advance any DATA_COPIED items to FINALIZED
                val finalizedEntries = adoptedEntries.map { entry ->
                    if (entry.publicationPhase == PublicationPhase.DATA_COPIED && entry.providerUri != null) {
                        val uri = Uri.parse(entry.providerUri)
                        when (entry.providerType) {
                            PublicationProviderType.MEDIA_STORE -> {
                                val finalized = publisher.finalizeMediaStore(uri, entry.intendedFilename)
                                if (finalized) {
                                    publisher.activeJournal.updateFinalized(entry.transactionId)
                                    entry.copy(publicationPhase = PublicationPhase.FINALIZED)
                                } else {
                                    entry
                                }
                            }
                            PublicationProviderType.SAF -> {
                                val finalUri = publisher.finalizeSaf(uri, entry.intendedFilename)
                                val verified = publisher.fileExists(finalUri.toString())
                                if (verified) {
                                    publisher.activeJournal.updateFinalized(entry.transactionId, finalUri.toString())
                                    entry.copy(
                                        providerUri = finalUri.toString(),
                                        publicationPhase = PublicationPhase.FINALIZED,
                                    )
                                } else {
                                    entry
                                }
                            }
                        }
                    } else {
                        entry
                    }
                }

                // Verify all outputs are finalized and exist at their provider URIs
                val allExist = finalizedEntries.all { entry ->
                    (entry.publicationPhase == PublicationPhase.FINALIZED || entry.publicationPhase == PublicationPhase.RECORDED) &&
                        entry.providerUri != null && publisher.fileExists(entry.providerUri)
                }

                if (allExist) {
                    val uris = finalizedEntries.map { it.providerUri!! }
                    val totalBytes = finalizedEntries.sumOf { it.copiedBytes }
                    val targetJob = existingJob ?: DownloadJob(
                        id = jobId,
                        request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
                        state = JobState.DONE,
                        title = finalizedEntries.firstOrNull()?.intendedFilename ?: "Recovered Media",
                        detail = if (uris.size == 1) "Saved to Downloads/VRKA" else "Saved ${uris.size} files",
                        downloadedBytes = totalBytes,
                        reportedTotalBytes = totalBytes,
                        reportedTotalIsExact = true,
                        actualFinalFileBytes = totalBytes,
                        outputUris = uris,
                        error = "",
                        publishStarted = true,
                        createdAt = finalizedEntries.minOfOrNull { it.updatedAt } ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    val isOpus = targetJob.request.mode == MediaMode.AUDIO && targetJob.request.audioFormat == AudioFormat.OPUS
                    val detail = if (isOpus) {
                        "Saved (native Opus stream copy)"
                    } else if (uris.size == 1) {
                        "Saved to Downloads/VRKA"
                    } else {
                        "Saved ${uris.size} files"
                    }

                    jobsToUpdate[jobId] = targetJob.copy(
                        state = JobState.DONE,
                        progress = 100f,
                        detail = detail,
                        downloadedBytes = totalBytes,
                        reportedTotalBytes = totalBytes,
                        reportedTotalIsExact = true,
                        actualFinalFileBytes = totalBytes,
                        outputUris = uris,
                        error = "",
                        publishStarted = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                    fullyReconciledJobIds.add(jobId)
                } else {
                    // One or more outputs could not be finalized (e.g. MediaStore update affected 0 rows).
                    // Preserve the journal and outputs for retry; do NOT delete completed files and do NOT reconcile.
                    reportStoreIssue(
                        stage = "Publication finalization",
                        summary = "Output finalization verification failed",
                        detail = "Outputs for job $jobId could not be finalized; preserving journal for retry",
                    )
                }
            } else {
                handleIncompleteBatch(jobId, existingJob, adoptedEntries, jobsToUpdate, fullyReconciledJobIds)
            }
        }

        // Step 2: Update in-memory job state
        if (jobsToUpdate.isNotEmpty()) {
            mutate(persist = false) { currentList ->
                val updatedMap = jobsToUpdate
                val existingIds = currentList.map { it.id }.toSet()
                val updatedList = currentList.map { job -> updatedMap[job.id] ?: job }
                val missingJobs = updatedMap.values.filterNot { it.id in existingIds }
                (updatedList + missingJobs).sortedByDescending(DownloadJob::createdAt)
            }
        }

        // Step 3 & 4: Persist terminal JobStore state and verify persistence result
        val persistSuccess = if (jobsToUpdate.isNotEmpty()) {
            persistJobsNow()
        } else {
            true
        }

        // Step 5 & 6: ONLY if persistence succeeded, mark journal reconciled and clean up staging
        if (persistSuccess) {
            fullyReconciledJobIds.forEach { jobId ->
                runCatching { publisher.activeJournal.markReconciled(jobId) }
                cleanupStaging(jobId)
            }
        } else {
            reportStoreIssue(
                stage = "Publication journal reconciliation",
                summary = "Failed to persist reconciled jobs to JobStore; retaining journal and staging",
                detail = "JobStore save returned false during startup reconciliation",
            )
        }

        handleLegacyInFlightJobs()
    }

    private fun handleIncompleteBatch(
        jobId: String,
        existingJob: DownloadJob?,
        entries: List<PublicationEntry>,
        jobsToUpdate: MutableMap<String, DownloadJob>,
        fullyReconciledJobIds: MutableList<String>,
    ) {
        val completedUris = mutableListOf<String>()
        var totalBytes = 0L
        val totalOutputs = entries.firstOrNull()?.totalOutputs ?: 1
        var anyCleanupFailed = false

        entries.forEach { entry ->
            val isFinal = (entry.publicationPhase == PublicationPhase.FINALIZED || entry.publicationPhase == PublicationPhase.RECORDED) &&
                entry.providerUri != null && publisher.fileExists(entry.providerUri)
            if (isFinal) {
                completedUris.add(entry.providerUri)
                totalBytes += entry.copiedBytes
            } else {
                val uri = entry.providerUri
                if (uri != null) {
                    val deleted = runCatching { publisher.delete(uri) }.getOrDefault(false)
                    if (deleted) {
                        publisher.activeJournal.updateDeleted(entry.transactionId)
                    } else {
                        anyCleanupFailed = true
                        publisher.activeJournal.markCleanupPending(entry.transactionId, "Could not delete temporary output: $uri")
                        reportStoreIssue(
                            stage = "Publication cleanup",
                            summary = "Failed to clean up unfinalized output",
                            detail = "Could not delete partial provider output $uri for job $jobId",
                        )
                    }
                } else {
                    runCatching { publisher.activeJournal.markInterrupted(entry.transactionId) }
                }
            }
        }

        if (existingJob != null && existingJob.state != JobState.DONE) {
            val detail = when {
                anyCleanupFailed -> "Interrupted during publication (cleanup pending for partial files)"
                completedUris.isNotEmpty() -> "Interrupted during publication (${completedUris.size} of $totalOutputs files published)"
                else -> "Interrupted during publication"
            }
            jobsToUpdate[jobId] = existingJob.copy(
                state = JobState.FAILED,
                error = INTERRUPTED_DOWNLOAD_ERROR,
                detail = detail,
                outputUris = completedUris,
                downloadedBytes = totalBytes,
                reportedTotalBytes = totalBytes,
                actualFinalFileBytes = totalBytes,
                speed = "",
                etaSeconds = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

        if (!anyCleanupFailed) {
            fullyReconciledJobIds.add(jobId)
        }
    }

    private fun handleLegacyInFlightJobs() {
        var changed = false
        mutate(persist = false) { currentList ->
            currentList.map { job ->
                if (job.publishStarted && job.state != JobState.DONE && job.outputUris.isEmpty()) {
                    val hasJournal = runCatching {
                        publisher.activeJournal.entriesForJob(job.id).isNotEmpty()
                    }.getOrDefault(false)
                    if (!hasJournal) {
                        changed = true
                        job.copy(
                            state = JobState.FAILED,
                            error = INTERRUPTED_DOWNLOAD_ERROR,
                            detail = "Interrupted (legacy transfer without publication journal)",
                            speed = "",
                            etaSeconds = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else {
                        job
                    }
                } else {
                    job
                }
            }
        }
        if (changed) {
            persistJobsNow()
        }
    }

    private fun resumeInterruptedWorkIfNeeded() {
        if (!DownloadRecovery.shouldRecoverPersistedActiveWork(
                Build.VERSION.SDK_INT,
                transferJobWasScheduled,
            )
        ) {
            return
        }
        val plan = DownloadRecovery.plan(_jobs.value)
        if (plan.isEmpty) return
        val resumeIds = plan.resumeIds.toSet()
        val interruptedIds = plan.interruptedIds.toSet()
        mutate(persist = true) { jobs ->
            jobs.map { job ->
                when (job.id) {
                    in resumeIds -> job.copy(
                        state = JobState.QUEUED,
                        detail = "Resuming interrupted download",
                        speed = "",
                        etaSeconds = null,
                        error = "",
                        updatedAt = System.currentTimeMillis(),
                    )
                    in interruptedIds -> job.copy(
                        state = JobState.FAILED,
                        detail = "Interrupted",
                        error = INTERRUPTED_DOWNLOAD_ERROR,
                        speed = "",
                        etaSeconds = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    else -> job
                }
            }
        }
        plan.resumeIds.forEach(::admitToQueue)
    }

    private fun triggerScheduler() {
        schedulerTrigger.trySend(Unit)
    }

    private fun admitToQueue(jobId: String) {
        if (pendingIds.add(jobId)) {
            pendingQueue.add(jobId)
            triggerScheduler()
        }
    }

    private suspend fun pumpQueue() {
        schedulerMutex.withLock {
            val settings = settingsRepository.settings.value
            val maxConcurrent = if (settings.concurrentDownloadsEnabled) {
                settings.maxConcurrentDownloads.coerceIn(2, 4)
            } else {
                1
            }
            while (activeRunningJobs.size < maxConcurrent) {
                val nextJobId = pendingQueue.poll() ?: break
                pendingIds.remove(nextJobId)
                if (isCancelled(nextJobId)) continue
                val job = current(nextJobId)
                if (job == null || job.state.isTerminal) continue
                if (activeRunningJobs.containsKey(nextJobId)) continue

                val runningJob = scope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, t ->
                    Log.e("VRKA", "Unhandled exception in background download job $nextJobId: ${safeError(t)}")
                }) {
                    try {
                        process(nextJobId)
                    } catch (t: Throwable) {
                        Log.e("VRKA", "Job $nextJobId failed: ${safeError(t)}")
                    } finally {
                        activeRunningJobs.remove(nextJobId)
                        triggerScheduler()
                    }
                }
                activeRunningJobs[nextJobId] = runningJob
            }
        }
    }

    fun enqueue(request: DownloadRequest, estimate: SizeEstimate? = null): String {
        require(isHttpUrl(request.url)) { "Enter a valid http or https URL." }
        val settings = settingsRepository.settings.value
        val selectedTemplate = if (settings.customCommandMode) {
            settings.customCommandTemplates.firstOrNull { it.id == settings.selectedCustomCommandTemplateId }
                ?: error("Select a valid yt-dlp command template in Settings.")
        } else null
        val queuedRequest = request.copy(
            h264CompatibilityMode = settings.h264CompatibilityMode,
            commandTemplateId = selectedTemplate?.id,
            commandTemplateName = selectedTemplate?.name.orEmpty(),
            commandTemplateArguments = selectedTemplate?.arguments.orEmpty(),
        )
        val id = UUID.randomUUID().toString()
        val job = DownloadJob(
            id = id,
            request = queuedRequest,
            // Only a preflight result is an estimate; a transfer total is never stored here.
            estimatedTotalBytes = estimate?.bytes?.takeIf { it > 0L },
            estimatedSizeAccuracy = estimate?.kind ?: SizeEstimateKind.UNAVAILABLE,
        )
        mutate(persist = true) { listOf(job) + it }
        admitToQueue(id)
        return id
    }

    fun retry(jobId: String): String? {
        val previous = _jobs.value.firstOrNull { it.id == jobId } ?: return null
        if (!previous.state.isTerminal) return null
        if (previous.state == JobState.DONE) return null
        cancelled.remove(jobId)
        runCatching { cleanupStaging(jobId) }
        activeFallbackEngines.remove(jobId)?.let { engine ->
            runCatching {
                engine.cancel()
                engine.cleanup()
            }
        }
        if (_activeFallback.value?.jobId == jobId) {
            _activeFallback.value = null
        }
        val request = previous.request.copy(
            resolvedMediaUrl = null,
            resolvedHeaders = emptyMap(),
        )
        // The logical job identity is stable across retries: late callbacks from an
        // older generation are rejected by attempt-scoped updates.
        val replacement = previous.copy(
            request = request,
            state = JobState.QUEUED,
            detail = "Queued retry",
            progress = 0f,
            speed = "",
            etaSeconds = null,
            downloadedBytes = null,
            reportedTotalBytes = null,
            reportedTotalIsExact = false,
            activeStream = null,
            playlistItemIndex = null,
            playlistItemCount = null,
            // A retry resolves the page again; an estimate captured for an older browser
            // candidate or a previous preflight must not describe the new attempt.
            estimatedTotalBytes = null,
            estimatedSizeAccuracy = SizeEstimateKind.UNAVAILABLE,
            outputUris = emptyList(),
            error = "",
            attempt = previous.attempt + 1,
            publishStarted = false,
            updatedAt = System.currentTimeMillis(),
        )
        replace(replacement, persist = true)
        admitToQueue(jobId)
        return jobId
    }

    fun cancel(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.state.isTerminal) return
        cancelled += jobId
        pendingQueue.remove(jobId)
        pendingIds.remove(jobId)
        activeRunningJobs.remove(jobId)?.cancel()
        runCatching { activeFallbackEngines.remove(jobId)?.apply { cancel(); cleanup() } }
        runCatching { activeEngineTokens.remove(jobId)?.cancel() }
        if (_activeFallback.value?.jobId == jobId) {
            _activeFallback.value = null
        }
        runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
        activeMediaProcesses.remove(jobId)?.let { process ->
            runCatching { process.destroy() }
            scope.launch(Dispatchers.IO) {
                if (runCatching { process.waitFor(750, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false).not()) {
                    runCatching { process.destroyForcibly() }
                }
            }
        }
        update(
            jobId,
            state = JobState.CANCELLED,
            detail = "Cancelled",
            error = "",
            persist = true,
        )
        scope.launch(Dispatchers.IO) {
            runCatching { cleanupStaging(jobId) }
        }
        triggerScheduler()
    }

    fun setJobDestination(jobId: String, treeUri: String) {
        val job = current(jobId) ?: return
        if (job.state != JobState.WAITING_FOR_USER) return
        val trimmed = treeUri.trim()
        if (trimmed.isBlank() || !OutputPublisher.hasPersistedTreePermission(context, Uri.parse(trimmed))) {
            update(
                jobId,
                state = JobState.WAITING_FOR_USER,
                detail = "Download location unavailable; choose location",
                persist = true,
            )
            return
        }
        val updated = job.copy(
            request = job.request.copy(destinationTreeUri = trimmed),
            state = JobState.PREPARING,
            detail = "Folder selected; resuming",
            updatedAt = System.currentTimeMillis(),
        )
        replace(updated, persist = true)
        admitToQueue(jobId)
    }

    fun fileExists(uriText: String): Boolean = publisher.fileExists(uriText)

    fun deleteHistoryRecord(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (!job.state.isTerminal) return
        scope.launch(Dispatchers.IO) {
            diagnosticStore.clearUrlsForJob(jobId)
            mutate(persist = true) { jobs -> jobs.filterNot { it.id == jobId } }
        }
    }

    suspend fun deleteJobWithFiles(jobId: String): HistoryDeletionResult {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return HistoryDeletionResult(0, 0)
        if (!job.state.isTerminal) return HistoryDeletionResult(0, 0)
        var deletedFiles = 0
        var failedFiles = 0
        job.outputUris.forEach { uriText ->
            val success = publisher.delete(uriText)
            if (success) deletedFiles++ else failedFiles++
        }
        diagnosticStore.clearUrlsForJob(jobId)
        mutate(persist = true) { jobs -> jobs.filterNot { it.id == jobId } }
        return HistoryDeletionResult(
            deletedRecordsCount = 1,
            deletedFilesCount = deletedFiles,
            failedFilesCount = failedFiles,
        )
    }

    fun deleteHistoryRecords(jobIds: Collection<String>) {
        val targetIds = jobIds.toSet()
        scope.launch(Dispatchers.IO) {
            targetIds.forEach { diagnosticStore.clearUrlsForJob(it) }
            mutate(persist = true) { jobs ->
                jobs.filterNot { it.id in targetIds && it.state.isTerminal }
            }
        }
    }

    suspend fun deleteJobsWithFiles(jobIds: Collection<String>): HistoryDeletionResult {
        val targetIds = jobIds.toSet()
        val targetJobs = _jobs.value.filter { it.id in targetIds && it.state.isTerminal }
        var deletedFiles = 0
        var failedFiles = 0
        targetJobs.forEach { job ->
            job.outputUris.forEach { uriText ->
                val success = publisher.delete(uriText)
                if (success) deletedFiles++ else failedFiles++
            }
        }
        targetIds.forEach { diagnosticStore.clearUrlsForJob(it) }
        mutate(persist = true) { jobs ->
            jobs.filterNot { it.id in targetIds && it.state.isTerminal }
        }
        return HistoryDeletionResult(
            deletedRecordsCount = targetJobs.size,
            deletedFilesCount = deletedFiles,
            failedFilesCount = failedFiles,
        )
    }

    suspend fun clearAllHistory(deleteFiles: Boolean): HistoryDeletionResult {
        val targetJobs = _jobs.value.filter { it.state.isTerminal }
        var deletedFiles = 0
        var failedFiles = 0
        if (deleteFiles) {
            targetJobs.forEach { job ->
                job.outputUris.forEach { uriText ->
                    val success = publisher.delete(uriText)
                    if (success) deletedFiles++ else failedFiles++
                }
            }
        }
        targetJobs.forEach { diagnosticStore.clearUrlsForJob(it.id) }
        mutate(persist = true) { jobs ->
            jobs.filterNot { it.state.isTerminal }
        }
        return HistoryDeletionResult(
            deletedRecordsCount = targetJobs.size,
            deletedFilesCount = deletedFiles,
            failedFilesCount = failedFiles,
        )
    }

    fun deleteJob(jobId: String) {
        deleteHistoryRecord(jobId)
    }

    fun clearFinished() {
        scope.launch(Dispatchers.IO) {
            _jobs.value.filter { it.state.isTerminal }.forEach { diagnosticStore.clearUrlsForJob(it.id) }
            mutate(persist = true) { list -> list.filterNot { it.state.isTerminal } }
        }
    }

    /**
     * Best-effort size of the browser candidate that is actually being transferred. It runs
     * beside the transfer and never blocks it; until it finishes (or if it fails) the card
     * shows no estimate rather than the page-level number that described a different stream.
     */
    private fun estimateFallbackCandidate(
        jobId: String,
        job: DownloadJob,
        mediaUrl: String,
    ) {
        scope.launch(Dispatchers.IO) {
            val estimate = runCatching { estimateSize(job.request) }.getOrNull() ?: return@launch
            if (!estimate.isAvailable) return@launch
            val live = current(jobId) ?: return@launch
            if (!FallbackEstimateIdentity.stillApplies(live, job.attempt, mediaUrl)) return@launch
            update(
                jobId,
                estimatedTotalBytes = estimate.bytes,
                estimatedSizeAccuracy = estimate.kind,
                expectedAttempt = job.attempt,
            )
        }
    }

    /**
     * Metadata-only preflight for the confirmation, share and format UIs. One yt-dlp process
     * per source and selection returns the complete document; every resolution chip is still
     * calculated locally from that document. Concurrent callers (the download screen and the
     * share card, for example) join the same in-flight extraction, and [forceRefresh] bypasses
     * both caches for the retry action.
     *
     * Nothing here runs until an extraction is actually required, so a warm cache hit never
     * pays the Python/FFmpeg initialisation cost.
     */
    suspend fun estimateSize(request: DownloadRequest, forceRefresh: Boolean = false): SizeEstimate {
        val startedAt = System.currentTimeMillis()
        val runtimeVersion = normalizedVersion(ytDlpRuntimeManager.activeVersion())
        return metadataRepository.estimate(request, runtimeVersion, forceRefresh) { load ->
            logEstimateLatency(load.source, startedAt, load.probeDurationMs)
        }
    }

    /**
     * The shared metadata view used by the download screen (resolution sizes and subtitle
     * languages). It goes through the same repository as [estimateSize], so it never starts a
     * second extraction for a source that was already fetched.
     */
    suspend fun metadata(request: DownloadRequest, forceRefresh: Boolean = false): MetadataSnapshot? {
        val runtimeVersion = normalizedVersion(ytDlpRuntimeManager.activeVersion())
        return metadataRepository.snapshot(request, runtimeVersion, forceRefresh)
    }

    /** Stage timing for latency measurement; never includes URLs or request headers. */
    private fun logEstimateLatency(source: String, startedAt: Long, preflightMs: Long? = null) {
        val total = System.currentTimeMillis() - startedAt
        Log.d(
            "VRKA",
            "Size estimate source=$source totalMs=$total" + (preflightMs?.let { " preflightMs=$it" } ?: ""),
        )
    }

    private fun rememberEstimateDiagnostic(key: String): Boolean {
        if (recordedEstimateDiagnostics.size >= MAX_ESTIMATE_DIAGNOSTICS) {
            recordedEstimateDiagnostics.clear()
        }
        return recordedEstimateDiagnostics.add(key)
    }

    private fun reportStoreLoadStatus(result: JobStoreLoadResult?) {
        if (result == null) return
        val needsAttention = result.state == JobStoreLoadState.MALFORMED ||
            result.state == JobStoreLoadState.READ_FAILED ||
            result.state == JobStoreLoadState.UNSUPPORTED_SCHEMA ||
            result.state == JobStoreLoadState.RECOVERED
        if (!needsAttention) return
        val summary = when (result.state) {
            JobStoreLoadState.RECOVERED ->
                "Queue/history recovered with ${result.skippedRecords} unreadable record(s)"
            JobStoreLoadState.MALFORMED -> "Queue/history file was malformed and could not be decoded"
            JobStoreLoadState.UNSUPPORTED_SCHEMA -> "Queue/history file uses an unsupported schema version"
            JobStoreLoadState.READ_FAILED -> "Queue/history file could not be read"
            JobStoreLoadState.MISSING, JobStoreLoadState.EMPTY, JobStoreLoadState.OK -> return
        }
        reportStoreIssue(
            stage = "Queue/history recovery",
            summary = summary,
            detail = buildString {
                if (result.detail.isNotBlank()) appendLine(result.detail)
                appendLine("state=${result.state.name} kept=${result.jobs.size} skipped=${result.skippedRecords}")
                result.recoveryCopy?.let { appendLine("recoveryCopy=${it.name}") }
                append("The original file was preserved; it is not overwritten during load.")
            },
        )
    }

    private fun persistJobsNow(): Boolean {
        val started = SystemClock.elapsedRealtime()
        val result = store.save(_jobs.value)
        Log.d(
            "VRKA",
            "JobStore save success=${result.success} jobs=${_jobs.value.size} elapsedMs=${SystemClock.elapsedRealtime() - started}",
        )
        if (!result.success) {
            reportStoreIssue(
                stage = "Queue/history persistence",
                summary = "Could not save queue and history",
                detail = result.error.ifBlank { "Unknown storage error" },
            )
        }
        return result.success
    }

    private fun reportStoreIssue(stage: String, summary: String, detail: String) {
        if (!reportedStoreIssues.add("$stage|$summary")) return
        Log.w("VRKA", "$summary: ${detail.take(300)}")
        scope.launch(Dispatchers.IO) {
            diagnosticStore.record(
                DiagnosticEntry(
                    jobId = "storage-" + UUID.randomUUID(),
                    title = "Queue and history storage",
                    stage = stage,
                    failureCategory = "STORAGE",
                    summary = summary,
                    detail = detail,
                    url = "",
                    quality = "",
                    acquisitionMethod = "Local storage",
                ),
            )
        }
    }

    private suspend fun recordSizeDiagnostic(request: DownloadRequest, preflight: SizePreflight) {
        val estimate = preflight.estimate
        diagnosticStore.record(
            DiagnosticEntry(
                jobId = "preflight-" + UUID.randomUUID(),
                title = estimate.title.ifBlank { request.url },
                stage = "Size preflight",
                failureCategory = "SIZE_UNAVAILABLE",
                summary = buildString {
                    append("Size estimate unavailable: ")
                    append(estimate.detail.ifBlank { "unknown" })
                    if (preflight.timedOut) {
                        append(" (timed out after ").append(preflight.durationMs).append(" ms)")
                    }
                },
                detail = buildString {
                    appendLine("mode=${request.mode} quality=${request.quality.label} fps60=${request.prefer60Fps}")
                    appendLine("selector=${preflight.formatSelector}")
                    appendLine("runtime=${preflight.runtimeLabel}")
                    appendLine(
                        "startedAt=${preflight.startedAtEpochMs} finishedAt=${preflight.finishedAtEpochMs} " +
                            "durationMs=${preflight.durationMs} exit=${preflight.exitCode ?: "unknown"} " +
                            "timedOut=${preflight.timedOut} stdoutChars=${preflight.stdoutChars}",
                    )
                    if (preflight.command.isNotBlank()) {
                        appendLine("command: ${preflight.command}")
                    }
                    if (preflight.documentSummary.isNotBlank()) {
                        appendLine("response: ${preflight.documentSummary}")
                    }
                    if (estimate.diagnostic.isNotBlank()) appendLine(estimate.diagnostic)
                    if (preflight.stderrTail.isNotBlank()) {
                        appendLine("stderr:")
                        appendLine(preflight.stderrTail)
                    }
                },
                url = "",
                sourceUrl = "",
                candidateUrl = "",
                quality = request.quality.label,
                acquisitionMethod = "Native yt-dlp",
                runtime = preflight.runtimeLabel,
            ),
        )
    }

    fun updateRuntime(channel: UpdatePreference) {
        if (_runtime.value.busy) return
        scope.launch(Dispatchers.IO) {
            _runtime.value = _runtime.value.copy(busy = true, message = "Updating yt-dlp")
            runCatching {
                ensureInitialized()
                val updater = SecureComponentUpdater(context)
                val targetTag = updater.fetchLatestReleaseTag(channel)
                val postVersion = updater.updateYtDlp(channel, targetTag).getOrThrow()
                val version = normalizedVersion(postVersion)
                if (ytDlpRuntimeManager.isCustomActive()) {
                    _runtime.value = RuntimeStatus(
                        initialized = true,
                        version = normalizedVersion(ytDlpRuntimeManager.activeVersion()),
                        message = "Stock yt-dlp updated to " + version + "; active runtime unchanged (" +
                            ytDlpRuntimeManager.activeDescription() + ")",
                    )
                } else {
                    _runtime.value = RuntimeStatus(
                        initialized = true,
                        version = version,
                        message = "yt-dlp " + version + " ready",
                    )
                }
            }.onFailure { error ->
                Log.e("VRKA", "Runtime update failed", error)
                val bundled = normalizedVersion(ytDlpRuntimeManager.activeVersion())
                _runtime.value = RuntimeStatus(
                    initialized = initialized.get(),
                    version = bundled,
                    message = "Update failed; known-good runtime retained: " + safeError(error),
                )
            }
        }
    }

    fun openOutput(uriText: String) {
        val uri = Uri.parse(uriText)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }

    fun shareOutput(uriText: String) {
        val uri = Uri.parse(uriText)
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share with",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun process(jobId: String) {
        if (isCancelled(jobId)) return
        val currentJobInitial = current(jobId) ?: return
        val saveMode = settingsRepository.settings.value.saveLocationMode
        val hasExplicitDest = currentJobInitial.request.destinationTreeUri != null

        if (saveMode == SaveLocationMode.ASK_EVERY_TIME && !hasExplicitDest) {
            update(
                jobId,
                state = JobState.WAITING_FOR_USER,
                detail = "Choose download location to start",
                persist = true,
            )
            return
        }

        val targetTreeUri = currentJobInitial.request.destinationTreeUri?.takeIf { it.isNotBlank() }
            ?: settingsRepository.settings.value.outputTreeUri.takeIf { it.isNotBlank() }
        if (OutputPublisher.requiresSafDestination() && targetTreeUri == null) {
            // API 26-28 cannot publish through MediaStore.Downloads and VRKA deliberately does
            // not request WRITE_EXTERNAL_STORAGE. Require the folder before any bytes move.
            update(
                jobId,
                state = JobState.WAITING_FOR_USER,
                detail = "Choose download location to start",
                persist = true,
            )
            return
        }
        if (!targetTreeUri.isNullOrBlank()) {
            val treeUri = Uri.parse(targetTreeUri)
            if (!OutputPublisher.hasPersistedTreePermission(context, treeUri)) {
                Log.w("VRKA", "Tree permission not granted or revoked for $targetTreeUri")
                update(
                    jobId,
                    state = JobState.WAITING_FOR_USER,
                    detail = "Download location unavailable; choose location",
                    persist = true,
                )
                return
            }
        }

        var currentStage = "Direct Extraction"
        update(jobId, state = JobState.PREPARING, detail = "Starting runtime", persist = true)
        startForegroundServiceSafely()
        try {
            ensureInitialized()
            var job = current(jobId) ?: return
            if (isCancelled(jobId)) return

            val customCommandMode = !job.request.commandTemplateId.isNullOrBlank()
            var failure: Throwable? = null
            if (customCommandMode) {
                update(
                    jobId,
                    state = JobState.PREPARING,
                    detail = "Custom command: ${job.request.commandTemplateName.ifBlank { "yt-dlp" }}",
                    persist = true,
                )
                failure = runCatching { downloadOnce(job) }.exceptionOrNull()
                if (failure == null) return
                Log.e("VRKA", "Custom yt-dlp attempt failed; checking normal recovery paths: ${safeError(failure)}")
            } else {
                // The transfer prints the resolved title itself (`__VRKA_TITLE__`), so a
                // separate getInfo() probe only repeated metadata extraction and delayed the
                // real transfer. Jobs still receive their title as soon as extraction starts.
                job = current(jobId) ?: return
                failure = runCatching { downloadOnce(job) }.exceptionOrNull()
                if (failure != null) {
                    Log.e("VRKA", "Direct attempt failed: ${safeError(failure)}")
                }
            }

            job = current(jobId) ?: return

            // v5.0.2 contract: one automatic native attempt. The user explicitly rejected
            // automatic retries, so a failure is classified once and either enters browser
            // fallback immediately or is reported with the manual Retry action.
            val priorCategories = mutableListOf<FailureCategory>()
            if (failure != null) {
                val firstCategory = classifyDownloadError(cleanErrorOutput(failure.message.orEmpty()))
                if (firstCategory != FailureCategory.UNKNOWN) {
                    priorCategories.add(firstCategory)
                }
            }

            // Subtitle failure isolation: a subtitle-only failure must not discard an
            // otherwise valid media transfer. This is a deterministic media-preservation
            // path, not a transient retry, and it never repeats transferred media bytes
            // because the second pass runs without subtitles against the same staging files.
            if (
                failure != null &&
                !customCommandMode &&
                job.request.downloadSubtitles &&
                !isCancelled(jobId) &&
                isSubtitleFailure(
                    failure.message.orEmpty(),
                    (failure as? DownloadExecutionException)?.outputTail?.joinToString("\n").orEmpty()
                )
            ) {
                Log.w("VRKA", "Job $jobId failed due to subtitle issue; saving media without subtitles")
                cleanupStaging(job.id)
                update(
                    job.id,
                    state = JobState.PREPARING,
                    detail = "Saving media without subtitles",
                    persist = true,
                )
                val mediaOnlyJob = job.copy(request = job.request.copy(downloadSubtitles = false))
                failure = runCatching { downloadOnce(mediaOnlyJob) }.exceptionOrNull()
            }

            // Failure classification and browser-fallback eligibility
            if (DownloadRequestFactory.shouldAttemptBrowserFallback(
                    request = job.request,
                    hasFailure = failure != null,
                    cancelled = isCancelled(jobId),
                )
            ) {
                val fallbackFailure = requireNotNull(failure) { "Browser fallback requested without a failed attempt." }
                val errorMessage = fallbackFailure.message.orEmpty()
                val latestExecFailure = fallbackFailure as? DownloadExecutionException
                val outputTailText = latestExecFailure?.outputTail?.joinToString("\n").orEmpty()
                val transferStarted = latestExecFailure?.transferStarted == true ||
                    job.progress > 0f ||
                    isTransferFailureAfterResolution(outputTailText, hasTransferStarted = false)

                val (category, isRecoverable) = classifyAndCheckRecoverable(
                    errorMessage = errorMessage,
                    targetUrl = job.request.url,
                    executionOutput = outputTailText,
                    hasResolvedMediaUrl = false,
                    hasTransferStarted = transferStarted,
                    priorCategories = priorCategories,
                )
                Log.i("VRKA", "Failure classified: category=$category, recoverable=$isRecoverable, transferStarted=$transferStarted, nativeTarget=${isYtdlpNativeTarget(job.request.url)}")

                if (isRecoverable) {
                    // Automatic browser fallback
                    currentStage = "Browser Fallback"
                    update(
                        jobId,
                        state = JobState.BROWSER_FALLBACK,
                        detail = "Direct extraction failed ($category); starting browser fallback",
                        error = "",
                        resetEstimate = true,
                        persist = true,
                    )

                    val engine = FallbackEngine(
                        context = context,
                        taskId = jobId,
                        targetUrl = job.request.url,
                    )
                    val result = browserFallbackMutex.withLock {
                        activeFallbackEngines[jobId] = engine
                        _activeFallback.value = ActiveFallbackState(jobId, job, engine, isVisible = true)
                        try {
                            engine.execute()
                        } finally {
                            engine.cleanup()
                            activeFallbackEngines.remove(jobId)
                            if (_activeFallback.value?.jobId == jobId) {
                                _activeFallback.value = null
                            }
                        }
                    }
                    when (result) {
                            is FallbackResult.Success -> {
                                if (isCancelled(jobId) || current(jobId)?.state?.isTerminal == true) {
                                    Log.i("VRKA", "Discarding browser fallback result for cancelled job $jobId")
                                    return
                                }
                                val bundle = result.bundle
                                val headers = buildMap {
                                    if (bundle.referer.isNotBlank()) put("Referer", bundle.referer)
                                    if (bundle.origin.isNotBlank()) put("Origin", bundle.origin)
                                    if (bundle.userAgent.isNotBlank()) put("User-Agent", bundle.userAgent)
                                    putAll(bundle.headers)
                                }
                                val updated = job.copy(
                                    request = DownloadRequestFactory.browserFallbackRequest(
                                        request = job.request,
                                        mediaUrl = bundle.mediaUrl,
                                        headers = headers,
                                    ),
                                    state = JobState.PREPARING,
                                    detail = "${bundle.mediaKind.value.uppercase()} candidate selected; downloading",
                                    // The page-level preflight describes the failed extraction,
                                    // not the selected browser candidate. Never keep showing it.
                                    estimatedTotalBytes = null,
                                    estimatedSizeAccuracy = SizeEstimateKind.UNAVAILABLE,
                                    updatedAt = System.currentTimeMillis(),
                                )
                                replace(updated, persist = true)
                                startForegroundServiceSafely()
                                estimateFallbackCandidate(jobId, updated, bundle.mediaUrl)

                                currentStage = "Browser Fallback yt-dlp Transfer"
                                Log.i(
                                    "VRKA",
                                    "Starting yt-dlp browser-candidate transfer: job=$jobId, kind=${bundle.mediaKind.value}, origin=${HeaderValidation.originOf(bundle.mediaUrl).orEmpty()}"
                                )
                                failure = runCatching {
                                    val resumedJob = current(jobId) ?: updated
                                    downloadOnce(resumedJob)
                                }.exceptionOrNull()
                                if (failure != null && bundle.withheldHeaderCount > 0) {
                                    val failureEvidence = buildString {
                                        append(failure.message.orEmpty())
                                        append('\n')
                                        append((failure as? DownloadExecutionException)?.outputTail?.joinToString("\n").orEmpty())
                                    }
                                    if (Regex("\\b(401|403)\\b").containsMatchIn(failureEvidence)) {
                                        failure = RuntimeException(
                                            "HTTP 401/403 rejection after browser fallback; VRKA withheld browser credential/custom header(s) because yt-dlp cannot safely scope them across redirects and media segments."
                                        )
                                    }
                                }
                                if (failure != null) {
                                    Log.e("VRKA", "Browser candidate yt-dlp transfer failed: ${safeError(failure)}")
                                }
                            }
                            is FallbackResult.Failed -> {
                                Log.e("VRKA", "Browser fallback failed: ${result.reason}")
                                failure = RuntimeException(result.reason.ifBlank { "Browser fallback could not find a media stream" })
                            }
                            is FallbackResult.Cancelled -> {
                                // Cancellation handled below
                                return
                            }
                    }
                } else {
                    // Terminal failure — NOT eligible for fallback
                    Log.i("VRKA", "Failure is terminal ($category); no fallback")
                }
            }

            if (failure != null) throw failure
        } catch (error: Throwable) {
            Log.e("VRKA", "Download processing for $jobId failed: ${safeError(error)}")
            if (!isCancelled(jobId)) {
                val failureMsg = safeError(error)
                val fallbackFailed = currentStage.startsWith("Browser Fallback") || failureMsg.contains("browser fallback", ignoreCase = true)
                val failureDetail = if (fallbackFailed) {
                    "Browser fallback could not find a media stream"
                } else {
                    "Download failed"
                }
                update(
                    jobId,
                    state = JobState.FAILED,
                    detail = failureDetail,
                    error = failureMsg,
                    persist = true,
                )
                val currentJob = current(jobId)
                val stage = if (currentJob?.detail?.contains("Publishing", ignoreCase = true) == true) {
                    "Publishing"
                } else {
                    currentStage
                }
                val categoryName = if (fallbackFailed) {
                    FailureCategory.BROWSER_RECOVERABLE.name
                } else {
                    classifyDownloadError(cleanErrorOutput(error.message.orEmpty())).name
                }
                val tailOutput = (error as? DownloadExecutionException)?.outputTail?.takeLast(30)?.joinToString("\n")
                    ?: error.message.orEmpty().take(2000)
                val sourceUrl = currentJob?.request?.url.orEmpty()
                diagnosticStore.record(
                    DiagnosticEntry(
                        jobId = jobId,
                        title = currentJob?.title?.ifBlank { sourceUrl }.orEmpty(),
                        stage = stage,
                        failureCategory = categoryName,
                        summary = failureMsg,
                        detail = tailOutput,
                        url = sourceUrl,
                        sourceUrl = sourceUrl,
                        candidateUrl = currentJob?.request?.resolvedMediaUrl.orEmpty(),
                        jobAttempt = currentJob?.attempt ?: 0,
                        quality = currentJob?.request?.quality?.label.orEmpty(),
                        acquisitionMethod = if (fallbackFailed || currentJob?.request?.resolvedMediaUrl != null) {
                            DiagnosticStore.BROWSER_FALLBACK_METHOD
                        } else {
                            "Native yt-dlp"
                        },
                        runtime = formatRuntimeLabel(currentJob),
                    )
                )
            }
            cleanupStaging(jobId)
        } finally {
            activeFallbackEngines.remove(jobId)?.cancel()
            activeEngineTokens.remove(jobId)?.cancel()
            stopForegroundServiceIfIdleSafely()
        }
    }

    private fun startForegroundServiceSafely() {
        runCatching { DownloadService.start(context) }
            .onFailure {
                Log.w(
                    "VRKA",
                    "Foreground service unavailable; transfer continues under the user-initiated job: ${safeError(it)}",
                )
            }
    }

    private fun stopForegroundServiceIfIdleSafely() {
        runCatching { DownloadService.stopIfIdle(context) }
    }

    private suspend fun downloadOnce(job: DownloadJob) {
        if (isCancelled(job.id)) return
        val directory = stagingDirectory(job.id)
        update(
            job.id,
            state = JobState.DOWNLOADING,
            detail = "Downloading",
            error = "",
            // A runtime switch is refused while jobs are active, so this snapshot is the
            // runtime this job's process actually runs on for its whole lifetime.
            runtimeName = ytDlpRuntimeManager.activeDescription(),
            runtimeVersion = normalizedVersion(ytDlpRuntimeManager.activeVersion()),
            resetSize = true,
            persist = true,
        )
        var lastUiUpdate = 0L
        val outputLines = mutableListOf<String>()
        var transferStarted = false
        // Separate video/audio streams report their own totals; the merged job total is the sum.
        val sizeTracker = DownloadSizeTracker()
        val resolutionTracker = ActualResolutionTracker()
        var pendingStreamMetadata = java.util.ArrayDeque<StreamMetadata>()
        var activeStreamKind: ActiveStream? = null

        val response = try {
            val includeSubtitles = job.request.resolvedMediaUrl == null
            YoutubeDL.getInstance().execute(
                DownloadRequestFactory.download(job, directory, includeSubtitles = includeSubtitles),
                job.id,
            ) { progress, eta, line ->
                outputLines.add(line)
                if (outputLines.size > 200) {
                    outputLines.removeAt(0)
                }

                val isCommandLineEcho = line.startsWith("[debug]", ignoreCase = true) ||
                    line.contains("--print", ignoreCase = true) ||
                    line.contains("%(title)s", ignoreCase = true)

                if (!isCommandLineEcho && (TRANSFER_STARTED_MARKERS.any { line.contains(it, ignoreCase = true) } || progress > 0f)) {
                    transferStarted = true
                }

                if (!isCommandLineEcho) {
                    PlaylistProgressParser.parse(line)?.let { (index, total) ->
                        // A playlist item is a new transfer scope. The previous item's
                        // percentage and stage must not leak into this item.
                        sizeTracker.reset()
                        pendingStreamMetadata.clear()
                        activeStreamKind = null
                        update(
                            job.id,
                            detail = "Downloading",
                            progress = 0f,
                            resetSize = true,
                            resetTransferScope = true,
                            playlistItemIndex = index,
                            playlistItemCount = total,
                            expectedAttempt = job.attempt,
                        )
                    }
                    StreamMarker.parse(line)?.let { metadata ->
                        pendingStreamMetadata.addLast(metadata)
                    }
                    if (line.contains("[download] Destination:", ignoreCase = true)) {
                        sizeTracker.beginStream()
                        val destination = line.substringAfter("Destination:", "").trim()
                        val kind = StreamClassification.classify(
                            pendingStreamMetadata.pollFirst(),
                            destination,
                        )
                        activeStreamKind = kind
                        update(
                            job.id,
                            activeStream = kind,
                            expectedAttempt = job.attempt,
                        )
                    }
                }

                if (!isCommandLineEcho && line.contains("__VRKA_TITLE__", ignoreCase = true)) {
                    val parsedTitle = line.substringAfter("__VRKA_TITLE__").substringAfter("__vrka_title__").trim()
                    if (parsedTitle.isNotBlank()) {
                        update(job.id, title = parsedTitle, expectedAttempt = job.attempt)
                    }
                }

                if (!isCommandLineEcho && job.request.mode == MediaMode.VIDEO) {
                    ActualResolutionTracker.parseMarker(line)?.let { height ->
                        resolutionTracker.record(height)
                        resolutionTracker.summary(job.request.isPlaylist)?.let { summary ->
                            update(job.id, actualResolution = summary, expectedAttempt = job.attempt)
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUiUpdate >= 250 || progress >= 100f) {
                    lastUiUpdate = now
                    val sizeUpdate = if (isCommandLineEcho) {
                        null
                    } else {
                        DownloadSizeParser.parseProgressLine(line)?.let(sizeTracker::update)
                    }
                    val detail = when {
                        line.contains("[Merger]", true) ||
                            line.contains("[ExtractAudio]", true) ||
                            line.contains("[Metadata]", true) -> "Post-processing"
                        else -> "Downloading"
                    }
                    if (detail == "Post-processing") {
                        activeStreamKind = null
                        pendingStreamMetadata.clear()
                    }
                    update(
                        job.id,
                        state = if (detail == "Post-processing") {
                            JobState.POSTPROCESSING
                        } else {
                            JobState.DOWNLOADING
                        },
                        progress = progress.coerceIn(0f, 100f),
                        downloadedBytes = sizeUpdate?.currentDownloadedBytes,
                        reportedTotalBytes = sizeUpdate?.currentTotalBytes,
                        reportedTotalIsExact = sizeUpdate?.currentIsExact,
                        activeStream = if (detail == "Post-processing") {
                            null
                        } else {
                            activeStreamKind ?: ActiveStream.MEDIA
                        },
                        resetTransferScope = detail == "Post-processing",
                        speed = speedPattern.find(line)?.groupValues?.getOrNull(1).orEmpty(),
                        etaSeconds = eta.takeIf { it >= 0 },
                        detail = detail,
                        expectedAttempt = job.attempt,
                    )
                }
            }
        } catch (e: Exception) {
            val tail = outputLines.takeLast(50)
            val combined = (tail + listOfNotNull(e.message)).joinToString("\n")
            if (TRANSFER_STARTED_MARKERS.any { combined.contains(it, ignoreCase = true) }) {
                transferStarted = true
            }
            throw DownloadExecutionException(
                message = e.message ?: "yt-dlp execution failed",
                exitCode = -1,
                outputTail = tail,
                transferStarted = transferStarted,
            )
        }

        if (isCancelled(job.id)) return
        if (response.exitCode != 0) {
            val errText = response.err.ifBlank { "yt-dlp exited with code " + response.exitCode }
            val tail = (outputLines + response.out.lines()).filter { it.isNotBlank() }.takeLast(50)
            val combined = (tail + errText).joinToString("\n")
            if (TRANSFER_STARTED_MARKERS.any { combined.contains(it, ignoreCase = true) }) {
                transferStarted = true
            }
            throw DownloadExecutionException(
                message = errText,
                exitCode = response.exitCode,
                outputTail = tail,
                transferStarted = transferStarted,
            )
        }
        val finalizeStarted = SystemClock.elapsedRealtime()
        fun markFinalize(stage: String) {
            Log.d("VRKA", "Finalize job=${job.id} stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - finalizeStarted}")
        }
        markFinalize("transfer-exited")
        update(
            job.id,
            state = JobState.POSTPROCESSING,
            progress = 100f,
            detail = if (job.request.mode == MediaMode.VIDEO) "Merging output" else "Processing audio",
            resetSize = true,
            resetTransferScope = true,
            persist = false,
        )
        markPublishStarted(job.id)
        markFinalize("publish-marker-persisted")
        var outputs = if (job.request.commandTemplateId != null) {
            directory.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in PUBLISHABLE_EXTENSIONS && isInsideDirectory(directory, it) }
                .sortedBy(File::lastModified)
                .toList()
        } else {
            DownloadRequestFactory.outputPaths(response.out)
                .map(::File)
                .filter(File::isFile)
        }
        if (outputs.isEmpty()) {
            outputs = directory.walkTopDown()
                .filter {
                    it.isFile && !it.name.endsWith(".part") &&
                        it.extension.lowercase() in PUBLISHABLE_EXTENSIONS && isInsideDirectory(directory, it)
                }
                .sortedBy(File::lastModified)
                .toList()
        }
        check(outputs.isNotEmpty()) { "yt-dlp completed but produced no output file." }
        markFinalize("outputs-discovered")

        if (job.request.mode == MediaMode.VIDEO && job.request.h264CompatibilityMode) {
            outputs = transcodeVideosToH264(job, outputs, directory)
            markFinalize("transcode-complete")
        }
        var jobSubStatus = com.mvrk.vrka.subtitle.SubtitleStatus.NONE
        var jobSubNote = ""

        if (job.request.downloadSubtitles && outputs.isNotEmpty()) {
            val primaryVideo = outputs.firstOrNull { it.extension in listOf("mp4", "mkv", "webm", "mov") }
            if (primaryVideo != null && job.request.mode == MediaMode.VIDEO && job.request.embedSubtitles &&
                verifySubtitlePresence(job.id, primaryVideo)
            ) {
                jobSubStatus = com.mvrk.vrka.subtitle.SubtitleStatus.EMBEDDED_VERIFIED
                jobSubNote = "subtitles embedded"
            } else if (outputs.any { it.extension in listOf("srt", "vtt", "ass", "ssa") }) {
                jobSubStatus = com.mvrk.vrka.subtitle.SubtitleStatus.SAVED_SEPARATELY
                jobSubNote = "subtitles saved"
            } else {
                jobSubStatus = com.mvrk.vrka.subtitle.SubtitleStatus.UNAVAILABLE
                jobSubNote = "subtitles unavailable"
            }
        }

        update(
            job.id,
            state = JobState.POSTPROCESSING,
            detail = "Validating output",
            persist = false,
            expectedAttempt = job.attempt,
        )
        if (job.request.mode == MediaMode.VIDEO) {
            requireVideoStreams(job, outputs, resolutionTracker)
        } else {
            requireAudioStreams(job, outputs)
        }
        markFinalize("validation-complete")
        // Final on-disk size is the most accurate number for the queue; per-stream progress
        // totals only describe the last video/audio download.
        val finalBytes = DownloadAccounting.mediaOutputBytes(outputs)
        markFinalize("size-accounted")
        if (finalBytes != null) {
            // The transfer byte counters described the last stream; the post-processing
            // total describes every media output that will be published.
            update(
                job.id,
                reportedTotalBytes = finalBytes,
                reportedTotalIsExact = true,
                activeStream = null,
                resetTransferScope = true,
                expectedAttempt = job.attempt,
            )
        }
        if (isCancelled(job.id)) throw CancellationException("Download cancelled")
        update(
            job.id,
            state = JobState.POSTPROCESSING,
            detail = "Publishing to Downloads",
            persist = false,
        )
        markFinalize("publication-started")
        val published = mutableListOf<String>()
        val publishedMedia = mutableListOf<PublishedOutput>()
        try {
            val planned = outputs.mapIndexed { index, file ->
                file to preferredOutputName(job, file, index, outputs.size)
            }
            val publishedOutputs = publisher.publishAll(
                files = planned,
                destinationTreeUri = job.request.destinationTreeUri,
                jobId = job.id,
                shouldContinue = { !isCancelled(job.id) },
                onPublished = { index, total ->
                    update(
                        job.id,
                        detail = if (total == 1) {
                            "Publishing file"
                        } else {
                            "Publishing ${index + 1} of $total"
                        },
                        expectedAttempt = job.attempt,
                    )
                },
            )
            publishedOutputs.forEach { output ->
                published += output.uri.toString()
                if (DownloadAccounting.isMediaExtension(output.extension)) {
                    publishedMedia += output
                }
            }
        } catch (error: Throwable) {
            published.forEach { uri -> runCatching { publisher.delete(uri) } }
            throw error
        }
        val isOpus = job.request.mode == MediaMode.AUDIO && job.request.audioFormat == AudioFormat.OPUS
        val transcoded = isOpus && outputLines.any { it.contains("libopus", ignoreCase = true) || it.contains("Converting audio", ignoreCase = true) }
        val doneDetail = if (isOpus) {
            val base = if (transcoded) "Saved (transcoded to Opus)" else "Saved (native Opus stream copy)"
            if (jobSubNote.isNotBlank()) "$base • $jobSubNote" else base
        } else if (published.size == 1) {
            if (jobSubNote.isNotBlank()) "Saved to Downloads/VRKA ($jobSubNote)" else "Saved to Downloads/VRKA"
        } else {
            if (jobSubNote.isNotBlank()) "Saved ${published.size} files ($jobSubNote)" else "Saved ${published.size} files"
        }
        // The published media documents are authoritative for the finished size. Subtitle
        // sidecars are deliberately excluded so a playlist/video size never includes them.
        val publishedBytes = publishedMedia
            .map { it.bytes }
            .takeIf { it.isNotEmpty() && it.all { bytes -> bytes > 0L } }
            ?.sum()
            ?: finalBytes
        markFinalize("publication-complete")
        val committed = commitTerminalPublishedJob(
            jobId = job.id,
            detail = doneDetail,
            downloadedBytes = finalBytes,
            reportedTotalBytes = finalBytes,
            reportedTotalIsExact = finalBytes != null,
            actualFinalFileBytes = publishedBytes,
            outputUris = published,
            subtitleStatus = jobSubStatus,
        )
        if (!committed) {
            Log.w("VRKA", "Terminal persistence failed for ${job.id}; journal and staging retained for recovery.")
        }
    }

    private suspend fun downloadViaMediaEngine(
        job: DownloadJob,
        bundle: HandoffBundle,
        directory: File,
    ) {
        if (isCancelled(job.id)) return
        directory.mkdirs()

        val headers = buildMap {
            putAll(job.request.customHeaders)
            if (job.request.referer.isNotBlank()) put("Referer", job.request.referer)
            if (job.request.origin.isNotBlank()) put("Origin", job.request.origin)
            if (bundle.referer.isNotBlank()) put("Referer", bundle.referer)
            if (bundle.origin.isNotBlank()) put("Origin", bundle.origin)
            if (bundle.userAgent.isNotBlank()) put("User-Agent", bundle.userAgent)
            putAll(bundle.headers)
        }

        val mediaKind = when {
            bundle.mediaKind == CandidateKind.HLS || MediaAssembly.isHlsPlaylist(bundle.mediaUrl) -> EngineMediaKind.HLS
            bundle.mediaKind == CandidateKind.DASH || MediaAssembly.isDashManifest(bundle.mediaUrl) -> EngineMediaKind.DASH
            bundle.mediaKind == CandidateKind.DIRECT -> if (job.request.mode == MediaMode.AUDIO) EngineMediaKind.PROGRESSIVE_AUDIO else EngineMediaKind.PROGRESSIVE_VIDEO
            else -> EngineMediaKind.UNKNOWN
        }

        val ext = if (job.request.mode == MediaMode.AUDIO) job.request.audioFormat.codec else "mp4"
        val stagingFile = File(directory, "media.$ext")

        val handoffContext = MediaHandoffContext(
            url = bundle.mediaUrl,
            mediaKind = mediaKind,
            headers = headers,
            title = job.title.ifBlank { "media" },
            stagingOutputFile = stagingFile,
            durationSeconds = if (bundle.expectedDurationSeconds > 0.0) bundle.expectedDurationSeconds else null,
            expectedHeight = job.request.quality.height,
            originalPageUrl = job.request.url,
        )

        val router = MediaEngineRouter(context, GeckoRuntimeManager.getInstance(context).runtime)
        val token = SimpleEngineCancellationToken()
        activeEngineTokens[job.id] = token

        try {
            update(
                job.id,
                state = JobState.DOWNLOADING,
                detail = "Downloading media stream",
                error = "",
                resetSize = true,
                persist = true,
            )

            val result = router.executeDownload(
                handoffContext = handoffContext,
                onProgress = { progress ->
                    when (progress) {
                        is EngineDownloadProgress.Initializing -> {
                            update(job.id, detail = progress.message)
                        }
                        is EngineDownloadProgress.Downloading -> {
                            update(
                                job.id,
                                progress = progress.percentage,
                                speed = progress.speedBytesPerSec?.let { UnifiedProgressSnapshot.formatSpeed(it) }.orEmpty(),
                                etaSeconds = progress.etaSeconds,
                                downloadedBytes = progress.bytesRead,
                                reportedTotalBytes = progress.totalBytes?.takeIf { it > 0L },
                                // Engines derive totals from Content-Length when available and
                                // from duration/bitrate otherwise; treat them as approximate.
                                reportedTotalIsExact = false,
                                detail = progress.detail.ifBlank { "Downloading stream" },
                            )
                        }
                        is EngineDownloadProgress.Finalizing -> {
                            update(job.id, progress = 100f, detail = progress.message)
                        }
                    }
                },
                cancellationToken = token,
            )

            if (isCancelled(job.id)) return

            when (result) {
                is EngineExecutionResult.Success -> {
                    update(
                        job.id,
                        state = JobState.POSTPROCESSING,
                        progress = 100f,
                        detail = "Publishing to Downloads",
                        persist = true,
                    )
                    markPublishStarted(job.id)
                    val outputs = listOf(result.outputFile)
                    if (job.request.mode == MediaMode.VIDEO) {
                        requireVideoStreams(job, outputs)
                    } else {
                        requireAudioStreams(job, outputs)
                    }
                    val published = mutableListOf<String>()
                    var publishedBytes: Long? = null
                    try {
                        val planned = outputs.mapIndexed { index, file ->
                            file to preferredOutputName(job, file, index, outputs.size)
                        }
                        val publishedOutputs = publisher.publishAll(
                            files = planned,
                            destinationTreeUri = job.request.destinationTreeUri,
                            jobId = job.id,
                            shouldContinue = { !isCancelled(job.id) },
                        )
                        publishedOutputs.forEach { output ->
                            published += output.uri.toString()
                            publishedBytes = (publishedBytes ?: 0L) + output.bytes
                        }
                    } catch (error: Throwable) {
                        published.forEach { uri -> runCatching { publisher.delete(uri) } }
                        throw error
                    }
                    val committed = commitTerminalPublishedJob(
                        jobId = job.id,
                        detail = if (published.size == 1) "Saved to Downloads/VRKA" else "Saved ${published.size} files",
                        downloadedBytes = result.totalBytes,
                        reportedTotalBytes = result.totalBytes,
                        reportedTotalIsExact = result.totalBytes > 0L,
                        actualFinalFileBytes = publishedBytes?.takeIf { it > 0L }
                            ?: result.totalBytes.takeIf { it > 0L },
                        outputUris = published,
                    )
                    if (!committed) {
                        Log.w("VRKA", "Terminal persistence failed for ${job.id}; journal and staging retained for recovery.")
                    }
                }
                is EngineExecutionResult.Failed -> {
                    throw result.error
                }
                is EngineExecutionResult.Cancelled -> {
                    throw CancellationException("Download cancelled")
                }
            }
        } finally {
            activeEngineTokens.remove(job.id)
        }
    }

    private fun isInsideDirectory(directory: File, file: File): Boolean = runCatching {
        val root = directory.canonicalFile.toPath()
        file.canonicalFile.toPath().startsWith(root)
    }.getOrDefault(false)

    private suspend fun transcodeVideosToH264(
        job: DownloadJob,
        outputs: List<File>,
        directory: File,
    ): List<File> {
        val videoFiles = outputs.filter { it.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov", "m4v", "ts") }
        if (videoFiles.isEmpty()) return outputs
        val codecs = videoFiles.associateWith { input ->
            if (isCancelled(job.id)) throw CancellationException("H.264 conversion cancelled")
            try {
                MediaAssembly.videoCodecName(input, context) { process -> activeMediaProcesses[job.id] = process }
            } finally {
                activeMediaProcesses.remove(job.id)
            }
        }
        val unsupportedCodec = codecs.values.any { codec -> codec == null }
        check(!unsupportedCodec) { "Could not verify the downloaded video codec; H.264 conversion was not started." }
        val needsEncoding = codecs.values.any { codec -> !MediaAssembly.isH264Codec(codec.orEmpty()) }
        if (!needsEncoding) return outputs

        val encoderAvailable = MediaAssembly.hasH264Encoder(context) { process ->
            activeMediaProcesses[job.id] = process
        }
        activeMediaProcesses.remove(job.id)
        if (isCancelled(job.id)) throw CancellationException("H.264 conversion cancelled")
        check(encoderAvailable) {
            "The installed FFmpeg build does not provide the libx264 encoder required for H.264 Compatibility Mode."
        }

        val replacements = mutableMapOf<File, File>()
        for ((index, input) in videoFiles.withIndex()) {
            if (isCancelled(job.id)) throw CancellationException("H.264 conversion cancelled")
            val codec = codecs[input].orEmpty()
            if (MediaAssembly.isH264Codec(codec)) continue

            update(
                job.id,
                state = JobState.POSTPROCESSING,
                detail = "Converting ${codec.uppercase()} video to H.264 (CPU, battery, and storage use)",
                progress = 0f,
                speed = "",
                etaSeconds = null,
                persist = true,
            )
            val output = File(directory, ".vrka_h264_${UUID.randomUUID()}.mkv")
            val command = MediaAssembly.buildH264CompatibilityCommand(context, input, output)
            val durationUs = MediaAssembly.durationMilliseconds(input, context)?.times(1_000L)
            val completed = MediaAssembly.executeFfmpeg(
                command,
                context,
                onProcessStarted = { process -> activeMediaProcesses[job.id] = process },
                onOutputLine = { line ->
                    if (line.startsWith("out_time_ms=")) {
                        val outTimeUs = line.substringAfter('=').toLongOrNull()
                        if (outTimeUs != null && durationUs != null && durationUs > 0L) {
                            val progress = (outTimeUs.toDouble() * 100.0 / durationUs).toFloat().coerceIn(0f, 99f)
                            update(job.id, progress = progress, detail = "Converting to H.264 (${progress.toInt()}%)")
                        }
                    } else if (line == "progress=end") {
                        update(job.id, progress = 99f, detail = "Validating H.264 output")
                    }
                },
            )
            activeMediaProcesses.remove(job.id)
            if (isCancelled(job.id)) {
                output.delete()
                throw CancellationException("H.264 conversion cancelled")
            }
            check(completed && output.isFile && output.length() > 0L) { "FFmpeg could not convert the video to H.264." }
            check(MediaAssembly.isH264Codec(MediaAssembly.videoCodecName(output, context).orEmpty())) {
                "FFmpeg output validation failed: the converted file is not H.264."
            }
            replacements[input] = output
        }
        return outputs.map { replacements[it] ?: it }
    }



    private fun requireAudioStreams(job: DownloadJob, outputs: List<File>) {
        val expectedBitrate = if (job.request.audioFormat == AudioFormat.MP3) job.request.mp3Bitrate else null
        val audioFiles = outputs.filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
        check(audioFiles.isNotEmpty()) { "Audio mode produced no audio file; refusing to publish custom-command output." }
        audioFiles.forEach { file ->
            val info = try {
                AudioValidator.validateAudioOutput(file, job.request.audioFormat, expectedBitrate, context) { process ->
                    activeMediaProcesses[job.id] = process
                }
            } finally {
                activeMediaProcesses.remove(job.id)
            }
            Log.i("VRKA", "Audio validation passed for ${file.name}: format=${job.request.audioFormat}, codec=${info.codecName}, duration=${info.durationMs}ms")
        }
    }

    private fun verifySubtitlePresence(jobId: String, file: File): Boolean = try {
        MediaAssembly.verifySubtitlePresence(file, context) { process ->
            activeMediaProcesses[jobId] = process
        }
    } finally {
        activeMediaProcesses.remove(jobId)
    }

    private fun requireVideoStreams(
        job: DownloadJob,
        outputs: List<File>,
        resolutionTracker: ActualResolutionTracker = ActualResolutionTracker(),
    ) {
        if (job.request.mode != MediaMode.VIDEO) return
        val videoFiles = outputs.filter { it.extension.lowercase() in VIDEO_EXTENSIONS }
        check(videoFiles.isNotEmpty()) { "Video mode produced no video file; refusing to publish custom-command output." }
        videoFiles.forEach { file ->
            val media = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val width = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull()
                    val height = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull()
                    Triple(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" ||
                            width?.let { it > 0 } == true,
                        width,
                        height,
                    )
                } finally {
                    retriever.release()
                }
            }.getOrDefault(Triple(false, null, null))
            check(media.first) {
                "Video mode produced no video stream; refusing to publish an audio-only file."
            }
            if (media.third != null && media.third!! > 0) {
                resolutionTracker.record(media.third)
            }
            job.request.quality.height?.let { cap ->
                check(media.third == null || media.third!! <= cap) {
                    "Downloaded video exceeds the requested ${cap}p quality cap."
                }
            }
        }
        resolutionTracker.summary(job.request.isPlaylist)?.let { summary ->
            update(job.id, actualResolution = summary)
        }
    }

    private fun preferredOutputName(job: DownloadJob, file: File, index: Int, total: Int): String {
        val title = job.title
            .trim()
            .takeIf { it.isNotBlank() && it != "Browser verification" }
        val host = runCatching { Uri.parse(job.request.url).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .takeIf(String::isNotBlank)
        val stem = title ?: host ?: "VRKA-${job.id.take(8)}"
        val suffix = if (total > 1) " ${index + 1}" else ""
        val extension = file.extension
        return if (extension.isBlank()) stem + suffix else "$stem$suffix.$extension"
    }

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(initialized) {
            if (initialized.get()) return
            _runtime.value = RuntimeStatus(busy = true, message = "Preparing yt-dlp and FFmpeg")
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            DownloadRequestFactory.initNativeLibraryDir(context.applicationInfo.nativeLibraryDir)
            DownloadRequestFactory.initCacheDir(
                File(context.cacheDir, "yt-dlp").apply { mkdirs() }.absolutePath,
            )
            MediaAssembly.fixUsrLibSymlinks(File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib"))
            MediaAssembly.fixUsrLibSymlinks(File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib"))
            initialized.set(true)
            ytDlpRuntimeManager.applyActiveRuntime().onFailure { error ->
                Log.w("VRKA", "Could not activate selected yt-dlp runtime; using stock: ${safeError(error)}")
            }
            refreshRuntimeStatus()
        }
    }

    private fun refreshRuntimeStatus() {
        val version = normalizedVersion(ytDlpRuntimeManager.activeVersion())
        val custom = ytDlpRuntimeManager.isCustomActive()
        _runtime.value = RuntimeStatus(
            initialized = initialized.get(),
            version = version,
            message = when {
                version.isBlank() -> ytDlpRuntimeManager.activeDescription() + " ready"
                custom -> ytDlpRuntimeManager.activeDescription() + " • yt-dlp " + version
                else -> "yt-dlp " + version + " ready"
            },
        )
    }

    /**
     * Writes the publish marker synchronously before any output leaves staging. If the
     * process dies afterwards, restart recovery fails closed instead of publishing a copy.
     */
    private fun markPublishStarted(jobId: String) {
        mutate(persist = false) { jobs ->
            jobs.map { job ->
                if (job.id == jobId) job.copy(publishStarted = true) else job
            }
        }
        check(persistJobsNow()) {
            "Could not persist the publish marker; refusing to publish to prevent duplicate files."
        }
    }

    private fun stagingDirectory(jobId: String): File {
        stagingRoot.mkdirs()
        return File(stagingRoot, jobId).apply { mkdirs() }
    }

    private fun cleanupStaging(jobId: String) {
        val child = File(stagingRoot, jobId)
        val rootPath = stagingRoot.absoluteFile.toPath().normalize()
        val childPath = child.absoluteFile.toPath().normalize()
        if (childPath.startsWith(rootPath) && childPath != rootPath) {
            runCatching { child.deleteRecursively() }
        }
    }

    /**
     * Executes the durable terminal completion protocol for published outputs:
     * 1. Confirms provider outputs are finalized (validated by publisher beforehand).
     * 2. Constructs the complete terminal JobStore state.
     * 3. Persists the terminal state using a checked, awaited save operation.
     * 4. Verifies that the exact transaction state was successfully persisted.
     * 5. Only then marks its publication journal entries reconciled.
     * 6. Only after successful reconciliation performs staging cleanup and posts notification.
     *
     * Returns true if terminal state was durably persisted and reconciled; false if persistence failed.
     */
    internal suspend fun commitTerminalPublishedJob(
        jobId: String,
        detail: String,
        downloadedBytes: Long?,
        reportedTotalBytes: Long?,
        reportedTotalIsExact: Boolean,
        actualFinalFileBytes: Long?,
        outputUris: List<String>,
        subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus? = null,
    ): Boolean {
        // Step 1: In-memory mutation
        var targetJob: DownloadJob? = null
        mutate(persist = false) { list ->
            list.map { job ->
                if (job.id == jobId && JobStateGuard.allowsStateChange(job.state, JobState.DONE)) {
                    val updated = job.copy(
                        state = JobState.DONE,
                        progress = 100f,
                        detail = detail,
                        downloadedBytes = downloadedBytes ?: job.downloadedBytes,
                        reportedTotalBytes = reportedTotalBytes ?: job.reportedTotalBytes,
                        reportedTotalIsExact = reportedTotalIsExact,
                        actualFinalFileBytes = actualFinalFileBytes ?: job.actualFinalFileBytes,
                        outputUris = outputUris,
                        error = "",
                        subtitleStatus = subtitleStatus ?: job.subtitleStatus,
                        updatedAt = System.currentTimeMillis(),
                    )
                    targetJob = updated
                    updated
                } else {
                    job
                }
            }
        }

        if (targetJob == null) {
            Log.w("VRKA", "Job $jobId was not found or state guard rejected transition to DONE.")
            return false
        }

        // Step 2 & 3: Checked, awaited terminal persistence
        val persistSuccess = withContext(Dispatchers.IO + NonCancellable) {
            persistJobsNow()
        }

        if (!persistSuccess) {
            reportStoreIssue(
                stage = "Queue/history persistence",
                summary = "Failed to persist terminal DONE state to JobStore",
                detail = "JobStore save returned false for job $jobId; retaining publication journal and staging data for restart recovery.",
            )
            Log.e("VRKA", "JobStore save failed for job $jobId; retaining journal and staging data for recovery.")
            return false
        }

        // Step 4: Verification - verify in-memory job state in current jobs matches the persisted terminal state
        val verifiedJob = current(jobId)
        if (verifiedJob?.state != JobState.DONE || verifiedJob.outputUris != outputUris) {
            Log.e("VRKA", "Terminal job state verification failed for $jobId; retaining journal and staging data.")
            return false
        }

        // Step 5: Mark publication journal reconciled ONLY after verified JobStore persistence
        runCatching { diagnosticStore.clearUrlsForJob(jobId) }
        runCatching { publisher.activeJournal.markReconciled(jobId) }

        // Step 6: Post completion notification and clean up staging ONLY after successful reconciliation
        runCatching { current(jobId)?.let { DownloadNotifications.notifyCompleted(context, it) } }
        Log.d("VRKA", "Finalize job=$jobId stage=done-state-posted")
        cleanupStaging(jobId)
        Log.d("VRKA", "Finalize job=$jobId stage=staging-cleanup-complete")
        return true
    }

    private fun current(jobId: String): DownloadJob? =
        _jobs.value.firstOrNull { it.id == jobId }

    private fun isCancelled(jobId: String): Boolean =
        jobId in cancelled || current(jobId)?.state == JobState.CANCELLED

    private fun replace(job: DownloadJob, persist: Boolean) {
        mutate(persist) { list -> list.map { if (it.id == job.id) job else it } }
    }

    private fun formatRuntimeLabel(job: DownloadJob?): String {
        if (job == null || job.runtimeName.isBlank()) return ""
        return if (job.runtimeVersion.isBlank()) {
            job.runtimeName
        } else {
            "${job.runtimeName} • yt-dlp ${job.runtimeVersion}"
        }
    }

    private fun update(
        jobId: String,
        state: JobState? = null,
        title: String? = null,
        detail: String? = null,
        progress: Float? = null,
        speed: String? = null,
        etaSeconds: Long? = null,
        downloadedBytes: Long? = null,
        reportedTotalBytes: Long? = null,
        reportedTotalIsExact: Boolean? = null,
        estimatedTotalBytes: Long? = null,
        estimatedSizeAccuracy: SizeEstimateKind? = null,
        activeStream: ActiveStream? = null,
        playlistItemIndex: Int? = null,
        playlistItemCount: Int? = null,
        resetTransferScope: Boolean = false,
        actualFinalFileBytes: Long? = null,
        resetSize: Boolean = false,
        resetEstimate: Boolean = false,
        outputUris: List<String>? = null,
        error: String? = null,
        actualResolution: String? = null,
        runtimeName: String? = null,
        runtimeVersion: String? = null,
        subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus? = null,
        persist: Boolean = false,
        expectedAttempt: Int? = null,
    ) {
        val terminalUpdate = state?.isTerminal == true
        // A terminal update is saved directly below for durability. Do not also enqueue
        // the conflated persist channel for it, which would serialize the same job list twice.
        mutate(persist && !terminalUpdate) { list ->
            list.map { job ->
                when {
                    job.id != jobId -> job
                    expectedAttempt != null && job.attempt != expectedAttempt -> job
                    !JobStateGuard.allowsStateChange(job.state, state) -> job
                    state == null && !JobStateGuard.allowsMetadataUpdate(job.state) -> job
                    else -> job.copy(
                        state = state ?: job.state,
                        title = title ?: job.title,
                        detail = detail ?: job.detail,
                        progress = progress ?: job.progress,
                        speed = speed ?: job.speed,
                        etaSeconds = etaSeconds ?: job.etaSeconds,
                        downloadedBytes = if (resetSize) null else downloadedBytes ?: job.downloadedBytes,
                        reportedTotalBytes = if (resetSize) {
                            null
                        } else {
                            reportedTotalBytes ?: job.reportedTotalBytes
                        },
                        reportedTotalIsExact = if (resetSize) {
                            false
                        } else {
                            reportedTotalIsExact ?: job.reportedTotalIsExact
                        },
                        estimatedTotalBytes = if (resetEstimate) {
                            null
                        } else {
                            estimatedTotalBytes ?: job.estimatedTotalBytes
                        },
                        estimatedSizeAccuracy = if (resetEstimate) {
                            SizeEstimateKind.UNAVAILABLE
                        } else {
                            estimatedSizeAccuracy ?: job.estimatedSizeAccuracy
                        },
                        activeStream = when {
                            activeStream != null -> activeStream
                            resetTransferScope -> null
                            else -> job.activeStream
                        },
                        playlistItemIndex = when {
                            playlistItemIndex != null -> playlistItemIndex
                            resetTransferScope -> null
                            else -> job.playlistItemIndex
                        },
                        playlistItemCount = when {
                            playlistItemCount != null -> playlistItemCount
                            resetTransferScope -> null
                            else -> job.playlistItemCount
                        },
                        actualFinalFileBytes = if (resetSize) {
                            null
                        } else {
                            actualFinalFileBytes ?: job.actualFinalFileBytes
                        },
                        outputUris = outputUris ?: job.outputUris,
                        error = error ?: job.error,
                        actualResolution = actualResolution ?: job.actualResolution,
                        runtimeName = runtimeName ?: job.runtimeName,
                        runtimeVersion = runtimeVersion ?: job.runtimeVersion,
                        subtitleStatus = subtitleStatus ?: job.subtitleStatus,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        if (terminalUpdate) {
            scope.launch(Dispatchers.IO + NonCancellable) {
                persistJobsNow()
            }
        }
    }

    private fun mutate(persist: Boolean, transform: (List<DownloadJob>) -> List<DownloadJob>) {
        synchronized(_jobs) {
            _jobs.value = transform(_jobs.value)
            if (persist) persistRequests.trySend(Unit)
        }
    }

    private fun normalizedVersion(value: String): String =
        value.removePrefix("yt-dlp ").trim()

    private fun safeError(error: Throwable): String {
        val raw = (error.message ?: error::class.java.simpleName)
        val cleaned = cleanErrorOutput(raw)
        val (_, guidance) = formatDownloadError(cleaned)
        val text = if (guidance.isNotBlank() && guidance != cleaned) {
            guidance
        } else {
            cleaned.ifBlank { raw }.lineSequence().toList().takeLast(2).joinToString(" ")
        }
        val redactedText = HeaderValidation.redactSensitiveHeaderInText(text)
        return redactedText
            .replace(Regex("""(?i)(cookie|authorization|proxy-authorization|token|signature|key|secret)=?[^\s&]*"""), "$1=[REDACTED]")
            .replace(Regex("""https?://[^\s]+"""), "[private URL]")
            .take(320)
            .ifBlank { "The operation failed." }
    }

    private fun isHttpUrl(value: String): Boolean = CustomCommandPolicy.isHttpUrl(value)

    companion object {
        private const val MAX_ESTIMATE_DIAGNOSTICS = 64
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "mka", "aac", "opus", "flac", "wav", "ogg")
        private val PUBLISHABLE_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + setOf("srt", "vtt", "ass", "ssa")

        fun create(context: Context): VrkaDownloadManager {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val settings = SettingsRepository(context.applicationContext, scope)
            return VrkaDownloadManager(context.applicationContext, settings, scope)
        }
    }
}
