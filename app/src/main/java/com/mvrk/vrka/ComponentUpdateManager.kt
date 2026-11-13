package com.mvrk.vrka

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mvrk.vrka.worker.ComponentUpdateWorker
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class ComponentCheckState {
    CHECK_IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    CHECK_FAILED,
}

enum class ComponentUpdateState {
    UPDATE_IDLE,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    UPDATE_SUCCESS,
    UPDATE_FAILED,
}

enum class ComponentLifecycleState {
    UNKNOWN,
    CHECKING,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    STAGING,
    INSTALLING,
    VERIFYING_INSTALL,
    UP_TO_DATE,
    UPDATED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
}

enum class BatchOperationState {
    IDLE,
    CHECKING,
    UPDATING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ComponentUpdateItem(
    val id: String,
    val name: String,
    val currentVersion: String,
    val targetVersion: String,
)

data class StartupUpdateDialogData(
    val updates: List<ComponentUpdateItem>,
)

data class ComponentStatus(
    val id: String,
    val name: String,
    val installedVersion: String,
    val latestVersion: String? = null,
    val lifecycleState: ComponentLifecycleState = ComponentLifecycleState.UNKNOWN,
    val checkState: ComponentCheckState = ComponentCheckState.CHECK_IDLE,
    val updateState: ComponentUpdateState = ComponentUpdateState.UPDATE_IDLE,
    val isResolvingInstalledVersion: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val lastChecked: Long = 0L,
    val downloadUrl: String? = null,
    val expectedSha256: String? = null,
) {
    /**
     * Only a real discovery request counts as checking. Resolving the installed version is a
     * local read that runs alongside discovery, so it must never disable the Check All button
     * or present "Checking" after discovery already finished.
     */
    val isChecking: Boolean get() = checkState == ComponentCheckState.CHECKING
    val isUpdating: Boolean get() = updateState in ACTIVE_UPDATE_STATES
}

private val ACTIVE_UPDATE_STATES = setOf(
    ComponentUpdateState.DOWNLOADING,
    ComponentUpdateState.VERIFYING,
    ComponentUpdateState.INSTALLING,
)

/**
 * Installed-version resolution shared by both components. Artifact metadata is tried first,
 * then the active runtime record, then a bounded `--version` run. A cached or unknown value
 * must never win over a readable artifact, and "Unknown" is never a resolved version.
 */
internal object ComponentVersionPriority {
    fun sanitize(raw: String?): String? {
        val clean = raw?.removePrefix("yt-dlp ")?.trim().orEmpty()
        return clean.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
    }

    /**
     * Returns the first sanitized candidate. Candidates are evaluated in order and lazily:
     * reading the bundled zipapp copies a multi-megabyte resource, so it must only run when
     * the installed artifact could not answer.
     */
    fun resolve(vararg candidates: () -> String?): String? =
        candidates.firstNotNullOfOrNull { candidate ->
            sanitize(runCatching(candidate).getOrNull())
        }
}

class ComponentUpdateManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("vrka_components", Context.MODE_PRIVATE)

    private val checkGenerations = ConcurrentHashMap<String, Long>()
    private val updateGenerations = ConcurrentHashMap<String, Long>()
    private val globalCheckGeneration = AtomicLong(0L)
    private val activeCheckJobs = ConcurrentHashMap<String, Job>()
    private val activeUpdateJobs = ConcurrentHashMap<String, Job>()

    /**
     * Generation that last set CHECKING for a component. Only the owner may clear the
     * checking state, so a superseded request can never wipe a newer one's UI state.
     */
    private val checkOwners = ConcurrentHashMap<String, Long>()

    // Local check-request gate and caching
    private data class ReleaseCacheEntry(val tag: String, val timestamp: Long)
    private val releaseCache = ConcurrentHashMap<String, ReleaseCacheEntry>()
    private val lastCheckAllTimestamp = AtomicLong(0L)

    private val batchMutex = Mutex()
    private val _batchState = MutableStateFlow(BatchOperationState.IDLE)
    val batchState: StateFlow<BatchOperationState> = _batchState.asStateFlow()

    private val _startupUpdateDialogData = MutableStateFlow<StartupUpdateDialogData?>(null)
    val startupUpdateDialogData: StateFlow<StartupUpdateDialogData?> = _startupUpdateDialogData.asStateFlow()

    fun dismissStartupDialog() {
        _startupUpdateDialogData.value = null
    }

    @Volatile
    private var rateLimitCooldownUntil: Long = 0L
    @Volatile
    private var rateLimitErrorMessage: String? = null

    /**
     * The active runtime's recorded version, wired by [VrkaDownloadManager]. Null until wired,
     * so the manager still works with metadata only in isolated tests.
     */
    @Volatile
    internal var runtimeVersionProvider: (() -> String?)? = null

    /** A bounded `yt-dlp --version` probe of the active binary. Wired by [VrkaDownloadManager]. */
    @Volatile
    internal var boundedVersionProbe: (() -> String?)? = null

    internal fun getInstalledYtDlpVersion(): String {
        // Priority: artifact metadata, then the active runtime record, then a bounded
        // `--version` run. A first install has no cached value, so metadata and the probe are
        // what keep "Installed: Unavailable" from appearing next to an available update.
        val resolution = ComponentVersionPriority.resolve(
            { readInstalledZipAppVersion() },
            { InstalledComponentVersionReader.readBundledYtDlpVersion(context) },
            { runtimeVersionProvider?.invoke() },
            { boundedVersionProbe?.invoke() },
            { prefs.getString(KEY_YTDLP_VER, null) },
            { legacyLibraryVersion() },
        )
        if (resolution != null) {
            prefs.edit().putString(KEY_YTDLP_VER, resolution).apply()
            return resolution
        }
        return "Unknown"
    }

    /**
     * Cheap constructor-time read. The bundled zipapp copy, runtime query and `--version`
     * probe all belong on a background thread, so they are left to
     * [refreshInstalledVersions] and only the installed artifact plus the cache are read here.
     */
    private fun cachedInstalledYtDlpVersion(): String =
        ComponentVersionPriority.sanitize(readInstalledZipAppVersion())
            ?: ComponentVersionPriority.sanitize(prefs.getString(KEY_YTDLP_VER, null))
            ?: ""

