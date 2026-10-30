package com.mvrk.vrka

import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mvrk.vrka.engine.*
import com.mvrk.vrka.update.AppUpdateManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

class VrkaDownloadManager(
    private val context: Context,
    val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
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
    private val store = JobStore(context)
    /** Set when a user-initiated transfer job is pending or has already started this process. */
    private val transferJobWasScheduled =
        DownloadTransferRuntime.wasTransferJobInvoked || DownloadTransferScheduler.isScheduled(context)
    private val publisher = OutputPublisher(context, settingsRepository)
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
    private val estimateCache = MetadataEstimateCache()
    private val documentCache = MetadataDocumentCache()
    private val documentInFlight =
        HashMap<String, CompletableDeferred<MetadataDocumentCache.Document?>>()
    private val recordedEstimateDiagnostics = ConcurrentHashMap.newKeySet<String>()

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
            estimateCache.clear()
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
            // Runs after persisted state is merged so the plan sees every record.
            resumeInterruptedWorkIfNeeded()
            for (ignored in persistRequests) {
                store.save(_jobs.value)
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
        val updated = job.copy(
            request = job.request.copy(destinationTreeUri = treeUri),
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
            mutate(persist = true) { list -> list.filterNot { it.state.isTerminal } }
        }
    }

    /**
     * Metadata-only preflight for the confirmation and share UIs. Runs on the active runtime
     * after runtime initialization; failures degrade to an "unavailable" estimate.
     *
     * The raw metadata document is cached separately from the per-selection estimate. Changing
     * quality or format therefore recalculates locally instead of launching another yt-dlp
     * extraction, and concurrent callers for the same source share one process. [forceRefresh]
     * bypasses both caches for the user-visible retry action.
     */
    suspend fun estimateSize(request: DownloadRequest, forceRefresh: Boolean = false): SizeEstimate =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            ensureInitialized()
            val runtimeVersion = normalizedVersion(ytDlpRuntimeManager.activeVersion())
            val formatKey = MetadataEstimateCache.formatKeyFor(request)
            val estimateKey = MetadataEstimateCache.keyFor(request, runtimeVersion)
            if (!forceRefresh) {
                estimateCache.get(estimateKey)?.let {
                    logEstimateLatency("estimate-cache", startedAt)
                    return@withContext it
                }
            }

            val documentKey = MetadataDocumentCache.keyFor(request, runtimeVersion)
            if (!forceRefresh) {
                documentCache.get(documentKey)?.let { document ->
                    val estimate = estimateFromDocument(document, request, formatKey)
                    estimateCache.put(estimateKey, estimate)
                    logEstimateLatency("document-cache", startedAt)
                    return@withContext estimate
                }
            }

            val (deferred, owner) = synchronized(documentInFlight) {
                val existing = documentInFlight[documentKey]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<MetadataDocumentCache.Document?>()
                        .also { documentInFlight[documentKey] = it } to true
                }
            }
            if (!owner) {
                val document = deferred.await()
                    ?: return@withContext SizeEstimate.unavailable("process")
                val estimate = estimateFromDocument(document, request, formatKey)
                estimateCache.put(estimateKey, estimate)
                logEstimateLatency("shared-in-flight", startedAt)
                return@withContext estimate
            }
            try {
                val preflight = sizeEstimator.probe(request)
                val document = preflight.document?.let {
                    MetadataDocumentCache.Document(it, formatKey, System.currentTimeMillis())
                }
                if (document != null) documentCache.put(documentKey, document)
                estimateCache.put(estimateKey, preflight.estimate)
                if (!preflight.estimate.isAvailable &&
                    preflight.estimate.detail != "custom-command" &&
                    rememberEstimateDiagnostic(estimateKey)
                ) {
                    recordSizeDiagnostic(request, preflight)
                }
                deferred.complete(document)
                logEstimateLatency(
                    if (preflight.document != null) "extraction" else "extraction-failed",
                    startedAt,
                    preflight.durationMs,
                )
                preflight.estimate
            } catch (error: Throwable) {
                deferred.complete(null)
                throw error
            } finally {
                synchronized(documentInFlight) { documentInFlight.remove(documentKey) }
            }
        }

    private fun estimateFromDocument(
        document: MetadataDocumentCache.Document,
        request: DownloadRequest,
        formatKey: String,
    ): SizeEstimate = sizeEstimator.estimateFromDocument(
        document = document.json,
        request = request,
        recalculateSelection = document.sourceFormatKey != formatKey,
    )

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
                        "startedAt=${preflight.startedAtEpochMs} durationMs=${preflight.durationMs} " +
                            "exit=${preflight.exitCode ?: "unknown"} timedOut=${preflight.timedOut} " +
                            "stdoutChars=${preflight.stdoutChars}",
                    )
                    if (estimate.diagnostic.isNotBlank()) appendLine(estimate.diagnostic)
                    if (preflight.stderrTail.isNotBlank()) {
                        appendLine("stderr:")
                        appendLine(preflight.stderrTail)
                    }
                },
                url = request.url,
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
                                    updatedAt = System.currentTimeMillis(),
                                )
                                replace(updated, persist = true)
                                startForegroundServiceSafely()

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
                diagnosticStore.record(
                    DiagnosticEntry(
                        jobId = jobId,
                        title = currentJob?.title?.ifBlank { currentJob.request.url }.orEmpty(),
                        stage = stage,
                        failureCategory = categoryName,
                        summary = failureMsg,
                        detail = tailOutput,
                        url = currentJob?.request?.url.orEmpty(),
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
                    line.contains("before_dl:", ignoreCase = true) ||
                    line.contains("%(title)s", ignoreCase = true)

                if (!isCommandLineEcho && (TRANSFER_STARTED_MARKERS.any { line.contains(it, ignoreCase = true) } || progress > 0f)) {
                    transferStarted = true
                }

                if (!isCommandLineEcho && line.contains("__VRKA_TITLE__", ignoreCase = true)) {
                    val parsedTitle = line.substringAfter("__VRKA_TITLE__").substringAfter("__vrka_title__").trim()
                    if (parsedTitle.isNotBlank()) {
                        update(job.id, title = parsedTitle, expectedAttempt = job.attempt)
                    }
                }

                if (!isCommandLineEcho && job.request.mode == MediaMode.VIDEO && line.contains("__VRKA_RES__", ignoreCase = true)) {
                    val parsedRes = line.substringAfter("__VRKA_RES__").substringAfter("__vrka_res__").trim()
                    val resHeight = parsedRes.toIntOrNull()
                    if (resHeight != null && resHeight > 0) {
                        update(job.id, actualResolution = "${resHeight}p", expectedAttempt = job.attempt)
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
                    update(
                        job.id,
                        state = if (detail == "Post-processing") {
                            JobState.POSTPROCESSING
                        } else {
                            JobState.DOWNLOADING
                        },
                        progress = progress.coerceIn(0f, 100f),
                        downloadedBytes = sizeUpdate?.downloadedBytes,
                        reportedTotalBytes = sizeUpdate?.totalBytes,
                        reportedTotalIsExact = sizeUpdate?.totalIsExact,
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
        update(
            job.id,
            state = JobState.POSTPROCESSING,
            progress = 100f,
            detail = "Publishing to Downloads",
            persist = true,
        )
        markPublishStarted(job.id)
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

        if (job.request.mode == MediaMode.VIDEO && job.request.h264CompatibilityMode) {
            outputs = transcodeVideosToH264(job, outputs, directory)
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

        if (job.request.mode == MediaMode.VIDEO) {
            requireVideoStreams(job, outputs)
        } else {
            requireAudioStreams(job, outputs)
        }
        // Final on-disk size is the most accurate number for the queue; per-stream progress
        // totals only describe the last video/audio download.
        val finalBytes = outputs
            .filter { it.extension.lowercase() in VIDEO_EXTENSIONS || it.extension.lowercase() in AUDIO_EXTENSIONS }
            .sumOf { file -> runCatching { file.length() }.getOrDefault(0L) }
            .takeIf { it > 0L }
        if (isCancelled(job.id)) throw CancellationException("Download cancelled")
        val published = mutableListOf<String>()
        try {
            outputs.forEachIndexed { index, file ->
                if (isCancelled(job.id)) throw CancellationException("Download cancelled")
                val outputName = preferredOutputName(job, file, index, outputs.size)
                published += publisher.publish(file, outputName, destinationTreeUri = job.request.destinationTreeUri).toString()
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
        // The published document is the authority for the finished size; the staging length is
        // only a fallback when the provider does not expose it.
        val publishedBytes = published
            .mapNotNull(publisher::sizeOf)
            .takeIf { it.isNotEmpty() }
            ?.sum()
            ?: finalBytes
        update(
            job.id,
            state = JobState.DONE,
            progress = 100f,
            detail = doneDetail,
            downloadedBytes = finalBytes,
            reportedTotalBytes = finalBytes,
            reportedTotalIsExact = finalBytes != null,
            actualFinalFileBytes = publishedBytes,
            outputUris = published,
            error = "",
            subtitleStatus = jobSubStatus,
            persist = true,
        )
        current(job.id)?.let { DownloadNotifications.notifyCompleted(context, it) }
        cleanupStaging(job.id)
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
                    val outputs = listOf(result.outputFile)
                    if (job.request.mode == MediaMode.VIDEO) {
                        requireVideoStreams(job, outputs)
                    } else {
                        requireAudioStreams(job, outputs)
                    }
                    val published = mutableListOf<String>()
                    try {
                        outputs.forEachIndexed { index, file ->
                            if (isCancelled(job.id)) throw CancellationException("Download cancelled")
                            val outputName = preferredOutputName(job, file, index, outputs.size)
                            published += publisher.publish(file, outputName, destinationTreeUri = job.request.destinationTreeUri).toString()
                        }
                    } catch (error: Throwable) {
                        published.forEach { uri -> runCatching { publisher.delete(uri) } }
                        throw error
                    }
                    update(
                        job.id,
                        state = JobState.DONE,
                        progress = 100f,
                        detail = if (published.size == 1) "Saved to Downloads/VRKA" else "Saved ${published.size} files",
                        downloadedBytes = result.totalBytes,
                        reportedTotalBytes = result.totalBytes,
                        reportedTotalIsExact = result.totalBytes > 0L,
                        actualFinalFileBytes = published
                            .mapNotNull(publisher::sizeOf)
                            .takeIf { it.isNotEmpty() }
                            ?.sum()
                            ?: result.totalBytes.takeIf { it > 0L },
                        outputUris = published,
                        error = "",
                        persist = true,
                    )
                    current(job.id)?.let { DownloadNotifications.notifyCompleted(context, it) }
                    cleanupStaging(job.id)
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

    private fun requireVideoStreams(job: DownloadJob, outputs: List<File>) {
        if (job.request.mode != MediaMode.VIDEO) return
        val videoFiles = outputs.filter { it.extension.lowercase() in VIDEO_EXTENSIONS }
        check(videoFiles.isNotEmpty()) { "Video mode produced no video file; refusing to publish custom-command output." }
        var detectedResolution: String? = null
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
                detectedResolution = "${media.third}p"
            }
            job.request.quality.height?.let { cap ->
                check(media.third == null || media.third!! <= cap) {
                    "Downloaded video exceeds the requested ${cap}p quality cap."
                }
            }
        }
        if (detectedResolution != null && current(job.id)?.actualResolution == null) {
            update(job.id, actualResolution = detectedResolution)
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
        runCatching { store.save(_jobs.value) }
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
        actualFinalFileBytes: Long? = null,
        resetSize: Boolean = false,
        outputUris: List<String>? = null,
        error: String? = null,
        actualResolution: String? = null,
        runtimeName: String? = null,
        runtimeVersion: String? = null,
        subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus? = null,
        persist: Boolean = false,
        expectedAttempt: Int? = null,
    ) {
        mutate(persist) { list ->
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
        if (state?.isTerminal == true) {
            scope.launch(Dispatchers.IO + NonCancellable) {
                store.save(_jobs.value)
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
