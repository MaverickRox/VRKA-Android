package com.mvrk.vrka

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

data class ComponentStatus(
    val id: String,
    val name: String,
    val installedVersion: String,
    val latestVersion: String? = null,
    val checkState: ComponentCheckState = ComponentCheckState.CHECK_IDLE,
    val updateState: ComponentUpdateState = ComponentUpdateState.UPDATE_IDLE,
    val message: String = "",
    val error: String? = null,
    val lastChecked: Long = 0L,
) {
    val isChecking: Boolean get() = checkState == ComponentCheckState.CHECKING
    val isUpdating: Boolean get() = updateState == ComponentUpdateState.DOWNLOADING ||
        updateState == ComponentUpdateState.VERIFYING ||
        updateState == ComponentUpdateState.INSTALLING
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

    @Volatile
    private var rateLimitCooldownUntil: Long = 0L
    @Volatile
    private var rateLimitErrorMessage: String? = null

    private val _components = MutableStateFlow<Map<String, ComponentStatus>>(
        mapOf(
            ID_YTDLP to ComponentStatus(
                id = ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = prefs.getString(KEY_YTDLP_VER, "2026.06.30") ?: "2026.06.30",
                message = "Ready",
            ),
            ID_UBLOCK to ComponentStatus(
                id = ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = "1.74.0",
                message = "Bundled • App Release",
            ),
            ID_PUEMOS to ComponentStatus(
                id = ID_PUEMOS,
                name = "Puemos HLS Detection",
                installedVersion = "1.0.0",
                message = "Bundled • App Release",
            ),
        ),
    )
    val components: StateFlow<Map<String, ComponentStatus>> = _components.asStateFlow()

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
            val ytdlpVer = runCatching {
                YoutubeDL.getInstance().versionName(context)?.removePrefix("yt-dlp ")?.trim()
            }.getOrNull() ?: prefs.getString(KEY_YTDLP_VER, "2026.06.30") ?: "2026.06.30"

            updateState(ID_YTDLP) { it.copy(installedVersion = ytdlpVer) }
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
            updateState(id) {
                it.copy(
                    checkState = ComponentCheckState.CHECKING,
                    message = "Checking for updates...",
                    error = null,
                )
            }

            try {
                withTimeout(CHECK_TIMEOUT_MS) {
                    if (id == ID_UBLOCK || id == ID_PUEMOS) {
                        updateState(id) {
                            it.copy(
                                checkState = ComponentCheckState.UP_TO_DATE,
                                message = "Bundled APK extension; updated via application releases.",
                                error = null,
                                lastChecked = System.currentTimeMillis(),
                            )
                        }
                        return@withTimeout
                    }

                    val latest = when (id) {
                        ID_YTDLP -> {
                            val repo = if (channel == UpdatePreference.NIGHTLY) "yt-dlp-nightly-builds" else "yt-dlp"
                            fetchLatestGithubRelease("yt-dlp", repo)
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
        activeCheckJobs[id] = job
    }

    fun checkAllUpdates(channel: UpdatePreference = UpdatePreference.STABLE) {
        val now = System.currentTimeMillis()
        val last = lastCheckAllTimestamp.get()
        if (now - last < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "checkAllUpdates debounced (${now - last}ms since last call)")
            return
        }
        lastCheckAllTimestamp.set(now)
        globalCheckGeneration.incrementAndGet()
        checkUpdate(ID_YTDLP, channel)
        updateState(ID_UBLOCK) {
            it.copy(
                checkState = ComponentCheckState.UP_TO_DATE,
                message = "Bundled APK extension; updated via application releases.",
                lastChecked = now,
            )
        }
        updateState(ID_PUEMOS) {
            it.copy(
                checkState = ComponentCheckState.UP_TO_DATE,
                message = "Bundled APK extension; updated via application releases.",
                lastChecked = now,
            )
        }
    }

    fun applyUpdate(id: String, channel: UpdatePreference = UpdatePreference.STABLE) {
        if (id == ID_UBLOCK || id == ID_PUEMOS) {
            updateState(id) {
                it.copy(
                    updateState = ComponentUpdateState.UPDATE_SUCCESS,
                    checkState = ComponentCheckState.UP_TO_DATE,
                    message = "Bundled APK extension; updated via application releases.",
                    error = null,
                )
            }
            return
        }

        val generation = updateGenerations.compute(id) { _, v -> (v ?: 0L) + 1L }!!
        activeUpdateJobs[id]?.cancel()

        val job = scope.launch {
            updateState(id) {
                it.copy(
                    updateState = ComponentUpdateState.DOWNLOADING,
                    message = "Downloading update...",
                    error = null,
                )
            }

            try {
                withTimeout(UPDATE_TIMEOUT_MS) {
                    when (id) {
                        ID_YTDLP -> {
                            val initSuccess = ensureYtdlpInitialized()
                            if (!initSuccess) {
                                val failureReason = "yt-dlp runtime initialization failed"
                                updateState(id) {
                                    it.copy(
                                        updateState = ComponentUpdateState.UPDATE_FAILED,
                                        error = failureReason,
                                        message = "Update failed; runtime not initialized",
                                    )
                                }
                                return@withTimeout
                            }

                            if (updateGenerations[id] != generation) return@withTimeout

                            val updater = SecureComponentUpdater(context)
                            val current = _components.value[id]
                            val targetTag = current?.latestVersion?.takeIf { it.isNotBlank() }
                                ?: updater.fetchLatestReleaseTag(channel)

                            updateState(id) {
                                it.copy(
                                    updateState = ComponentUpdateState.INSTALLING,
                                    message = "Authenticating and installing yt-dlp v$targetTag...",
                                )
                            }

                            val updateResult = updater.updateYtDlp(channel, targetTag)

                            if (updateGenerations[id] != generation) return@withTimeout

                            updateState(id) {
                                it.copy(
                                    updateState = ComponentUpdateState.VERIFYING,
                                    message = "Verifying installed binary...",
                                )
                            }

                            if (updateResult.isSuccess) {
                                val postVersion = updateResult.getOrThrow()
                                val cleanPostVersion = cleanVersionString(postVersion)
                                prefs.edit().putString(KEY_YTDLP_VER, postVersion).apply()
                                updateState(id) {
                                    it.copy(
                                        updateState = ComponentUpdateState.UPDATE_SUCCESS,
                                        checkState = ComponentCheckState.UP_TO_DATE,
                                        installedVersion = postVersion,
                                        latestVersion = cleanPostVersion,
                                        message = "Updated successfully to v$cleanPostVersion",
                                        error = null,
                                    )
                                }
                            } else {
                                val failureReason = updateResult.exceptionOrNull()?.message
                                    ?: "Authenticated update failed"
                                Log.e(TAG, "yt-dlp secure update failed: $failureReason")
                                val channelMsg = "Update failed: ${failureReason.take(80)}"
                                updateState(id) {
                                    it.copy(
                                        updateState = ComponentUpdateState.UPDATE_FAILED,
                                        error = failureReason.take(80),
                                        message = channelMsg,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                Log.d(TAG, "Update cancelled for $id (gen $generation)")
            } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                if (updateGenerations[id] == generation) {
                    Log.w(TAG, "Update timed out for $id")
                    updateState(id) {
                        it.copy(
                            updateState = ComponentUpdateState.UPDATE_FAILED,
                            error = "Update timed out (> ${UPDATE_TIMEOUT_MS / 1000}s)",
                            message = "Update timed out",
                        )
                    }
                }
            } catch (e: Exception) {
                if (updateGenerations[id] == generation) {
                    val errMsg = e.message?.take(80) ?: "Unknown update error"
                    Log.e(TAG, "Update failed for $id: $errMsg", e)
                    updateState(id) {
                        it.copy(
                            updateState = ComponentUpdateState.UPDATE_FAILED,
                            error = errMsg,
                            message = "Update failed: $errMsg",
                        )
                    }
                }
            } finally {
                if (updateGenerations[id] == generation) {
                    activeUpdateJobs.remove(id)
                }
            }
        }
        activeUpdateJobs[id] = job
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

        private const val KEY_YTDLP_VER = "ver_ytdlp"
        private const val KEY_UBLOCK_VER = "ver_ublock"
        private const val KEY_PUEMOS_VER = "ver_puemos"

        private const val CHECK_TIMEOUT_MS = 8_000L
        private const val UPDATE_TIMEOUT_MS = 90_000L
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val DEBOUNCE_INTERVAL_MS = 2_000L // 2 seconds

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