    private fun readInstalledZipAppVersion(): String? = runCatching {
        val installedZipApp = java.io.File(SecureComponentUpdater.getYoutubeDLDir(context), "yt-dlp")
        InstalledComponentVersionReader.readYtDlpVersion(installedZipApp)
    }.getOrNull()

    private fun legacyLibraryVersion(): String? {
        val ytdlPrefs = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        return ytdlPrefs.getString("dlpVersionName", null)
            ?: ytdlPrefs.getString("dlpVersion", null)
    }


    private fun readXpiManifestVersion(file: java.io.File): String? {
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return@runCatching null
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                    val json = org.json.JSONObject(reader.readText())
                    json.optString("version").trim().takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }

    private suspend fun getInstalledUBlockVersion(): String {
        // The XPI manifest is the installed artifact's own metadata: it is readable on a
        // first install without starting GeckoView, which is what previously left the card
        // at "Installed: Unavailable".
        val xpiDir = java.io.File(context.filesDir, "extensions/xpis")
        val ublockXpi = java.io.File(xpiDir, "ublock.xpi")
        val installedFile = ComponentVersionPriority.sanitize(readXpiManifestVersion(ublockXpi))
        val bundled = if (installedFile == null) {
            ComponentVersionPriority.sanitize(
                runCatching {
                    InstalledComponentVersionReader.readBundledExtensionVersion(context, "ublock.xpi")
                }.getOrNull(),
            )
        } else {
            null
        }
        // Seed the working copy so later extension loads and updates use the same file.
        if ((installedFile ?: bundled) != null && (!ublockXpi.exists() || ublockXpi.length() == 0L)) {
            runCatching { GeckoRuntimeManager.getInstance(context) }.getOrNull()
                ?.seedBundledExtensionsIfMissing()
        }
        if (installedFile != null || bundled != null) {
            val resolved = installedFile ?: bundled!!
            prefs.edit().putString(KEY_UBLOCK_VER, resolved).apply()
            return resolved
        }
        val runtimeVer = ComponentVersionPriority.sanitize(
            runCatching { GeckoRuntimeManager.getInstance(context) }.getOrNull()
                ?.getInstalledExtensionVersion(GeckoRuntimeManager.UBLOCK_ID),
        )
        if (runtimeVer != null) {
            prefs.edit().putString(KEY_UBLOCK_VER, runtimeVer).apply()
            return runtimeVer
        }
        return ComponentVersionPriority.sanitize(prefs.getString(KEY_UBLOCK_VER, null)) ?: "Unknown"
    }

    private val _components = MutableStateFlow<Map<String, ComponentStatus>>(
        mapOf(
            ID_YTDLP to ComponentStatus(
                id = ID_YTDLP,
                name = "yt-dlp",
                installedVersion = cachedInstalledYtDlpVersion(),
            ),
            ID_UBLOCK to ComponentStatus(
                id = ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = ComponentVersionPriority
                    .sanitize(prefs.getString(KEY_UBLOCK_VER, null))
                    .orEmpty(),
            ),
        ),
    )
    val components: StateFlow<Map<String, ComponentStatus>> = _components.asStateFlow()

    init {
        clearRetiredComponentState()
        refreshInstalledVersions()
        observeComponentWorkers()
    }

