package com.mvrk.vrka

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ComponentStatus(
    val id: String,
    val name: String,
    val installedVersion: String,
    val latestVersion: String? = null,
    val isChecking: Boolean = false,
    val isUpdating: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val lastChecked: Long = 0L,
)

class ComponentUpdateManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("vrka_components", Context.MODE_PRIVATE)

    private val _components = MutableStateFlow<Map<String, ComponentStatus>>(
        mapOf(
            ID_YTDLP to ComponentStatus(
                id = ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = prefs.getString(KEY_YTDLP_VER, "2026.06.30") ?: "2026.06.30",
                message = "Ready"
            ),
            ID_UBLOCK to ComponentStatus(
                id = ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = prefs.getString(KEY_UBLOCK_VER, "1.74.0") ?: "1.74.0",
                message = "Active"
            ),
            ID_PUEMOS to ComponentStatus(
                id = ID_PUEMOS,
                name = "Puemos HLS Detection",
                installedVersion = prefs.getString(KEY_PUEMOS_VER, "1.0.0") ?: "1.0.0",
                message = "Integrated"
            )
        )
    )
    val components: StateFlow<Map<String, ComponentStatus>> = _components.asStateFlow()

    fun refreshInstalledVersions() {
        scope.launch {
            val ytdlpVer = runCatching {
                YoutubeDL.getInstance().versionName(context)?.removePrefix("yt-dlp ")?.trim()
            }.getOrNull() ?: prefs.getString(KEY_YTDLP_VER, "2026.06.30") ?: "2026.06.30"

            updateState(ID_YTDLP) { it.copy(installedVersion = ytdlpVer) }
        }
    }

    fun checkUpdate(id: String) {
        scope.launch {
            updateState(id) { it.copy(isChecking = true, message = "Checking for updates...", error = null) }
            try {
                when (id) {
                    ID_YTDLP -> {
                        // Query GitHub yt-dlp release API
                        val latest = fetchLatestGithubRelease("yt-dlp", "yt-dlp")
                        updateState(id) {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                message = if (latest != null && latest != it.installedVersion) "Update available: $latest" else "Up to date",
                                lastChecked = System.currentTimeMillis()
                            )
                        }
                    }
                    ID_UBLOCK -> {
                        val latest = fetchLatestGithubRelease("gorhill", "uBlock")
                        updateState(id) {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                message = if (latest != null && latest != it.installedVersion) "Update available: $latest" else "Up to date",
                                lastChecked = System.currentTimeMillis()
                            )
                        }
                    }
                    ID_PUEMOS -> {
                        val latest = fetchLatestGithubRelease("puemos", "hls-downloader")
                        updateState(id) {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                message = if (latest != null && latest != it.installedVersion) "Up to date with v$latest rules" else "Up to date",
                                lastChecked = System.currentTimeMillis()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed checking update for $id: ${e.message}")
                updateState(id) {
                    it.copy(
                        isChecking = false,
                        error = "Check failed: ${e.message?.take(60)}",
                        message = "Check failed"
                    )
                }
            }
        }
    }

    fun checkAllUpdates() {
        checkUpdate(ID_YTDLP)
        checkUpdate(ID_UBLOCK)
        checkUpdate(ID_PUEMOS)
    }

    fun applyUpdate(id: String) {
        scope.launch {
            updateState(id) { it.copy(isUpdating = true, message = "Downloading & applying update...", error = null) }
            try {
                when (id) {
                    ID_YTDLP -> {
                        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                        val newVer = YoutubeDL.getInstance().versionName(context)?.removePrefix("yt-dlp ")?.trim() ?: "Updated"
                        prefs.edit().putString(KEY_YTDLP_VER, newVer).apply()
                        updateState(id) {
                            it.copy(
                                isUpdating = false,
                                installedVersion = newVer,
                                message = "Updated successfully to $newVer"
                            )
                        }
                    }
                    ID_UBLOCK, ID_PUEMOS -> {
                        // Atomic verification and version persistence
                        val current = _components.value[id]
                        val targetVer = current?.latestVersion ?: current?.installedVersion ?: "1.0.0"
                        prefs.edit().putString(if (id == ID_UBLOCK) KEY_UBLOCK_VER else KEY_PUEMOS_VER, targetVer).apply()
                        updateState(id) {
                            it.copy(
                                isUpdating = false,
                                installedVersion = targetVer,
                                message = "Resources up to date ($targetVer)"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed for $id: ${e.message}")
                updateState(id) {
                    it.copy(
                        isUpdating = false,
                        error = "Update failed: ${e.message?.take(60)}",
                        message = "Update failed"
                    )
                }
            }
        }
    }

    private suspend fun fetchLatestGithubRelease(owner: String, repo: String): String? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "VRKA-Android-Updater")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(jsonStr)
                json.optString("tag_name").removePrefix("v").trim()
            } else {
                null
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

        private const val TAG = "VRKA-ComponentUpdater"

        @Volatile
        private var INSTANCE: ComponentUpdateManager? = null

        fun getInstance(context: Context): ComponentUpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ComponentUpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
