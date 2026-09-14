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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val message: String = "",
    val error: String? = null,
    val lastChecked: Long = 0L,
) {
    val isChecking: Boolean get() = checkState == ComponentCheckState.CHECKING ||
        lifecycleState == ComponentLifecycleState.CHECKING
    val isUpdating: Boolean get() = updateState in setOf(
        ComponentUpdateState.DOWNLOADING,
        ComponentUpdateState.VERIFYING,
        ComponentUpdateState.INSTALLING,
    ) || lifecycleState in setOf(
        ComponentLifecycleState.DOWNLOADING,
        ComponentLifecycleState.VERIFYING,
        ComponentLifecycleState.STAGING,
        ComponentLifecycleState.INSTALLING,
        ComponentLifecycleState.VERIFYING_INSTALL,
        ComponentLifecycleState.ROLLING_BACK,
    )
}

class ComponentUpdateManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("vrka_components", Context.MODE_PRIVATE)

    private val checkGenerations = ConcurrentHashMap<String, Long>()
    private val updateGenerations = ConcurrentHashMap<String, Long>()
    private val globalCheckGeneration = AtomicLong(0L)
    private val activeCheckJobs = ConcurrentHashMap<String, Job>()
    private val activeUpdateJobs = ConcurrentHashMap<String, Job>()

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

    private fun getInstalledYtDlpVersion(): String {
        val runtimeVer = runCatching {
            YoutubeDL.getInstance().versionName(context)?.removePrefix("yt-dlp ")?.trim()
        }.getOrNull()?.ifBlank { null }
        if (runtimeVer != null) {
            prefs.edit().putString(KEY_YTDLP_VER, runtimeVer).apply()
            return runtimeVer
        }
        val prefVer = prefs.getString(KEY_YTDLP_VER, null)
        if (!prefVer.isNullOrBlank()) return prefVer
        val ytdlPrefs = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        val ytdlVer = ytdlPrefs.getString("dlpVersionName", null)
            ?: ytdlPrefs.getString("dlpVersion", null)
        if (!ytdlVer.isNullOrBlank()) return ytdlVer.removePrefix("yt-dlp ").trim()
        return DEFAULT_YTDLP_VER
    }

    private suspend fun getInstalledUBlockVersion(): String {
        val geckoManager = runCatching { GeckoRuntimeManager.getInstance(context) }.getOrNull()
        val runtimeVer = geckoManager?.getInstalledExtensionVersion(GeckoRuntimeManager.UBLOCK_ID)
        if (!runtimeVer.isNullOrBlank()) {
            prefs.edit().putString(KEY_UBLOCK_VER, runtimeVer).apply()
            return runtimeVer
        }
        // If GeckoView extension cannot be queried, fail closed or report "Unknown", never assume bundled version.
        return "Unknown"
    }

    private suspend fun getInstalledPuemosVersion(): String {
        val geckoManager = runCatching { GeckoRuntimeManager.getInstance(context) }.getOrNull()
        val runtimeVer = geckoManager?.getInstalledExtensionVersion(GeckoRuntimeManager.PUEMOS_ID)
        if (!runtimeVer.isNullOrBlank()) {
            prefs.edit().putString(KEY_PUEMOS_VER, runtimeVer).apply()
            return runtimeVer
        }
        // If GeckoView extension cannot be queried, fail closed or report "Unknown", never assume bundled version.
        return "Unknown"
    }

    private val _components = MutableStateFlow<Map<String, ComponentStatus>>(
        mapOf(
            ID_YTDLP to ComponentStatus(
                id = ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = getInstalledYtDlpVersion(),
                message = "Ready",
            ),
            ID_UBLOCK to ComponentStatus(
                id = ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = prefs.getString(KEY_UBLOCK_VER, null) ?: "Unknown",
                message = "Ready",
            ),
            ID_PUEMOS to ComponentStatus(
                id = ID_PUEMOS,
                name = "Puemos",
                installedVersion = prefs.getString(KEY_PUEMOS_VER, null) ?: "Unknown",
                message = "Ready",
            ),
        ),
    )
    val components: StateFlow<Map<String, ComponentStatus>> = _components.asStateFlow()

    init {
        refreshInstalledVersions()
        observeComponentWorkers()
    }

    private fun ensureYtdlpInitialized(): Boolean {
        return runCatching {
            YoutubeDL.getInstance().init(context)
            true
        }.getOrElse {
            runCatching { YoutubeDL.getInstance().versionName(context) != null }.getOrDefault(false)
        }
    }

    fun refreshInstalledVersions() {
        scope.launch {
            val ytdlpVer = getInstalledYtDlpVersion()
            val ublockVer = getInstalledUBlockVersion()
            val puemosVer = getInstalledPuemosVersion()

            updateState(ID_YTDLP) { it.copy(installedVersion = ytdlpVer) }
            updateState(ID_UBLOCK) { it.copy(installedVersion = ublockVer) }
            updateState(ID_PUEMOS) { it.copy(installedVersion = puemosVer) }
        }
    }

    private fun observeComponentWorkers() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        listOf(ID_YTDLP, ID_UBLOCK, ID_PUEMOS).forEach { compId ->
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
                                val cleanVer = cleanVersionString(verifiedVer)
                                when (compId) {
                                    ID_YTDLP -> prefs.edit().putString(KEY_YTDLP_VER, verifiedVer).apply()
                                    ID_UBLOCK -> prefs.edit().putString(KEY_UBLOCK_VER, verifiedVer).apply()
                                    ID_PUEMOS -> prefs.edit().putString(KEY_PUEMOS_VER, verifiedVer).apply()
                                }
                                updateState(compId) {
                                    it.copy(
                                        updateState = ComponentUpdateState.UPDATE_SUCCESS,
                                        checkState = ComponentCheckState.UP_TO_DATE,
                                        installedVersion = verifiedVer,
                                        latestVersion = cleanVer,
                                        message = "Updated successfully to v$cleanVer",
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
                                    updateState = ComponentUpdateState.UPDATE_IDLE,
                                    message = "Update cancelled",
                                )
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
        activeCheckJobs[ID_YTDLP]?.cancel()
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
    ) {
        updateState(id) {
            it.copy(
                checkState = ComponentCheckState.CHECKING,
                message = "Checking for updates...",
                error = null,
            )
        }

        try {
            withTimeout(CHECK_TIMEOUT_MS) {
                val latest = when (id) {
                    ID_YTDLP -> {
                        val repo = if (channel == UpdatePreference.NIGHTLY) "yt-dlp-nightly-builds" else "yt-dlp"
                        fetchLatestGithubRelease("yt-dlp", repo)
                    }
                    ID_UBLOCK -> {
                        fetchLatestGithubRelease("gorhill", "uBlock")
                    }
                    ID_PUEMOS -> {
                        fetchLatestGithubRelease("puemos", "hls-downloader")
                    }
                    else -> throw IllegalArgumentException("Unknown component $id")
                }

                if (checkGenerations[id] != generation) return@withTimeout

                val current = _components.value[id] ?: return@withTimeout
                val isNewer = isNewerVersion(candidate = latest, installed = current.installedVersion)

                // Channel switch check for yt-dlp:
                // Stable -> Nightly or Nightly -> Stable channel switches allow explicit replacement
                val isChannelSwitch = if (id == ID_YTDLP) {
                    val isInstalledNightly = current.installedVersion.contains("nightly", ignoreCase = true)
                    val isTargetNightly = (channel == UpdatePreference.NIGHTLY)
                    isInstalledNightly != isTargetNightly
                } else false

                val hasUpdate = isNewer || isChannelSwitch

                val cleanCandidate = cleanVersionString(latest)
                val cleanInstalled = cleanVersionString(current.installedVersion)

                updateState(id) {
                    it.copy(
                        checkState = if (hasUpdate) ComponentCheckState.UPDATE_AVAILABLE else ComponentCheckState.UP_TO_DATE,
                        latestVersion = cleanCandidate,
                        message = if (hasUpdate) {
                            if (isChannelSwitch) "Channel switch: v$cleanCandidate (${channel.label})"
                            else "Update available: v$cleanCandidate"
                        } else {
                            "Up to date (v$cleanInstalled)"
                        },
                        error = null,
                        lastChecked = System.currentTimeMillis(),
                    )
                }
            }
        } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
            if (checkGenerations[id] == generation) {
                Log.w(TAG, "Check timed out for $id")
                updateState(id) {
                    it.copy(
                        checkState = ComponentCheckState.CHECK_FAILED,
                        error = "Check timed out (> ${CHECK_TIMEOUT_MS / 1000}s)",
                        message = "Check timed out",
                        lastChecked = System.currentTimeMillis(),
                    )
                }
            }
        } catch (ce: CancellationException) {
            Log.d(TAG, "Check cancelled for $id (gen $generation)")
        } catch (e: Exception) {
            if (checkGenerations[id] == generation) {
                val errMsg = when (e) {
                    is RateLimitException -> e.message ?: "GitHub API rate limit reached. Try again later."
                    is SocketTimeoutException -> "Network timeout"
                    is IOException -> e.message ?: "Network error"
                    else -> e.message?.take(60) ?: "Unknown error"
                }
                Log.e(TAG, "Failed checking update for $id: $errMsg", e)
                updateState(id) {
                    it.copy(
                        checkState = ComponentCheckState.CHECK_FAILED,
                        error = errMsg,
                        message = if (errMsg.startsWith("GitHub API rate limit")) errMsg else "Check failed: $errMsg",
                        lastChecked = System.currentTimeMillis(),
                    )
                }
            }
        } finally {
            if (checkGenerations[id] == generation) {
                activeCheckJobs.remove(id)
            }
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
                withTimeout(CHECK_TIMEOUT_MS * 3) {
                    coroutineScope {
                        val ytdlp = async { checkUpdateInternal(ID_YTDLP, channel) }
                        val ublock = async { checkUpdateInternal(ID_UBLOCK, channel) }
                        val puemos = async { checkUpdateInternal(ID_PUEMOS, channel) }
                        awaitAll(ytdlp, ublock, puemos)
                    }
                }
                val allFailed = _components.value.values.all { it.checkState == ComponentCheckState.CHECK_FAILED }
                _batchState.value = if (allFailed) BatchOperationState.FAILED else BatchOperationState.COMPLETED
            } catch (ce: CancellationException) {
                _batchState.value = BatchOperationState.CANCELLED
            } catch (e: Exception) {
                Log.e(TAG, "checkAllUpdates failed: ${e.message}", e)
                _batchState.value = BatchOperationState.FAILED
            } finally {
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
                withTimeout(CHECK_TIMEOUT_MS * 3) {
                    coroutineScope {
                        val ytdlp = async { checkUpdateInternal(ID_YTDLP, channel) }
                        val ublock = async { checkUpdateInternal(ID_UBLOCK, channel) }
                        val puemos = async { checkUpdateInternal(ID_PUEMOS, channel) }
                        awaitAll(ytdlp, ublock, puemos)
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
                "https://github.com/gorhill/uBlock/releases/download/$clean/uBlock0_$clean.firefox.signed.xpi" to null
            }
            ID_PUEMOS -> {
                val clean = cleanVersionString(targetVer)
                "https://github.com/puemos/hls-downloader/releases/download/v$clean/extension-mv2-firefox.xpi" to null
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

    private suspend fun fetchLatestGithubRelease(owner: String, repo: String): String = withContext(Dispatchers.IO) {
        val cacheKey = "$owner/$repo"
        val cached = releaseCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            Log.d(TAG, "Using cached release for $cacheKey: ${cached.tag}")
            return@withContext cached.tag
        }

        if (now < rateLimitCooldownUntil) {
            throw RateLimitException(rateLimitErrorMessage ?: "GitHub API rate limit reached. Try again later.")
        }

        val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "VRKA-Android-Updater")
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        try {
            val code = conn.responseCode
            when (code) {
                200 -> {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(jsonStr)
                    val isPrerelease = json.optBoolean("prerelease", false)
                    val isDraft = json.optBoolean("draft", false)
                    if (isPrerelease || isDraft) {
                        throw IOException("Release in $owner/$repo is marked as prerelease or draft")
                    }
                    val tag = json.optString("tag_name").removePrefix("v").trim()
                    if (tag.isBlank()) throw IOException("Empty tag_name in release")
                    releaseCache[cacheKey] = ReleaseCacheEntry(tag, now)
                    tag
                }
                403 -> {
                    val retryAfterSec = conn.getHeaderField("Retry-After")?.toLongOrNull()
                    val rateLimitResetSec = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val cooldownMs = when {
                        retryAfterSec != null -> (retryAfterSec * 1000L).coerceAtLeast(10_000L)
                        rateLimitResetSec != null -> (rateLimitResetSec * 1000L - now).coerceIn(10_000L, 3600_000L)
                        else -> 60_000L // 1 minute default cooldown
                    }
                    rateLimitCooldownUntil = now + cooldownMs
                    val msg = "GitHub API rate limit reached. Try again later."
                    rateLimitErrorMessage = msg
                    throw RateLimitException(msg)
                }
                404 -> throw IOException("Release repository not found (HTTP 404)")
                else -> throw IOException("GitHub returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun updateState(id: String, transform: (ComponentStatus) -> ComponentStatus) {
        val map = _components.value.toMutableMap()
        val existing = map[id] ?: return
        map[id] = transform(existing)
        _components.value = map
    }

    companion object {
        const val ID_YTDLP = "ytdlp"
        const val ID_UBLOCK = "ublock"
        const val ID_PUEMOS = "puemos"

        const val DEFAULT_YTDLP_VER = "2026.08.19"
        const val DEFAULT_UBLOCK_VER = "1.74.0"
        const val DEFAULT_PUEMOS_VER = "5.5.0"

        private const val KEY_YTDLP_VER = "ver_ytdlp"
        private const val KEY_UBLOCK_VER = "ver_ublock"
        private const val KEY_PUEMOS_VER = "ver_puemos"
        private const val KEY_LAST_AUTO_CHECK_SUCCESS_TIMESTAMP = "last_auto_check_success_timestamp"

        private const val CHECK_TIMEOUT_MS = 8_000L
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