    private fun clearRetiredComponentState() {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PUEMOS_WORK_NAME)
        }.onFailure { error ->
            Log.w(TAG, "Could not cancel a retired component update: ${error.message}")
        }
        prefs.edit().remove(LEGACY_PUEMOS_VERSION_KEY).apply()
        context.getSharedPreferences(ComponentUpdateWorker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("puemos.state")
            .remove("puemos.targetVersion")
            .remove("puemos.error")
            .remove("puemos.installedVersion")
            .apply()
    }

    private fun ensureYtdlpInitialized(): Boolean {
        return runCatching {
            YoutubeDL.getInstance().init(context)
            true
        }.getOrElse {
            runCatching { YoutubeDL.getInstance().versionName(context) != null }.getOrDefault(false)
        }
    }

    private suspend fun resolveAndPublishInstalledVersion(id: String): String {
        val version = when (id) {
            ID_YTDLP -> {
                ensureYtdlpInitialized()
                getInstalledYtDlpVersion()
            }
            ID_UBLOCK -> getInstalledUBlockVersion()
            else -> throw IllegalArgumentException("Unknown component $id")
        }
        val installed = version.takeUnless {
            it.isBlank() || it.equals("Unknown", ignoreCase = true)
        } ?: "Unknown"
        updateState(id) { current -> ComponentStatusTransitions.installedVersionResolved(current, installed) }
        return installed
    }

    fun refreshInstalledVersions() {
        scope.launch {
            listOf(ID_YTDLP, ID_UBLOCK).forEach { id ->
                // Until the artifact has been read, an empty version means "still resolving",
                // not "unavailable". This flag is display-only and never marks the component
                // as checking: discovery may already have completed while this read runs.
                updateState(id, ComponentStatusTransitions::resolutionStarted)
                try {
                    resolveAndPublishInstalledVersion(id)
                } finally {
                    updateState(id, ComponentStatusTransitions::resolutionFinished)
                }
            }
        }
    }

    private fun observeComponentWorkers() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        listOf(ID_YTDLP, ID_UBLOCK).forEach { compId ->
            val workName = ComponentUpdateWorker.getWorkName(compId)
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(workName).collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progressState = workInfo.progress.getString(ComponentUpdateWorker.KEY_PROGRESS_STATE)
                                ?: ComponentUpdateWorker.STATE_DOWNLOADING
                            val stateEnum = when (progressState) {
                                ComponentUpdateWorker.STATE_VERIFYING -> ComponentUpdateState.VERIFYING
                                ComponentUpdateWorker.STATE_INSTALLING -> ComponentUpdateState.INSTALLING
                                else -> ComponentUpdateState.DOWNLOADING
                            }
                            updateState(compId) {
                                it.copy(
                                    updateState = stateEnum,
                                    message = "Updating ($progressState)...",
                                    error = null,
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val verifiedVer = workInfo.outputData.getString(ComponentUpdateWorker.KEY_INSTALLED_VERSION)
                            if (!verifiedVer.isNullOrBlank()) {
                                when (compId) {
                                    ID_YTDLP -> prefs.edit().putString(KEY_YTDLP_VER, verifiedVer).apply()
                                    ID_UBLOCK -> prefs.edit().putString(KEY_UBLOCK_VER, verifiedVer).apply()
                                }
                                updateState(compId) {
                                    ComponentStatusTransitions.updateSucceeded(it, verifiedVer)
                                }
                            } else {
                                updateState(compId) {
                                    it.copy(
                                        lifecycleState = ComponentLifecycleState.UPDATED,
                                        updateState = ComponentUpdateState.UPDATE_SUCCESS,
                                        checkState = ComponentCheckState.UP_TO_DATE,
                                        error = null,
                                    )
                                }
                            }
                            if (_batchState.value == BatchOperationState.UPDATING &&
                                _components.value.values.none { it.isUpdating }
                            ) {
                                _batchState.value = BatchOperationState.COMPLETED
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString(ComponentUpdateWorker.KEY_ERROR) ?: "Update failed"
                            updateState(compId) {
                                it.copy(
                                    lifecycleState = ComponentLifecycleState.FAILED,
                                    updateState = ComponentUpdateState.UPDATE_FAILED,
                                    error = error,
                                    message = "Update failed: $error",
                                )
                            }
                            if (_batchState.value == BatchOperationState.UPDATING &&
                                _components.value.values.none { it.isUpdating }
                            ) {
                                _batchState.value = BatchOperationState.FAILED
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            updateState(compId) {
                                it.copy(
                                    lifecycleState = ComponentLifecycleState.UNKNOWN,
                                    updateState = ComponentUpdateState.UPDATE_IDLE,
                                    message = "Update cancelled",
                                )
                            }
                            if (_batchState.value == BatchOperationState.UPDATING &&
                                _components.value.values.none { it.isUpdating }
                            ) {
                                _batchState.value = BatchOperationState.CANCELLED
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun onChannelChanged(preference: UpdatePreference) {
        // Cancel in-flight yt-dlp check and update state cleanly, clearing any old failed state
        activeCheckJobs.remove(ID_YTDLP)?.cancel()
        checkGenerations.compute(ID_YTDLP) { _, v -> (v ?: 0L) + 1L }
        updateState(ID_YTDLP) {
            it.copy(
                checkState = ComponentCheckState.CHECK_IDLE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
                message = "Channel switched to ${preference.label}. Check for updates.",
                error = null,
            )
        }
    }

    fun checkUpdate(id: String, channel: UpdatePreference = UpdatePreference.STABLE) {
        // Single-flight check: if already checking this component, don't restart or create request storm
        val existingJob = activeCheckJobs[id]
        if (existingJob != null && existingJob.isActive) {
            Log.d(TAG, "Check already in flight for $id; reusing existing request")
            return
        }

        // Rate limit gate: if currently in cooldown, report truthfully without hitting network
        val now = System.currentTimeMillis()
        if (now < rateLimitCooldownUntil) {
            val cooldownMsg = rateLimitErrorMessage ?: "GitHub API rate limit reached. Try again later."
            updateState(id) {
                it.copy(
                    checkState = ComponentCheckState.CHECK_FAILED,
                    error = cooldownMsg,
                    message = cooldownMsg,
                    lastChecked = now,
                )
            }
            return
        }

        val generation = checkGenerations.compute(id) { _, v -> (v ?: 0L) + 1L }!!

        val job = scope.launch {
            checkUpdateInternal(id, channel, generation)
        }
        activeCheckJobs[id] = job
    }

    private suspend fun checkUpdateInternal(
        id: String,
        channel: UpdatePreference = UpdatePreference.STABLE,
        generation: Long = checkGenerations.compute(id) { _, v -> (v ?: 0L) + 1L }!!,
        isAutomaticCheck: Boolean = false,
    ) {
        if (checkGenerations[id] != generation) return
        currentCoroutineContext().ensureActive()
        checkOwners[id] = generation
        updateState(id, ComponentStatusTransitions::beginCheck)

        try {
            withTimeout(CHECK_TIMEOUT_MS) {
                // Resolve and publish the currently installed version.
                // This is a best-effort read: if the version cannot be read (e.g. on first
                // launch before the bundled library has been extracted) we continue with
                // whatever was stored previously rather than aborting the whole check with
                // a confusing "cannot compare" error.  A genuine local-resolution failure
                // is already reflected in the installedVersion field published by
                // resolveAndPublishInstalledVersion.
                resolveAndPublishInstalledVersion(id)

                val (latest, amoDownloadUrl, amoSha) = when (id) {
                    ID_YTDLP -> {
                        val repo = if (channel == UpdatePreference.NIGHTLY) "yt-dlp-nightly-builds" else "yt-dlp"
                        val tag = fetchLatestGithubRelease("yt-dlp", repo)
                        Triple(tag, null, null)
                    }
                    ID_UBLOCK -> {
                        val now = System.currentTimeMillis()
                        val lastCheck = prefs.getLong(KEY_AMO_UBLOCK_LAST_CHECK, 0L)
                        val cachedVer = prefs.getString(KEY_AMO_UBLOCK_CACHED_VERSION, null)
                        val cachedUrl = prefs.getString(KEY_AMO_UBLOCK_CACHED_URL, null)
                        val cachedSha = prefs.getString(KEY_AMO_UBLOCK_CACHED_SHA256, null)

                        if (isAutomaticCheck && (now - lastCheck < SIX_HOURS_MS) && cachedVer != null) {
                            Log.d(TAG, "Skipping AMO check within 6h cooldown; reusing cached v$cachedVer")
                            Triple(cachedVer, cachedUrl, cachedSha)
                        } else {
                            val cachedETag = prefs.getString(KEY_AMO_UBLOCK_ETAG, null)
                            val releaseInfo = fetchLatestAmoRelease("ublock-origin", cachedETag)
                            if (releaseInfo == null) {
                                // HTTP 304 - clean cached result
                                val resolvedVer = cachedVer
                                    ?: prefs.getString(KEY_UBLOCK_VER, null)
                                    ?: SecureComponentUpdater.BUNDLED_UBLOCK_VERSION
                                prefs.edit().putLong(KEY_AMO_UBLOCK_LAST_CHECK, now).apply()
                                Triple(resolvedVer, cachedUrl, cachedSha)
                            } else {
                                // HTTP 200 - new metadata
                                prefs.edit()
                                    .putString(KEY_AMO_UBLOCK_CACHED_VERSION, releaseInfo.version)
                                    .putString(KEY_AMO_UBLOCK_CACHED_URL, releaseInfo.downloadUrl)
                                    .putString(KEY_AMO_UBLOCK_CACHED_SHA256, releaseInfo.expectedSha256)
                                    .putLong(KEY_AMO_UBLOCK_LAST_CHECK, now)
                                    .apply()
                                if (!releaseInfo.eTag.isNullOrBlank()) {
                                    prefs.edit().putString(KEY_AMO_UBLOCK_ETAG, releaseInfo.eTag).apply()
                                }
                                Triple(releaseInfo.version, releaseInfo.downloadUrl, releaseInfo.expectedSha256)
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown component $id")
                }

                if (checkGenerations[id] != generation) return@withTimeout

                val current = _components.value[id] ?: return@withTimeout
                // Channel switch check for yt-dlp:
                // Stable -> Nightly or Nightly -> Stable channel switches allow explicit replacement
                val isChannelSwitch = if (id == ID_YTDLP) {
                    val isInstalledNightly = current.installedVersion.contains("nightly", ignoreCase = true)
                    val isTargetNightly = (channel == UpdatePreference.NIGHTLY)
                    isInstalledNightly != isTargetNightly
                } else false

                updateState(id) {
                    ComponentStatusTransitions.checkSucceeded(
                        status = it,
                        candidateVersion = latest,
                        channelSwitch = isChannelSwitch,
                        channelLabel = channel.label,
                        downloadUrl = amoDownloadUrl,
                        expectedSha256 = amoSha,
                    )
                }
            }
        } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
            // A parent batch timeout is cancellation too. Only turn this into a
            // per-component timeout when this component's own deadline fired.
            if (!currentCoroutineContext().isActive) throw te
            if (checkGenerations[id] == generation) {
                Log.w(TAG, "Check timed out for $id")
                updateState(id) {
                    ComponentStatusTransitions.checkFailed(it, "Check timed out (> ${CHECK_TIMEOUT_MS / 1000}s)")
                }
            }
        } catch (ce: CancellationException) {
            if (checkGenerations[id] == generation) {
                updateState(id, ComponentStatusTransitions::checkCancelled)
            }
            Log.d(TAG, "Check cancelled for $id (gen $generation)")
            throw ce
        } catch (e: Exception) {
            if (checkGenerations[id] == generation) {
                val errMsg = when (e) {
                    is RateLimitException -> e.message ?: "GitHub API rate limit reached. Try again later."
                    is SocketTimeoutException -> "Network timeout"
                    is IOException -> e.message ?: "Network error"
                    else -> e.message?.take(60) ?: "Unknown error"
                }
                updateState(id) { ComponentStatusTransitions.checkFailed(it, errMsg) }
            }
        } finally {
            // Only the request that set CHECKING may clear it. A channel switch or a newer
            // request already owns the current state in every other case.
            if (checkOwners[id] == generation) {
                checkOwners.remove(id, generation)
                updateState(id) { current ->
                    if (current.checkState == ComponentCheckState.CHECKING) {
                        current.copy(checkState = ComponentCheckState.CHECK_IDLE, message = "Ready to check", error = null)
                    } else {
                        current
                    }
                }
            }
            activeCheckJobs.computeIfPresent(id) { _, job -> if (job.isCompleted) null else job }
        }
    }


    /**
     * Checks all components with single-flight mutex protection and spam-resilience.
     * Rapid taps (1, 2, 5, 10 presses) are safely attached/no-op'd.
     * Guaranteed never to permanently hang in checking state.
     */
    fun checkAllUpdates(channel: UpdatePreference = UpdatePreference.STABLE) {
        if (!batchMutex.tryLock()) {
            Log.d(TAG, "checkAllUpdates already in flight; ignoring spam invocation")
            return
        }

        val now = System.currentTimeMillis()
        val last = lastCheckAllTimestamp.get()
        if (now - last < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "checkAllUpdates debounced (${now - last}ms since last call)")
            batchMutex.unlock()
            return
        }
        lastCheckAllTimestamp.set(now)
        globalCheckGeneration.incrementAndGet()

        scope.launch {
            _batchState.value = BatchOperationState.CHECKING
            try {
                withTimeout(CHECK_TIMEOUT_MS + BATCH_CLEANUP_GRACE_MS) {
                    coroutineScope {
                        val ytdlp = async { checkUpdateInternal(ID_YTDLP, channel, isAutomaticCheck = true) }
                        val ublock = async { checkUpdateInternal(ID_UBLOCK, channel, isAutomaticCheck = true) }
                        awaitAll(ytdlp, ublock)
                    }
                }
                val anyFailed = _components.value.values.any { it.checkState == ComponentCheckState.CHECK_FAILED }
                _batchState.value = if (anyFailed) BatchOperationState.FAILED else BatchOperationState.COMPLETED
            } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Check All Updates exceeded its total deadline")
                markPendingChecksFailed("Check timed out")
                _batchState.value = BatchOperationState.FAILED
            } catch (ce: CancellationException) {
                _batchState.value = BatchOperationState.CANCELLED
                markPendingChecksCancelled()
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "checkAllUpdates failed: ${e.message}", e)
                markPendingChecksFailed("Check failed: ${e.message?.take(60) ?: "Unknown error"}")
                _batchState.value = BatchOperationState.FAILED
            } finally {
                if (_batchState.value == BatchOperationState.CHECKING) {
                    markPendingChecksFailed("Check failed")
                    _batchState.value = BatchOperationState.FAILED
                }
                batchMutex.unlock()
            }
        }
    }

    /**
     * Executes automatic 24-hour startup check.
     * Persists timestamp ONLY upon successful check execution.
     * Non-blocking: emits dialog state if updates are available.
     */
    fun performStartupCheckIfNeeded(channel: UpdatePreference = UpdatePreference.STABLE) {
        scope.launch {
            val lastSuccess = prefs.getLong(KEY_LAST_AUTO_CHECK_SUCCESS_TIMESTAMP, 0L)
            val now = System.currentTimeMillis()
            if (now - lastSuccess < TWENTY_FOUR_HOURS_MS) {
                Log.d(TAG, "Skipping automatic startup check; within 24h gate (${(now - lastSuccess) / 1000}s ago)")
                return@launch
            }

            if (!batchMutex.tryLock()) return@launch
            try {
                withTimeout(CHECK_TIMEOUT_MS + BATCH_CLEANUP_GRACE_MS) {
                    coroutineScope {
                        val ytdlp = async { checkUpdateInternal(ID_YTDLP, channel, isAutomaticCheck = true) }
                        val ublock = async { checkUpdateInternal(ID_UBLOCK, channel, isAutomaticCheck = true) }
                        awaitAll(ytdlp, ublock)
                    }
                }

                val anyFailed = _components.value.values.any { it.checkState == ComponentCheckState.CHECK_FAILED }
                if (!anyFailed) {
                    prefs.edit().putLong(KEY_LAST_AUTO_CHECK_SUCCESS_TIMESTAMP, System.currentTimeMillis()).apply()
                    Log.i(TAG, "Automatic startup check succeeded; updated 24h timestamp")
                }

                val available = _components.value.values
                    .filter { it.checkState == ComponentCheckState.UPDATE_AVAILABLE && it.latestVersion != null }
                    .map {
                        ComponentUpdateItem(
                            id = it.id,
                            name = it.name,
                            currentVersion = it.installedVersion,
                            targetVersion = it.latestVersion!!,
                        )
                    }

                if (available.isNotEmpty()) {
                    _startupUpdateDialogData.value = StartupUpdateDialogData(available)
                }
            } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Automatic startup update check timed out")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Automatic startup check encountered error: ${e.message}")
            } finally {
                batchMutex.unlock()
            }
        }
    }

    fun applyUpdate(id: String, channel: UpdatePreference = UpdatePreference.STABLE) {
        val current = _components.value[id] ?: return
        if (current.isUpdating) return

        val targetVer = current.latestVersion ?: ""

        val (downloadUrl, expectedSha256) = when (id) {
            ID_YTDLP -> null to null
            ID_UBLOCK -> {
                val clean = cleanVersionString(targetVer)
                val url = current.downloadUrl
                    ?: prefs.getString(KEY_AMO_UBLOCK_CACHED_URL, null)
                    ?: if (clean == SecureComponentUpdater.BUNDLED_UBLOCK_VERSION) {
                        "https://github.com/gorhill/uBlock/releases/download/$clean/uBlock0_$clean.firefox.signed.xpi"
                    } else null

                val sha = current.expectedSha256
                    ?: prefs.getString(KEY_AMO_UBLOCK_CACHED_SHA256, null)
                    ?: SecureComponentUpdater.approvedExtensionSha256(ID_UBLOCK, clean)

                if (url.isNullOrBlank() || sha.isNullOrBlank()) {
                    val msg = "Missing verified download URL or checksum for $id: v$clean"
                    Log.w(TAG, msg)
                    updateState(id) {
                        it.copy(
                            updateState = ComponentUpdateState.UPDATE_FAILED,
                            lifecycleState = ComponentLifecycleState.FAILED,
                            error = msg,
                            message = "Update information incomplete.",
                        )
                    }
                    return
                }
                url to sha
            }
            else -> return
        }

        updateState(id) {
            it.copy(
                updateState = ComponentUpdateState.DOWNLOADING,
                lifecycleState = ComponentLifecycleState.DOWNLOADING,
                message = "Starting update for ${it.name}...",
                error = null,
            )
        }

        runCatching {
            val workManager = WorkManager.getInstance(context)
            val inputData = workDataOf(
                ComponentUpdateWorker.KEY_COMPONENT_ID to id,
                ComponentUpdateWorker.KEY_TARGET_VERSION to targetVer,
                ComponentUpdateWorker.KEY_INSTALLED_VERSION to current.installedVersion,
                ComponentUpdateWorker.KEY_CHANNEL to channel.name,
                ComponentUpdateWorker.KEY_DOWNLOAD_URL to downloadUrl,
                ComponentUpdateWorker.KEY_EXPECTED_SHA256 to expectedSha256,
            )
            val workRequest = OneTimeWorkRequestBuilder<ComponentUpdateWorker>()
                .setInputData(inputData)
                .build()

            workManager.enqueueUniqueWork(
                ComponentUpdateWorker.getWorkName(id),
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue ComponentUpdateWorker for $id: ${error.message}", error)
            updateState(id) {
                it.copy(
                    updateState = ComponentUpdateState.UPDATE_FAILED,
                    error = error.message ?: "Failed to start background worker",
                    message = "Update failed to start",
                )
            }
        }
    }

    fun applyAllUpdates(channel: UpdatePreference = UpdatePreference.STABLE) {
        _batchState.value = BatchOperationState.UPDATING
        dismissStartupDialog()
        _components.value.values.forEach { comp ->
            if (comp.checkState == ComponentCheckState.UPDATE_AVAILABLE) {
                applyUpdate(comp.id, channel)
            }
        }
    }

    private class RateLimitException(message: String) : IOException(message)

    private suspend fun fetchLatestGithubRelease(owner: String, repo: String): String {
        val cacheKey = "$owner/$repo"
        val cached = releaseCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            Log.d(TAG, "Using cached release for $cacheKey: ${cached.tag}")
            return cached.tag
        }

        if (now < rateLimitCooldownUntil) {
            throw RateLimitException(rateLimitErrorMessage ?: "GitHub API rate limit reached. Try again later.")
        }

        return suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>(null)
            continuation.invokeOnCancellation {
                connectionRef.getAndSet(null)?.disconnect()
            }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                var connection: HttpURLConnection? = null
                try {
                    val (openedConnection, code) = openGithubMetadataConnection(
                        initialUrl = "https://api.github.com/repos/$owner/$repo/releases/latest",
                        connectTimeoutMs = NETWORK_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = NETWORK_READ_TIMEOUT_MS,
                        connectionRef = connectionRef,
                        isCancelled = { !continuation.isActive },
                    ) ?: return@Runnable
                    connection = openedConnection

                    when (code) {
                        200 -> {
                            val jsonText = readBoundedResponseBody(connection.inputStream, MAX_RELEASE_METADATA_BYTES)
                            val json = JSONObject(jsonText)
                            if (json.optBoolean("prerelease", false) || json.optBoolean("draft", false)) {
                                throw IOException("Release in $owner/$repo is marked as prerelease or draft")
                            }
                            val tag = json.optString("tag_name").removePrefix("v").trim()
                            if (tag.isBlank()) throw IOException("Empty tag_name in release")
                            releaseCache[cacheKey] = ReleaseCacheEntry(tag, System.currentTimeMillis())
                            if (continuation.isActive) {
                                runCatching { continuation.resumeWith(Result.success(tag)) }
                            }
                        }
                        403 -> {
                            val retryAfterSec = connection.getHeaderField("Retry-After")?.toLongOrNull()
                            val rateLimitResetSec = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                            val responseTime = System.currentTimeMillis()
                            val cooldownMs = when {
                                retryAfterSec != null -> (retryAfterSec * 1000L).coerceAtLeast(10_000L)
                                rateLimitResetSec != null -> (rateLimitResetSec * 1000L - responseTime).coerceIn(10_000L, 3600_000L)
                                else -> 60_000L
                            }
                            rateLimitCooldownUntil = responseTime + cooldownMs
                            val message = "GitHub API rate limit reached. Try again later."
                            rateLimitErrorMessage = message
                            throw RateLimitException(message)
                        }
                        404 -> throw IOException("Release repository not found (HTTP 404)")
                        else -> throw IOException("GitHub returned HTTP $code")
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        runCatching { continuation.resumeWith(Result.failure(error)) }
                    }
                } finally {
                    connectionRef.getAndSet(null)?.disconnect()
                    connection?.disconnect()
                }
            })
        }
    }

    private fun markPendingChecksFailed(message: String) {
        _components.value.keys.forEach { id ->
            updateState(id) { current ->
                if (!current.isChecking) current else current.copy(
                    checkState = ComponentCheckState.CHECK_FAILED,
                    lifecycleState = ComponentLifecycleState.UNKNOWN,
                    message = message,
                    error = message,
                    lastChecked = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun markPendingChecksCancelled() {
        _components.value.keys.forEach { id ->
            updateState(id) { current ->
                if (!current.isChecking) current else current.copy(
                    checkState = ComponentCheckState.CHECK_IDLE,
                    lifecycleState = ComponentLifecycleState.UNKNOWN,
                    message = "Update check cancelled",
                    error = null,
                    lastChecked = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun updateState(id: String, transform: (ComponentStatus) -> ComponentStatus) {
        // Atomic compare-and-set: discovery, version resolution and worker observation all
        // publish from different dispatchers, so a read-copy-write would lose updates.
        _components.update { map ->
            val existing = map[id] ?: return@update map
            map + (id to transform(existing))
        }
    }

    companion object {
        const val ID_YTDLP = "ytdlp"
        const val ID_UBLOCK = "ublock"

        private const val KEY_YTDLP_VER = "ver_ytdlp"
        private const val KEY_UBLOCK_VER = "ver_ublock"
        private const val LEGACY_PUEMOS_VERSION_KEY = "ver_puemos"
        private const val LEGACY_PUEMOS_WORK_NAME = "VRKA_COMPONENT_UPDATE_puemos"
        private const val KEY_LAST_AUTO_CHECK_SUCCESS_TIMESTAMP = "last_auto_check_success_timestamp"

        const val KEY_AMO_UBLOCK_ETAG = "amo_ublock_etag"
        const val KEY_AMO_UBLOCK_LAST_CHECK = "amo_ublock_last_check"
        const val KEY_AMO_UBLOCK_CACHED_VERSION = "amo_ublock_cached_version"
        const val KEY_AMO_UBLOCK_CACHED_URL = "amo_ublock_cached_url"
        const val KEY_AMO_UBLOCK_CACHED_SHA256 = "amo_ublock_cached_sha256"
        const val SIX_HOURS_MS = 6 * 3600 * 1000L

        private const val CHECK_TIMEOUT_MS = 8_000L
        private const val BATCH_CLEANUP_GRACE_MS = 2_000L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 6_000
        private const val NETWORK_READ_TIMEOUT_MS = 6_000
        private const val MAX_RELEASE_METADATA_BYTES = 64 * 1024
        private const val UPDATE_TIMEOUT_MS = 90_000L
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val DEBOUNCE_INTERVAL_MS = 2_000L // 2 seconds
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

        private const val TAG = "VRKA-ComponentUpdater"

        @Volatile
        private var INSTANCE: ComponentUpdateManager? = null

        fun getInstance(context: Context): ComponentUpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ComponentUpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Extracts all numeric component sequences from a version string.
         * Examples:
         * - "v2026.08.30.232658" -> [2026, 8, 30, 232658]
         * - "yt-dlp 2026.08.30.232658" -> [2026, 8, 30, 232658]
         * - "yt-dlp nightly 2026.08.30.232658" -> [2026, 8, 30, 232658]
         * - "nightly 2026.08.30.232658" -> [2026, 8, 30, 232658]
         * - "v2026.08.19" -> [2026, 8, 19]
         * - "1.74.0" -> [1, 74, 0]
         */
        fun extractVersionNumbers(version: String): List<Long> {
            return Regex("""\d+""").findAll(version).map { it.value.toLong() }.toList()
        }

        /**
         * Normalizes any version string into a clean display format, stripping prefixes
         * like 'v', 'yt-dlp', 'nightly' while preserving the canonical version identifier.
         */
        fun cleanVersionString(version: String): String {
            val trimmed = version.trim()
            var cleaned = trimmed
            var changed = true
            while (changed) {
                val prev = cleaned
                cleaned = cleaned
                    .removePrefix("yt-dlp ")
                    .removePrefix("yt-dlp-")
                    .removePrefix("nightly ")
                    .removePrefix("nightly-")
                    .removePrefix("v")
                    .trim()
                changed = (cleaned != prev)
            }
            return cleaned.ifBlank { trimmed }
        }

        /**
         * Compares two versions (date-based YYYY.MM.DD or SemVer X.Y.Z).
         * Returns true only if [candidate] is strictly newer than [installed].
         */
        fun isNewerVersion(candidate: String, installed: String): Boolean {
            val candNums = extractVersionNumbers(candidate)
            val instNums = extractVersionNumbers(installed)
            if (candNums.isEmpty() || instNums.isEmpty()) {
                val cleanC = cleanVersionString(candidate)
                val cleanI = cleanVersionString(installed)
                if (cleanC.equals(cleanI, ignoreCase = true)) return false
                return cleanC.compareTo(cleanI) > 0
            }

            if (candNums == instNums) return false

            val minLen = minOf(candNums.size, instNums.size)
            for (i in 0 until minLen) {
                if (candNums[i] > instNums[i]) return true
                if (candNums[i] < instNums[i]) return false
            }
            return candNums.size > instNums.size
        }
    }
}

internal val APPROVED_GITHUB_METADATA_HOSTS = setOf(
    "api.github.com",
    "github.com",
)
private const val MAX_GITHUB_METADATA_REDIRECTS = 5
private val GITHUB_METADATA_REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

internal fun validateGithubMetadataUrl(
    url: String,
    allowedHosts: Set<String> = APPROVED_GITHUB_METADATA_HOSTS,
    allowedSchemes: Set<String> = setOf("https"),
): URL {
    val parsed = URL(url)
    val scheme = parsed.protocol?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (scheme !in allowedSchemes) {
        throw SecurityException("Insecure protocol '$scheme' rejected for GitHub API metadata")
    }
    if (parsed.userInfo != null) {
        throw SecurityException("Embedded userInfo rejected for GitHub API metadata")
    }
    val host = parsed.host?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (host.isBlank() || host !in allowedHosts) {
        throw SecurityException("Unapproved redirect or request host '$host' rejected for GitHub API metadata")
    }
    return parsed
}

internal fun openGithubMetadataConnection(
    initialUrl: String,
    connectTimeoutMs: Int = 6_000,
    readTimeoutMs: Int = 6_000,
    connectionRef: AtomicReference<HttpURLConnection?>? = null,
    isCancelled: () -> Boolean = { false },
    allowedHosts: Set<String> = APPROVED_GITHUB_METADATA_HOSTS,
    allowedSchemes: Set<String> = setOf("https"),
    maxRedirects: Int = MAX_GITHUB_METADATA_REDIRECTS,
    connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
): Pair<HttpURLConnection, Int>? {
    var currentUrl = initialUrl
    for (redirectCount in 0..maxRedirects) {
        val parsed = validateGithubMetadataUrl(
            url = currentUrl,
            allowedHosts = allowedHosts,
            allowedSchemes = allowedSchemes,
        )
        val conn = connectionFactory(parsed)
        var handOffToCaller = false
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "VRKA-Android-Updater")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            connectionRef?.set(conn)
            if (isCancelled()) {
                return null
            }

            val code = conn.responseCode
            if (code in GITHUB_METADATA_REDIRECT_STATUS_CODES) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP $code redirect missing Location header from GitHub API")
                }
                if (redirectCount == maxRedirects) {
                    throw IOException("Exceeded maximum redirect depth ($maxRedirects) for GitHub API")
                }
                val resolved = HeaderValidation.resolveRedirectUrl(currentUrl, location)
                    ?: throw SecurityException("Rejected malformed or unsafe redirect from GitHub API")
                validateGithubMetadataUrl(
                    url = resolved,
                    allowedHosts = allowedHosts,
                    allowedSchemes = allowedSchemes,
                )
                currentUrl = resolved
                continue
            }

            handOffToCaller = true
            return conn to code
        } finally {
            if (!handOffToCaller) {
                connectionRef?.compareAndSet(conn, null)
                runCatching { conn.disconnect() }
            }
        }
    }
    throw IOException("Exceeded maximum redirect depth ($maxRedirects) for GitHub API")
}

internal fun readBoundedResponseBody(inputStream: InputStream, maxBytes: Int): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    inputStream.use { input ->
        val buffer = ByteArray(4 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                throw IOException("Release metadata exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, count)
        }
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

data class AmoReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val size: Long,
    val eTag: String? = null,
)

internal val APPROVED_AMO_METADATA_HOSTS = setOf(
    "addons.mozilla.org",
    "addons.cdn.mozilla.net",
)
private const val MAX_AMO_METADATA_REDIRECTS = 3
private val AMO_METADATA_REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

internal fun validateAmoMetadataUrl(
    url: String,
    allowedHosts: Set<String> = APPROVED_AMO_METADATA_HOSTS,
    allowedSchemes: Set<String> = setOf("https"),
): URL {
    val parsed = URL(url)
    val scheme = parsed.protocol?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (scheme !in allowedSchemes) {
        throw SecurityException("Insecure protocol '$scheme' rejected for AMO API metadata")
    }
    if (parsed.userInfo != null) {
        throw SecurityException("Embedded userInfo rejected for AMO API metadata")
    }
    val host = parsed.host?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (host.isBlank() || host !in allowedHosts) {
        throw SecurityException("Unapproved redirect or request host '$host' rejected for AMO API metadata")
    }
    return parsed
}

internal fun openAmoMetadataConnection(
    initialUrl: String,
    connectTimeoutMs: Int = 6_000,
    readTimeoutMs: Int = 6_000,
    ifNoneMatchETag: String? = null,
    connectionRef: AtomicReference<HttpURLConnection?>? = null,
    isCancelled: () -> Boolean = { false },
    allowedHosts: Set<String> = APPROVED_AMO_METADATA_HOSTS,
    allowedSchemes: Set<String> = setOf("https"),
    maxRedirects: Int = MAX_AMO_METADATA_REDIRECTS,
    connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
): Pair<HttpURLConnection, Int>? {
    var currentUrl = initialUrl
    for (redirectCount in 0..maxRedirects) {
        val parsed = validateAmoMetadataUrl(
            url = currentUrl,
            allowedHosts = allowedHosts,
            allowedSchemes = allowedSchemes,
        )
        val conn = connectionFactory(parsed)
        var handOffToCaller = false
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "VRKA-Android-Updater")
            conn.setRequestProperty("Accept", "application/json")
            if (redirectCount == 0 && !ifNoneMatchETag.isNullOrBlank()) {
                conn.setRequestProperty("If-None-Match", ifNoneMatchETag)
            }
            connectionRef?.set(conn)
            if (isCancelled()) {
                return null
            }

            val code = conn.responseCode
            if (code in AMO_METADATA_REDIRECT_STATUS_CODES) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP $code redirect missing Location header from AMO API")
                }
                if (redirectCount == maxRedirects) {
                    throw IOException("Exceeded maximum redirect depth ($maxRedirects) for AMO API")
                }
                val resolved = HeaderValidation.resolveRedirectUrl(currentUrl, location)
                    ?: throw SecurityException("Rejected malformed or unsafe redirect from AMO API")
                validateAmoMetadataUrl(
                    url = resolved,
                    allowedHosts = allowedHosts,
                    allowedSchemes = allowedSchemes,
                )
                currentUrl = resolved
                continue
            }

            handOffToCaller = true
            return conn to code
        } finally {
            if (!handOffToCaller) {
                connectionRef?.compareAndSet(conn, null)
                runCatching { conn.disconnect() }
            }
        }
    }
    throw IOException("Exceeded maximum redirect depth ($maxRedirects) for AMO API")
}

