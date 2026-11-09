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
import com.mvrk.vrka.ContentVerification
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.UriLookupResult
import com.mvrk.vrka.ProviderObjectStatus
import com.mvrk.vrka.storage.CleanupStore
import com.mvrk.vrka.storage.JobPersistence
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.StorageReadiness
import com.mvrk.vrka.storage.StorageRuntime
import com.mvrk.vrka.storage.StorageStatus
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
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

sealed interface EnqueueResult {
    data class Accepted(val jobId: String) : EnqueueResult
    data class Rejected(val reason: String, val message: String) : EnqueueResult
}

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
    customStore: JobPersistence? = null,
    customPublisher: OutputPublisher? = null,
    customRepository: RoomStorageRepository? = null,
    private val storageRuntime: StorageRuntime? = null,
    customStagingRoot: File? = null,
    customStagingCleanupStore: CleanupStore? = null,
    private val stagingDelete: (File) -> Boolean = { it.deleteRecursively() },
    autoRecoverOnInit: Boolean = true,
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
    private val repository = customRepository
    private val store = customStore ?: repository?.jobPersistence ?: JobStore(context)
    /** Set when a user-initiated transfer job is pending or has already started this process. */
    private val transferJobWasScheduled =
        DownloadTransferRuntime.wasTransferJobInvoked || DownloadTransferScheduler.isScheduled(context)
    internal val publisher = customPublisher ?: OutputPublisher(
        context = context,
        settingsRepository = settingsRepository,
        journal = repository?.publicationStore,
    )
    private val stagingRoot: File by lazy { customStagingRoot ?: File(context.getExternalFilesDir(null), "staging") }
    private val stagingCleanupStore: CleanupStore = customStagingCleanupStore
        ?: repository?.cleanupStore
        ?: StagingCleanupStore(File(context.filesDir, "staging_cleanup.json"))
    internal val storageStatus: StateFlow<StorageStatus> = storageRuntime?.status
        ?: MutableStateFlow(StorageStatus(StorageReadiness.READY)).asStateFlow()

    internal suspend fun awaitStorageReady(): StorageStatus =
        storageRuntime?.awaitReady() ?: StorageStatus(StorageReadiness.READY)

    internal fun historyPager(): Pager<Int, DownloadJob>? {
        val repo = repository ?: return null
        return Pager(
            config = PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false),
            pagingSourceFactory = { repo.historyPagingSource() },
        )
    }

    internal suspend fun allTerminalJobIds(): List<String> =
        repository?.terminalJobIds() ?: _jobs.value.filter { it.state.isTerminal }.map { it.id }

    internal suspend fun terminalJobCount(): Int =
        repository?.terminalCount() ?: _jobs.value.count { it.state.isTerminal }

    private suspend fun refreshCacheFromRepository() {
        val repo = repository ?: return
        repo.loadCache()
        synchronized(_jobs) {
            _jobs.value = repo.cachedJobs().sortedByDescending(DownloadJob::createdAt)
        }
    }
    /** Serializes snapshot selection, retention, write, and durable verification. */
    private val persistenceLock = Any()
    private val speedPattern = Regex("""\bat\s+([^\s]+/s)""", RegexOption.IGNORE_CASE)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(
        if (repository != null) {
            repository.cachedJobs().sortedByDescending(DownloadJob::createdAt)
        } else {
            runCatching {
                store.load(recoverActive = transferJobWasScheduled)
                    .sortedByDescending(DownloadJob::createdAt)
            }.getOrDefault(emptyList())
        }
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
            if (storageRuntime != null) {
                val status = storageRuntime.awaitReady()
                if (status.readiness == StorageReadiness.READY) {
                    repository?.loadCache()
                    synchronized(_jobs) {
                        _jobs.value = repository?.cachedJobs()
                            ?.sortedByDescending(DownloadJob::createdAt)
                            .orEmpty()
                    }
                    if (autoRecoverOnInit) {
                        reconcilePublicationJournalOnStartup()
                        resumeInterruptedWorkIfNeeded()
                        reportStoreLoadStatus(store.lastLoadResult)
                    }
                } else {
                    reportStoreIssue(
                        stage = "Storage initialization",
                        summary = "Storage is unavailable",
                        detail = status.message.ifBlank { status.readiness.name },
                    )
                }
            } else if (autoRecoverOnInit) {
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
            }
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
            PublicationJournalLoadState.READ_FAILED,
            PublicationJournalLoadState.UNSUPPORTED_SCHEMA -> {
                reportStoreIssue(
                    stage = "Publication journal recovery",
                    summary = "Publication journal cannot be interpreted safely",
                    detail = loadResult.detail,
                )
                return
            }
            PublicationJournalLoadState.CORRUPT -> {
                if (!loadResult.isolated || loadResult.entries.isEmpty()) {
                    reportStoreIssue(
                        stage = "Publication journal recovery",
                        summary = "Publication journal is corrupted and cannot be isolated",
                        detail = loadResult.detail,
                    )
                    return
                }
                reportStoreIssue(
                    stage = "Publication journal recovery",
                    summary = "Publication journal quarantined invalid records",
                    detail = loadResult.detail,
                )
            }
            PublicationJournalLoadState.MISSING,
            PublicationJournalLoadState.EMPTY -> {
                handleLegacyInFlightJobs()
                retryPendingStagingCleanup()
                return
            }
            PublicationJournalLoadState.OK -> Unit
        }

        val unreconciled = loadResult.entries.filter { it.status != PublicationTransactionStatus.RECONCILED }
        if (unreconciled.isEmpty()) {
            handleLegacyInFlightJobs()
            retryPendingStagingCleanup()
            return
        }

        val byJob = unreconciled.groupBy { it.jobId }
        val jobsToUpdate = mutableMapOf<String, DownloadJob>()
        val plans = mutableMapOf<String, JobRecoveryPlan>()

        for ((jobId, entries) in byJob) {
            val existingJob = _jobs.value.firstOrNull { it.id == jobId }
            val plan = planJobRecovery(jobId, existingJob, entries)
            if (plan.updatedJob != null) jobsToUpdate[jobId] = plan.updatedJob
            plans[jobId] = plan
        }

        if (jobsToUpdate.isNotEmpty()) {
            mutate(persist = false) { currentList ->
                val updatedMap = jobsToUpdate
                val existingIds = currentList.map { it.id }.toSet()
                val updatedList = currentList.map { job -> updatedMap[job.id] ?: job }
                val missingJobs = updatedMap.values.filterNot { it.id in existingIds }
                (updatedList + missingJobs).sortedByDescending(DownloadJob::createdAt)
            }
        }

        // Every unresolved job is pinned so the 250-record history cap cannot evict a job
        // whose journal transaction still requires reconciliation.
        val persistSuccess = jobsToUpdate.isEmpty() || persistJobsNow(byJob.keys)
        if (!persistSuccess) {
            reportStoreIssue(
                stage = "Publication journal reconciliation",
                summary = "Failed to persist reconciled jobs to JobStore; retaining journal and staging",
                detail = "JobStore save or durable verification failed during startup reconciliation",
            )
            handleLegacyInFlightJobs()
            return
        }

        for ((jobId, plan) in plans) {
            var reconciliationSucceeded = true
            if (plan.reconciledTransactions.isNotEmpty()) {
                reconciliationSucceeded = runCatching {
                    publisher.activeJournal.markReconciled(plan.reconciledTransactions)
                    true
                }.getOrDefault(false)
                if (!reconciliationSucceeded) {
                    reportStoreIssue(
                        stage = "Publication journal reconciliation",
                        summary = "Could not retire reconciled publication transactions",
                        detail = "Journal write failed for job $jobId; staging retained for a later retry.",
                    )
                }
            }
            if (reconciliationSucceeded) {
                cleanupStagingWhenSafe(jobId)
            }
        }

        handleLegacyInFlightJobs()
        retryPendingStagingCleanup()
    }

    private data class JobRecoveryPlan(
        val updatedJob: DownloadJob?,
        val reconciledTransactions: Set<String>,
        val unresolvedTransactions: Set<String>,
    )

    /**
     * The newest transaction per output index belongs to the current attempt; older
     * transactions for the same job are leftovers from previous attempts and are cleaned
     * or reconciled independently without affecting the current batch's verdict.
     */
    private fun planJobRecovery(
        jobId: String,
        existingJob: DownloadJob?,
        entries: List<PublicationEntry>,
    ): JobRecoveryPlan {
        val newestPerIndex = entries.groupBy { it.outputIndex }.mapValues { (_, group) ->
            group.maxWithOrNull(
                compareBy<PublicationEntry> { it.createdAt }
                    .thenBy { it.updatedAt }
                    .thenBy { it.transactionId },
            )!!
        }
        val historical: List<PublicationEntry>
        val current: List<PublicationEntry>
        if (existingJob != null && existingJob.state == JobState.DONE) {
            // The durable DONE record names the transactions that actually produced its
            // outputs. Journal entries outside those URIs are leftovers from earlier
            // attempts and must not demote a successfully recorded publication.
            val doneUris = existingJob.outputUris.toSet()
            val matching = entries.filter { it.providerUri != null && it.providerUri in doneUris }
                .sortedBy { it.outputIndex }
            val matchingIds = matching.mapTo(HashSet()) { it.transactionId }
            current = matching
            historical = entries.filter { it.transactionId !in matchingIds }
        } else {
            val currentIds = newestPerIndex.values.mapTo(HashSet()) { it.transactionId }
            current = newestPerIndex.values.sortedBy { it.outputIndex }
            historical = entries.filter { it.transactionId !in currentIds }
        }

        val reconciled = mutableSetOf<String>()
        val unresolved = mutableSetOf<String>()
        val completed = linkedMapOf<String, PublicationEntry>()
        var missingCurrentOutput = false

        for (entry in historical) {
            if (resolveHistoricalEntry(entry)) {
                reconciled += entry.transactionId
            } else {
                unresolved += entry.transactionId
            }
        }

        for (original in current) {
            var entry = original
            if (entry.providerUri == null) {
                when (val lookup = lookupPendingObject(entry)) {
                    is UriLookupResult.Found -> {
                        val adopted = adoptDiscoveredEntry(entry, lookup.uri.toString())
                        if (adopted == null) {
                            unresolved += entry.transactionId
                            continue
                        }
                        entry = adopted
                    }
                    UriLookupResult.DefinitelyAbsent -> {
                        if (entry.publicationPhase == PublicationPhase.FINALIZED ||
                            entry.publicationPhase == PublicationPhase.RECORDED
                        ) {
                            missingCurrentOutput = true
                        } else {
                            runCatching { publisher.activeJournal.markInterrupted(entry.transactionId) }
                        }
                        reconciled += entry.transactionId
                        continue
                    }
                    is UriLookupResult.Unknown -> {
                        unresolved += entry.transactionId
                        continue
                    }
                }
            }

            var inspection = publisher.inspectOutput(entry)
            if (entry.providerType == PublicationProviderType.SAF &&
                (inspection.status == ProviderObjectStatus.ABSENT ||
                    inspection.status == ProviderObjectStatus.UNKNOWN)
            ) {
                when (val rediscovered = recoverStaleSafUri(entry)) {
                    is RecoveredSafUri.Adopted -> {
                        entry = rediscovered.entry
                        inspection = publisher.inspectOutput(entry)
                    }
                    RecoveredSafUri.Unknown,
                    RecoveredSafUri.Unresolved,
                    -> {
                        unresolved += entry.transactionId
                        continue
                    }
                    RecoveredSafUri.NotApplicable -> Unit
                }
            }
            when (inspection.status) {
                ProviderObjectStatus.VERIFIED -> {
                        val providerUri = entry.providerUri
                        val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                        val unique = entry.providerType != PublicationProviderType.SAF ||
                            tree == null ||
                            publisher.safFinalIdentityIsUnique(entry, Uri.parse(providerUri!!))
                        val content = if (providerUri == null) ContentVerification.UNKNOWN else {
                            publisher.verifyProviderContent(entry, Uri.parse(providerUri))
                        }
                        when {
                            !unique || content == ContentVerification.UNKNOWN -> {
                                unresolved += entry.transactionId
                            }
                            content == ContentVerification.MISMATCH -> {
                                unresolved += entry.transactionId
                                missingCurrentOutput = true
                            }
                            else -> {
                                val finalized = if (entry.publicationPhase == PublicationPhase.FINALIZED ||
                                    entry.publicationPhase == PublicationPhase.RECORDED
                                ) {
                                    entry
                                } else {
                                    persistFinalizedEntry(entry, entry.providerUri!!)
                                }
                                if (finalized == null) {
                                    unresolved += entry.transactionId
                                } else {
                                    completed[entry.transactionId] = finalized
                                    reconciled += entry.transactionId
                                }
                            }
                        }
                    }
                    ProviderObjectStatus.PENDING -> {
                        val providerSize = runCatching {
                            publisher.getFileSize(entry.providerUri!!)
                        }.getOrDefault(-1L)
                        val copyComplete = if (providerSize >= 0L) {
                            providerSize == entry.expectedSizeBytes
                        } else {
                            entry.copiedBytes == entry.expectedSizeBytes
                        }
                        if (copyComplete) {
                            val content = publisher.verifyProviderContent(entry, Uri.parse(entry.providerUri!!))
                            if (content == ContentVerification.UNKNOWN) {
                                unresolved += entry.transactionId
                                continue
                            }
                            if (content == ContentVerification.MISMATCH) {
                                unresolved += entry.transactionId
                                missingCurrentOutput = true
                                continue
                            }
                            val finalization = publisher.finalizeEntry(entry)
                            when (finalization.inspection.status) {
                                ProviderObjectStatus.VERIFIED -> {
                                    val finalized = persistFinalizedEntry(entry, finalization.uri.toString())
                                    if (finalized == null) {
                                        unresolved += entry.transactionId
                                    } else {
                                        completed[entry.transactionId] = finalized
                                        reconciled += entry.transactionId
                                    }
                                }
                                ProviderObjectStatus.ABSENT -> {
                                    missingCurrentOutput = true
                                    reconciled += entry.transactionId
                                }
                                ProviderObjectStatus.PENDING,
                                ProviderObjectStatus.MISMATCH,
                                ProviderObjectStatus.UNKNOWN,
                                -> {
                                    // A fully copied object is never destroyed because a
                                    // transient finalization attempt failed; it stays
                                    // recoverable for the next startup.
                                    unresolved += entry.transactionId
                                }
                            }
                        } else if (providerSize < 0L) {
                            // The provider cannot report its size, so a lagging journal
                            // cannot prove that the copy is incomplete. Preserve it.
                            unresolved += entry.transactionId
                        } else if (cleanupIncompleteEntry(entry)) {
                            reconciled += entry.transactionId
                        } else {
                            unresolved += entry.transactionId
                        }
                    }
                    ProviderObjectStatus.ABSENT -> {
                        if (entry.publicationPhase == PublicationPhase.FINALIZED ||
                            entry.publicationPhase == PublicationPhase.RECORDED
                        ) {
                            missingCurrentOutput = true
                        } else {
                            runCatching { publisher.activeJournal.markInterrupted(entry.transactionId) }
                        }
                        reconciled += entry.transactionId
                    }
                    ProviderObjectStatus.MISMATCH,
                    ProviderObjectStatus.UNKNOWN,
                    -> unresolved += entry.transactionId
                }
        }

        val totalOutputs = current.firstOrNull()?.totalOutputs ?: 1
        val allCurrentComplete = completed.size == current.size &&
            current.size == totalOutputs &&
            current.map { it.outputIndex }.toSet() == (0 until totalOutputs).toSet() &&
            !missingCurrentOutput
        val completedEntries = current.mapNotNull { completed[it.transactionId] }

        if (existingJob != null && existingJob.state == JobState.DONE) {
            if (current.isEmpty()) {
                return JobRecoveryPlan(null, reconciled, unresolved)
            }
            val allMatchedVerified = completed.size == current.size && !missingCurrentOutput
            if (allMatchedVerified && unresolved.isEmpty()) {
                return JobRecoveryPlan(null, reconciled, unresolved)
            }
            if (missingCurrentOutput || (!allMatchedVerified && unresolved.isEmpty())) {
                val demoted = existingJob.copy(
                    state = JobState.FAILED,
                    error = INTERRUPTED_DOWNLOAD_ERROR,
                    detail = "Published output verification failed",
                    outputUris = completedEntries.map { it.providerUri!! },
                    updatedAt = System.currentTimeMillis(),
                )
                return JobRecoveryPlan(demoted, reconciled, unresolved)
            }
            return JobRecoveryPlan(null, reconciled, unresolved)
        }

        val base = existingJob ?: DownloadJob(
            id = jobId,
            request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            title = current.firstOrNull()?.intendedFilename ?: "Recovered Media",
            publishStarted = true,
            createdAt = entries.minOfOrNull { it.createdAt } ?: System.currentTimeMillis(),
        )
        val totalBytes = completedEntries.sumOf { it.copiedBytes }
        val uris = completedEntries.map { it.providerUri!! }
        val updated = if (allCurrentComplete) {
            val isOpus = base.request.mode == MediaMode.AUDIO && base.request.audioFormat == AudioFormat.OPUS
            val detail = if (isOpus) {
                "Saved (native Opus stream copy)"
            } else if (uris.size == 1) {
                "Saved to Downloads/VRKA"
            } else {
                "Saved ${uris.size} files"
            }
            base.copy(
                state = JobState.DONE,
                progress = 100f,
                detail = detail,
                downloadedBytes = totalBytes,
                reportedTotalBytes = totalBytes,
                reportedTotalIsExact = true,
                actualFinalFileBytes = totalBytes,
                outputUris = uris,
                error = "",
                speed = "",
                etaSeconds = null,
                publishStarted = true,
                updatedAt = System.currentTimeMillis(),
            )
        } else {
            val detail = when {
                unresolved.isNotEmpty() -> "Interrupted during publication (recovery pending)"
                uris.isNotEmpty() -> "Interrupted during publication (${uris.size} of $totalOutputs files published)"
                else -> "Interrupted during publication"
            }
            base.copy(
                state = JobState.FAILED,
                error = INTERRUPTED_DOWNLOAD_ERROR,
                detail = detail,
                outputUris = uris,
                downloadedBytes = totalBytes,
                reportedTotalBytes = totalBytes,
                actualFinalFileBytes = totalBytes,
                speed = "",
                etaSeconds = null,
                publishStarted = true,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return JobRecoveryPlan(updated, reconciled, unresolved)
    }

    private sealed interface RecoveredSafUri {
        data class Adopted(val entry: PublicationEntry) : RecoveredSafUri
        data object NotApplicable : RecoveredSafUri
        data object Unknown : RecoveredSafUri
        data object Unresolved : RecoveredSafUri
    }

    /**
     * `renameDocument()` may return a replacement URI and invalidate the old one. When the
     * journaled URI no longer resolves, rediscover exactly one document carrying this
     * transaction's final-name marker, verify its content against staging, and continue.
     */
    private fun recoverStaleSafUri(entry: PublicationEntry): RecoveredSafUri {
        if (entry.providerType != PublicationProviderType.SAF) return RecoveredSafUri.NotApplicable
        val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
            ?: return RecoveredSafUri.Unresolved
        return when (val found = publisher.findSafUriByName(tree, entry.intendedFilename)) {
            is UriLookupResult.Found -> {
                when (publisher.verifyProviderContent(entry, found.uri)) {
                    ContentVerification.HASH_VERIFIED,
                    ContentVerification.SIZE_VERIFIED,
                    -> {
                        val adopted = adoptDiscoveredEntry(entry, found.uri.toString())
                        if (adopted == null) RecoveredSafUri.Unresolved else RecoveredSafUri.Adopted(adopted)
                    }
                    ContentVerification.MISMATCH -> RecoveredSafUri.Unresolved
                    ContentVerification.UNKNOWN -> RecoveredSafUri.Unknown
                }
            }
            UriLookupResult.DefinitelyAbsent -> RecoveredSafUri.NotApplicable
            is UriLookupResult.Unknown -> RecoveredSafUri.Unknown
        }
    }

    private fun resolveHistoricalEntry(entry: PublicationEntry): Boolean {
        var working = entry
        if (working.providerUri == null) {
            when (val lookup = lookupPendingObject(working)) {
                is UriLookupResult.Found -> {
                    working = adoptDiscoveredEntry(working, lookup.uri.toString()) ?: return false
                }
                UriLookupResult.DefinitelyAbsent -> {
                    runCatching { publisher.activeJournal.markInterrupted(entry.transactionId) }
                    return true
                }
                is UriLookupResult.Unknown -> return false
            }
        }
        var inspection = publisher.inspectOutput(working)
        if (working.providerUri != null &&
            (inspection.status == ProviderObjectStatus.ABSENT ||
                inspection.status == ProviderObjectStatus.UNKNOWN)
        ) {
            when (val rediscovered = recoverStaleSafUri(working)) {
                is RecoveredSafUri.Adopted -> {
                    working = rediscovered.entry
                    inspection = publisher.inspectOutput(working)
                }
                RecoveredSafUri.Unknown,
                RecoveredSafUri.Unresolved,
                -> return false
                RecoveredSafUri.NotApplicable -> Unit
            }
        }
        return when (inspection.status) {
            ProviderObjectStatus.VERIFIED -> {
                val providerUri = working.providerUri
                    ?: return false
                val unique = working.providerType != PublicationProviderType.SAF ||
                    publisher.safFinalIdentityIsUnique(working, Uri.parse(providerUri))
                val content = publisher.verifyProviderContent(working, Uri.parse(providerUri))
                if (!unique || content == ContentVerification.UNKNOWN) return false
                if (content == ContentVerification.MISMATCH) return false
                val finalized = if (working.publicationPhase == PublicationPhase.FINALIZED ||
                    working.publicationPhase == PublicationPhase.RECORDED
                ) {
                    working
                } else {
                    persistFinalizedEntry(working, providerUri)
                }
                // A previous attempt's finalized file is preserved; only its journal
                // transaction is retired. No unrelated or user-visible file is deleted.
                finalized != null
            }
            ProviderObjectStatus.PENDING -> cleanupIncompleteEntry(working)
            ProviderObjectStatus.ABSENT -> {
                runCatching { publisher.activeJournal.markInterrupted(entry.transactionId) }
                true
            }
            ProviderObjectStatus.MISMATCH,
            ProviderObjectStatus.UNKNOWN,
            -> false
        }
    }

    private fun lookupPendingObject(entry: PublicationEntry): UriLookupResult =
        when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE -> publisher.findPendingMediaStoreUri(entry.pendingFilename)
            PublicationProviderType.SAF -> {
                val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                    ?: return UriLookupResult.Unknown("Invalid SAF destination identity")
                when (val pending = publisher.findPendingSafUri(tree, entry.pendingFilename)) {
                    is UriLookupResult.Found -> pending
                    is UriLookupResult.Unknown -> pending
                    UriLookupResult.DefinitelyAbsent -> publisher.findSafUriByName(tree, entry.intendedFilename)
                }
            }
        }

    private fun adoptDiscoveredEntry(entry: PublicationEntry, providerUri: String): PublicationEntry? =
        runCatching {
            publisher.activeJournal.updateProviderUri(entry.transactionId, providerUri)
            entry.copy(
                providerUri = providerUri,
                publicationPhase = if (entry.publicationPhase == PublicationPhase.INTENT_RECORDED) {
                    PublicationPhase.PROVIDER_OBJECT_CREATED
                } else {
                    entry.publicationPhase
                },
            )
        }.getOrNull()

    private fun persistFinalizedEntry(entry: PublicationEntry, providerUri: String): PublicationEntry? =
        runCatching {
            publisher.activeJournal.updateFinalized(entry.transactionId, providerUri)
            entry.copy(
                providerUri = providerUri,
                publicationPhase = PublicationPhase.FINALIZED,
                status = PublicationTransactionStatus.COMPLETED,
            )
        }.getOrNull()

    private fun cleanupIncompleteEntry(entry: PublicationEntry): Boolean =
        when (publisher.cleanupPendingObject(entry)) {
            CleanupOutcome.DELETED,
            CleanupOutcome.ALREADY_ABSENT,
            -> runCatching {
                publisher.activeJournal.updateDeleted(entry.transactionId)
                true
            }.getOrDefault(false)
            CleanupOutcome.RETRY_REQUIRED -> {
                runCatching {
                    publisher.activeJournal.markCleanupPending(
                        entry.transactionId,
                        "Provider cleanup could not be completed safely; retrying on a later startup",
                    )
                }
                reportStoreIssue(
                    stage = "Publication cleanup",
                    summary = "Publication cleanup requires a later retry",
                    detail = "Transaction ${entry.transactionId} (${entry.providerUri ?: "no provider object"}) was not deleted because ownership or provider availability could not be verified.",
                )
                false
            }
        }

    private fun journalAllowsStagingCleanup(jobId: String): Boolean = runCatching {
        val load = publisher.activeJournal.loadWithStatus()
        if (load.state != PublicationJournalLoadState.OK &&
            load.state != PublicationJournalLoadState.MISSING &&
            load.state != PublicationJournalLoadState.EMPTY
        ) {
            // A journal that cannot be interpreted must not authorize deleting recovery data.
            return false
        }
        load.entries.filter { it.jobId == jobId }.all {
            it.status == PublicationTransactionStatus.RECONCILED ||
                it.status == PublicationTransactionStatus.CLEANUP_PENDING
        }
    }.getOrDefault(false)

    /**
     * Deletes staging only when no unresolved transaction still requires its job's data.
     * Otherwise the cleanup is durably queued for a later startup.
     */
    private fun cleanupStagingWhenSafe(jobId: String) {
        if (journalAllowsStagingCleanup(jobId)) {
            cleanupStaging(jobId)
        } else {
            runCatching { stagingCleanupStore.markPending(jobId) }
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

    fun enqueue(request: DownloadRequest, estimate: SizeEstimate? = null): EnqueueResult {
        if (!isHttpUrl(request.url)) {
            return EnqueueResult.Rejected("invalid_url", "Enter a valid http or https URL.")
        }
        if (repository != null && storageStatus.value.readiness != StorageReadiness.READY) {
            return EnqueueResult.Rejected(
                "storage_not_ready",
                "Storage is still initializing. Try again in a moment.",
            )
        }
        val settings = settingsRepository.settings.value
        val selectedTemplate = if (settings.customCommandMode) {
            settings.customCommandTemplates.firstOrNull { it.id == settings.selectedCustomCommandTemplateId }
                ?: return EnqueueResult.Rejected(
                    "template_missing",
                    "Select a valid yt-dlp command template in Settings.",
                )
        } else null
        val activeJobs = _jobs.value.count { !it.state.isTerminal }
        if (activeJobs >= MAX_ADMITTED_JOBS) {
            return EnqueueResult.Rejected(
                "queue_capacity",
                "The queue is full (${MAX_ADMITTED_JOBS} active or queued jobs). Finish or remove a job first.",
            )
        }
        if (runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE) < MIN_FREE_BYTES) {
            return EnqueueResult.Rejected(
                "storage_full",
                "There is not enough free storage to safely add another download.",
            )
        }
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
        mutate(persist = false) { listOf(job) + it }
        if (!persistJobsNow(setOf(job.id))) {
            mutate(persist = false) { jobs -> jobs.filterNot { it.id == id } }
            return EnqueueResult.Rejected(
                "storage_write_failed",
                "The download could not be durably saved. No job was added.",
            )
        }
        admitToQueue(id)
        return EnqueueResult.Accepted(id)
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
            cleanupStagingWhenSafe(jobId)
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
            if (repository != null) {
                repository.deleteJobs(listOf(jobId))
                refreshCacheFromRepository()
            } else {
                mutate(persist = true) { jobs -> jobs.filterNot { it.id == jobId } }
            }
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
        val recordDeleted = if (repository != null) {
            val ok = repository.deleteJobs(listOf(jobId))
            refreshCacheFromRepository()
            ok
        } else {
            mutate(persist = true) { jobs -> jobs.filterNot { it.id == jobId } }
            true
        }
        return HistoryDeletionResult(
            deletedRecordsCount = if (recordDeleted) 1 else 0,
            deletedFilesCount = deletedFiles,
            failedFilesCount = failedFiles,
        )
    }

    fun deleteHistoryRecords(jobIds: Collection<String>) {
        val targetIds = jobIds.toSet()
        scope.launch(Dispatchers.IO) {
            targetIds.forEach { diagnosticStore.clearUrlsForJob(it) }
            if (repository != null) {
                repository.deleteJobs(targetIds)
                refreshCacheFromRepository()
            } else {
                mutate(persist = true) { jobs ->
                    jobs.filterNot { it.id in targetIds && it.state.isTerminal }
                }
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
        val recordsDeleted = if (repository != null) {
            val ok = repository.deleteJobs(targetIds)
            refreshCacheFromRepository()
            if (ok) targetJobs.size else 0
        } else {
            mutate(persist = true) { jobs ->
                jobs.filterNot { it.id in targetIds && it.state.isTerminal }
            }
            targetJobs.size
        }
        return HistoryDeletionResult(
            deletedRecordsCount = recordsDeleted,
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
        val recordsDeleted = if (repository != null) {
            val ok = repository.deleteJobs(targetJobs.map { it.id })
            refreshCacheFromRepository()
            if (ok) targetJobs.size else 0
        } else {
            mutate(persist = true) { jobs ->
                jobs.filterNot { it.state.isTerminal }
            }
            targetJobs.size
        }
        return HistoryDeletionResult(
            deletedRecordsCount = recordsDeleted,
            deletedFilesCount = deletedFiles,
            failedFilesCount = failedFiles,
        )
    }

    fun deleteJob(jobId: String) {
        deleteHistoryRecord(jobId)
    }

    fun clearFinished() {
        scope.launch(Dispatchers.IO) {
            val terminal = _jobs.value.filter { it.state.isTerminal }
            terminal.forEach { diagnosticStore.clearUrlsForJob(it.id) }
            if (repository != null) {
                repository.deleteJobs(terminal.map { it.id })
                refreshCacheFromRepository()
            } else {
                mutate(persist = true) { list -> list.filterNot { it.state.isTerminal } }
            }
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

    /**
     * Single authoritative persistence path. The snapshot is selected inside
     * [persistenceLock], after every earlier writer completed, so a stale snapshot can never
     * replace a newer terminal snapshot. The store re-reads the file it just replaced and
     * reports success only when the exact job states are durable.
     */
    private fun persistJobsNow(extraPinnedJobIds: Set<String> = emptySet()): Boolean {
        val started = SystemClock.elapsedRealtime()
        val snapshotSize: Int
        val result = synchronized(persistenceLock) {
            val pinned = extraPinnedJobIds + pinnedJobIdsFromJournal()
            val snapshot = synchronized(_jobs) { _jobs.value }
            snapshotSize = snapshot.size
            store.save(snapshot, pinned)
        }
        Log.d(
            "VRKA",
            "JobStore save success=${result.success} jobs=$snapshotSize pinned=${extraPinnedJobIds.size} elapsedMs=${SystemClock.elapsedRealtime() - started}",
        )
        if (!result.success) {
            reportStoreIssue(
                stage = "Queue/history persistence",
                summary = "Could not save queue and history",
                detail = result.error.ifBlank { "Unknown storage error" },
            )
        } else {
            store.cachedJobs()?.let { persisted ->
                synchronized(_jobs) {
                    _jobs.value = persisted.sortedByDescending(DownloadJob::createdAt)
                }
            }
        }
        return result.success
    }

    /**
     * Jobs referenced by unresolved publication transactions are never evicted by the
     * history cap. If the journal itself cannot be read, every job that entered publication
     * is pinned: the manager cannot prove which history records are still needed.
     */
    private fun pinnedJobIdsFromJournal(): Set<String> {
        val load = runCatching { publisher.activeJournal.loadWithStatus() }.getOrNull()
        if (load == null ||
            load.state == PublicationJournalLoadState.READ_FAILED ||
            load.state == PublicationJournalLoadState.UNSUPPORTED_SCHEMA ||
            load.state == PublicationJournalLoadState.CORRUPT
        ) {
            return _jobs.value.filter { it.publishStarted }.mapTo(HashSet()) { it.id }
        }
        return load.entries
            .filter { it.status != PublicationTransactionStatus.RECONCILED }
            .mapTo(HashSet()) { it.jobId }
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
            cleanupStagingWhenSafe(jobId)
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
        val publishedTransactions = mutableListOf<String>()
        val publishedMedia = mutableListOf<PublishedOutput>()
        var publishedBatchId = ""
        val planned = outputs.mapIndexed { index, file ->
            file to preferredOutputName(job, file, index, outputs.size)
        }
        val publishedOutputs = publisher.publishAll(
            files = planned,
            destinationTreeUri = job.request.destinationTreeUri,
            jobId = job.id,
            attemptNumber = job.attempt,
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
            publishedTransactions += output.transactionId
            if (publishedBatchId.isBlank()) publishedBatchId = output.batchId
            if (DownloadAccounting.isMediaExtension(output.extension)) {
                publishedMedia += output
            }
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
            transactionIds = publishedTransactions.toSet(),
            subtitleStatus = jobSubStatus,
            attemptNumber = job.attempt,
            batchId = publishedBatchId,
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
                    val publishedTransactions = mutableListOf<String>()
                    var publishedBytes: Long? = null
                    var publishedBatchId = ""
                    val planned = outputs.mapIndexed { index, file ->
                        file to preferredOutputName(job, file, index, outputs.size)
                    }
                    val publishedOutputs = publisher.publishAll(
                        files = planned,
                        destinationTreeUri = job.request.destinationTreeUri,
                        jobId = job.id,
                        attemptNumber = job.attempt,
                        shouldContinue = { !isCancelled(job.id) },
                    )
                    publishedOutputs.forEach { output ->
                        published += output.uri.toString()
                        publishedTransactions += output.transactionId
                        if (publishedBatchId.isBlank()) publishedBatchId = output.batchId
                        publishedBytes = (publishedBytes ?: 0L) + output.bytes
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
                        transactionIds = publishedTransactions.toSet(),
                        attemptNumber = job.attempt,
                        batchId = publishedBatchId,
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

    private fun cleanupStaging(jobId: String): Boolean {
        val child = File(stagingRoot, jobId)
        val rootPath = stagingRoot.absoluteFile.toPath().normalize()
        val childPath = child.absoluteFile.toPath().normalize()
        if (!childPath.startsWith(rootPath) || childPath == rootPath) return true
        if (!child.exists()) {
            runCatching { stagingCleanupStore.clear(jobId) }
            return true
        }
        val deleted = runCatching { stagingDelete(child) }.getOrDefault(false)
        if (deleted && !child.exists()) {
            runCatching { stagingCleanupStore.clear(jobId) }
            return true
        }
        runCatching { stagingCleanupStore.markPending(jobId) }
        reportStoreIssue(
            stage = "Staging cleanup",
            summary = "Could not delete download staging data",
            detail = "Staging directory for job $jobId will be retried on the next startup.",
        )
        return false
    }

    /**
     * Retries staging deletion only when no unresolved publication transaction still refers
     * to the job. Staging must never be removed while it can still serve recovery.
     */
    internal fun retryPendingStagingCleanup() {
        val pending = runCatching { stagingCleanupStore.pending() }.getOrDefault(emptyList())
        for (entry in pending) {
            val job = _jobs.value.firstOrNull { it.id == entry.jobId }
            if (job != null && !job.state.isTerminal) continue
            if (journalAllowsStagingCleanup(entry.jobId)) {
                cleanupStaging(entry.jobId)
            }
        }
    }

    /**
     * Executes the durable terminal completion protocol for published outputs:
     * 1. Confirms provider outputs are finalized (validated by the publisher beforehand).
     * 2. Constructs the complete terminal JobStore state.
     * 3. Persists the terminal state with the current completion job pinned through retention.
     * 4. Re-reads the durable store and verifies the exact terminal record, not just memory.
     * 5. Only then retires exactly the publication transactions produced by this attempt.
     * 6. Only after verified persistence performs staging cleanup and posts the notification.
     *
     * Returns true only when the terminal state is durably present. A false result leaves the
     * journal, provider outputs, and staging intact for restart recovery.
     */
    internal suspend fun commitTerminalPublishedJob(
        jobId: String,
        detail: String,
        downloadedBytes: Long?,
        reportedTotalBytes: Long?,
        reportedTotalIsExact: Boolean,
        actualFinalFileBytes: Long?,
        outputUris: List<String>,
        transactionIds: Set<String> = emptySet(),
        subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus? = null,
        attemptNumber: Int? = null,
        batchId: String = "",
    ): Boolean {
        if (repository != null) {
            val currentJob = current(jobId)
            if (currentJob == null) {
                Log.w("VRKA", "Job $jobId was not found for terminal commit.")
                return false
            }
            val attempt = attemptNumber ?: currentJob.attempt
            val committed = withContext(Dispatchers.IO + NonCancellable) {
                repository.commitTerminalPublishedJob(
                    jobId = jobId,
                    attemptNumber = attempt,
                    batchId = batchId,
                    outputUris = outputUris,
                    detail = detail,
                    downloadedBytes = downloadedBytes,
                    reportedTotalBytes = reportedTotalBytes,
                    reportedTotalIsExact = reportedTotalIsExact,
                    actualFinalFileBytes = actualFinalFileBytes,
                    subtitleStatus = subtitleStatus,
                )
            }
            if (!committed) {
                reportStoreIssue(
                    stage = "Queue/history persistence",
                    summary = "Atomic terminal publication commit failed",
                    detail = "Job $jobId was not committed as DONE with its exact output set; journal and staging remain recoverable.",
                )
                repository.loadCache()
                synchronized(_jobs) {
                    _jobs.value = repository.cachedJobs().sortedByDescending(DownloadJob::createdAt)
                }
                return false
            }
            repository.loadCache()
            synchronized(_jobs) {
                _jobs.value = repository.cachedJobs().sortedByDescending(DownloadJob::createdAt)
            }
            runCatching { diagnosticStore.clearUrlsForJob(jobId) }
            runCatching { current(jobId)?.let { DownloadNotifications.notifyCompleted(context, it) } }
            cleanupStaging(jobId)
            return true
        }
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

        // Step 2 & 3: Checked, awaited terminal persistence. Cancellation cannot interrupt it.
        val persistSuccess = withContext(Dispatchers.IO + NonCancellable) {
            persistJobsNow(setOf(jobId))
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

        // Step 4: Verify the durable record by re-reading the actual store file.
        val durableJob = withContext(Dispatchers.IO + NonCancellable) {
            runCatching { store.load(recoverActive = true).firstOrNull { it.id == jobId } }.getOrNull()
        }
        if (durableJob?.state != JobState.DONE || durableJob.outputUris != outputUris) {
            reportStoreIssue(
                stage = "Queue/history persistence",
                summary = "Durable terminal state verification failed",
                detail = "Job $jobId is not present as DONE with its output URIs in the persisted store; retaining journal and staging.",
            )
            Log.e("VRKA", "Terminal job state verification failed for $jobId; retaining journal and staging data.")
            return false
        }

        // Step 5: Retire exactly the transactions created by this attempt. Previous failed
        // attempts for the same logical job remain in the journal and are handled separately.
        val transactions = if (transactionIds.isNotEmpty()) {
            transactionIds
        } else {
            runCatching {
                publisher.activeJournal.entriesForJob(jobId)
                    .filter { it.status == PublicationTransactionStatus.COMPLETED }
                    .mapTo(HashSet()) { it.transactionId }
            }.getOrDefault(emptySet())
        }
        val reconciled = transactions.isEmpty() || runCatching {
            publisher.activeJournal.markReconciled(transactions)
            true
        }.getOrDefault(false)
        runCatching { diagnosticStore.clearUrlsForJob(jobId) }

        if (!reconciled) {
            reportStoreIssue(
                stage = "Publication journal reconciliation",
                summary = "Could not retire the completed publication transactions",
                detail = "Terminal state for job $jobId is durable; journal reconciliation is retried on the next startup.",
            )
            runCatching { stagingCleanupStore.markPending(jobId) }
            return true
        }

        // Step 6: Post completion notification and clean up staging only after durable
        // persistence and reconciliation. Cleanup failure becomes a durable retry record.
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
        private const val MAX_ADMITTED_JOBS = 1_000
        private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
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