internal suspend fun fetchLatestAmoRelease(
    addonSlug: String = "ublock-origin",
    ifNoneMatchETag: String? = null,
    connectTimeoutMs: Int = 6_000,
    readTimeoutMs: Int = 6_000,
    connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
): AmoReleaseInfo? {
    val apiUrl = "https://addons.mozilla.org/api/v5/addons/addon/$addonSlug/"
    return suspendCancellableCoroutine { continuation ->
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            connectionRef.getAndSet(null)?.disconnect()
        }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            var connection: HttpURLConnection? = null
            try {
                val (openedConnection, code) = openAmoMetadataConnection(
                    initialUrl = apiUrl,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    ifNoneMatchETag = ifNoneMatchETag,
                    connectionRef = connectionRef,
                    isCancelled = { !continuation.isActive },
                    connectionFactory = connectionFactory,
                ) ?: return@Runnable
                connection = openedConnection

                when (code) {
                    200 -> {
                        val eTag = connection.getHeaderField("ETag")?.trim()
                        val jsonText = readBoundedResponseBody(connection.inputStream, 256 * 1024)
                        val root = JSONObject(jsonText)
                        val currentVersionObj = root.optJSONObject("current_version")
                            ?: throw IOException("Missing current_version in AMO API response")
                        val version = currentVersionObj.optString("version").trim()
                        if (version.isBlank()) {
                            throw IOException("Missing version in AMO current_version")
                        }
                        val fileObj = currentVersionObj.optJSONObject("file")
                            ?: currentVersionObj.optJSONArray("files")?.optJSONObject(0)
                            ?: throw IOException("Missing file object in AMO current_version")
                        val status = fileObj.optString("status").trim()
                        if (status != "public") {
                            throw SecurityException("AMO file status is '$status', expected 'public'")
                        }
                        val platform = fileObj.optString("platform", "all").trim().lowercase(java.util.Locale.ROOT)
                        if (platform !in setOf("all", "android")) {
                            throw SecurityException("AMO file platform '$platform' is not supported")
                        }
                        val size = fileObj.optLong("size", -1L)
                        if (size !in 1L..20_000_000L) {
                            throw SecurityException("AMO file size out of bounds: $size")
                        }
                        val rawHash = fileObj.optString("hash").trim()
                        val hashMatch = Regex("""^sha256:([0-9a-fA-F]{64})$""").find(rawHash)
                            ?: throw SecurityException("Invalid AMO hash format: $rawHash")
                        val expectedSha256 = hashMatch.groupValues[1].lowercase(java.util.Locale.ROOT)
                        val downloadUrl = fileObj.optString("url").trim()
                        val parsedDownloadUrl = validateAmoMetadataUrl(downloadUrl, APPROVED_AMO_METADATA_HOSTS)

                        val info = AmoReleaseInfo(
                            version = version,
                            downloadUrl = parsedDownloadUrl.toString(),
                            expectedSha256 = expectedSha256,
                            size = size,
                            eTag = eTag,
                        )
                        if (continuation.isActive) {
                            runCatching { continuation.resumeWith(Result.success(info)) }
                        }
                    }
                    304 -> {
                        // Clean cached result
                        if (continuation.isActive) {
                            runCatching { continuation.resumeWith(Result.success(null)) }
                        }
                    }
                    404 -> throw IOException("Add-on not found on AMO (HTTP 404)")
                    429 -> throw IOException("AMO API rate limit reached (HTTP 429)")
                    else -> throw IOException("AMO returned HTTP $code")
                }
            } catch (error: Exception) {
                if (continuation.isActive) {
                    runCatching { continuation.resumeWith(Result.failure(error)) }
                }
            } finally {
                connectionRef.getAndSet(null)?.disconnect()
                connection?.disconnect()
            }
        })
    }
}
