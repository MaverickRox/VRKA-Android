package com.mvrk.vrka.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.mvrk.vrka.BuildConfig
import com.mvrk.vrka.SettingsRepository
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val apiEndpoint: String = DEFAULT_API_ENDPOINT,
) {
    private val _checkState = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
    val checkState: StateFlow<AppUpdateCheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    val downloadState: StateFlow<AppUpdateDownloadState> = _downloadState.asStateFlow()

    private var activeCheckJob: Job? = null
    private var activeDownloadJob: Job? = null

    val currentVersion: SemanticVersion by lazy {
        SemanticVersion.parseOrNull(BuildConfig.VERSION_NAME) ?: SemanticVersion(4, 5, 1)
    }

    fun checkForUpdate(isManual: Boolean = false) {
        if (activeCheckJob?.isActive == true) {
            Log.d(TAG, "Update check already in progress")
            return
        }

        activeCheckJob = scope.launch {
            try {
                if (!isManual) {
                    val lastCheck = settingsRepository.getLastAppUpdateCheckTimestamp()
                    val now = System.currentTimeMillis()
                    if (now - lastCheck < TWENTY_FOUR_HOURS_MS) {
                        Log.d(TAG, "Skipping background update check; within 24h gate")
                        return@launch
                    }
                }

                _checkState.value = AppUpdateCheckState.Checking

                val release = fetchLatestRelease()
                val now = System.currentTimeMillis()
                settingsRepository.setLastAppUpdateCheckTimestamp(now)

                if (release != null && release.version.isNewerThan(currentVersion)) {
                    Log.i(TAG, "Update available: ${release.version} (current: $currentVersion)")
                    _checkState.value = AppUpdateCheckState.UpdateAvailable(release)
                } else {
                    Log.i(TAG, "App is up-to-date (version: $currentVersion)")
                    _checkState.value = AppUpdateCheckState.UpToDate(BuildConfig.VERSION_NAME)
                }
            } catch (ce: CancellationException) {
                Log.d(TAG, "Update check cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                if (isManual) {
                    val message = when (e) {
                        is RateLimitException -> e.message ?: "GitHub API rate limit reached. Try again later."
                        is IOException -> "Network error checking for updates."
                        else -> e.message ?: "Unable to check for updates."
                    }
                    _checkState.value = AppUpdateCheckState.Error(message)
                } else {
                    // Silent failure on startup/background
                    _checkState.value = AppUpdateCheckState.Idle
                }
            }
        }
    }

    fun dismissUpdate() {
        _checkState.value = AppUpdateCheckState.Idle
        _downloadState.value = AppUpdateDownloadState.Idle
    }

    fun resetState() {
        _checkState.value = AppUpdateCheckState.Idle
        _downloadState.value = AppUpdateDownloadState.Idle
    }

    fun downloadAndInstall(release: AppReleaseInfo) {
        if (activeDownloadJob?.isActive == true) return

        activeDownloadJob = scope.launch {
            _downloadState.value = AppUpdateDownloadState.Downloading(0f, 0L, release.apkSizeBytes)
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val targetFile = File(updatesDir, release.apkFileName)
            try {
                downloadFileWithProgress(release.apkDownloadUrl, targetFile) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    _downloadState.value = AppUpdateDownloadState.Downloading(progress, downloaded, total)
                }

                if (!targetFile.exists() || targetFile.length() == 0L) {
                    throw IOException("Downloaded APK file is empty or missing")
                }

                _downloadState.value = AppUpdateDownloadState.ReadyToInstall(targetFile)
                installApk(targetFile)
            } catch (ce: CancellationException) {
                Log.d(TAG, "Download cancelled")
                _downloadState.value = AppUpdateDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update: ${e.message}", e)
                _downloadState.value = AppUpdateDownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            _downloadState.value = AppUpdateDownloadState.Installing
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            _downloadState.value = AppUpdateDownloadState.Error("Failed to launch package installer: ${e.message}")
        }
    }

    private suspend fun fetchLatestRelease(): AppReleaseInfo? = withContext(Dispatchers.IO) {
        val jsonStr = httpGet(apiEndpoint)
        parseReleaseJson(jsonStr)
    }

    private fun httpGet(urlString: String, maxRedirects: Int = 5): String {
        var currentUrl = urlString
        var redirects = 0

        while (redirects < maxRedirects) {
            val url = validateHttpsUrl(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        return conn.inputStream.bufferedReader().use { it.readText() }
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("HTTP redirect $code without Location header")
                        val nextUrl = resolveRedirectUrl(url, location)
                        validateHttpsUrl(nextUrl)
                        currentUrl = nextUrl
                        redirects++
                    }
                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        val reset = conn.getHeaderField("X-RateLimit-Reset")
                        throw RateLimitException("GitHub API rate limit exceeded. Reset: $reset")
                    }
                    else -> throw IOException("GitHub API returned HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects: $redirects")
    }

    private suspend fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = validateHttpsUrl(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS

            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val totalBytes = conn.contentLengthLong
                        var downloadedBytes = 0L
                        val tempFile = File("${targetFile.absolutePath}.tmp")

                        conn.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var read: Int
                                var lastUpdate = 0L

                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate > 100 || downloadedBytes == totalBytes) {
                                        lastUpdate = now
                                        onProgress(downloadedBytes, totalBytes)
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (tempFile.renameTo(targetFile)) {
                            onProgress(downloadedBytes, totalBytes)
                            return@withContext
                        } else {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                            onProgress(downloadedBytes, totalBytes)
                            return@withContext
                        }
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("HTTP redirect $code without Location header")
                        val nextUrl = resolveRedirectUrl(url, location)
                        validateHttpsUrl(nextUrl)
                        currentUrl = nextUrl
                        redirects++
                    }
                    else -> throw IOException("Download failed with HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects downloading APK")
    }

    companion object {
        private const val TAG = "VRKA-AppUpdater"
        private const val USER_AGENT = "VRKA-Android-AppUpdater"
        private const val DEFAULT_API_ENDPOINT =
            "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest"
        const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L // 86,400,000 ms
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 6000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 30000
        private const val BUFFER_SIZE = 8192

        val ALLOWED_UPDATE_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "raw.githubusercontent.com",
        )

        val APK_NAME_PATTERN = Regex("""^VRKA-Android-v\d+\.\d+\.\d+\.apk$""", RegexOption.IGNORE_CASE)

        fun isApprovedHost(host: String): Boolean {
            val h = host.lowercase()
            return h in ALLOWED_UPDATE_HOSTS || (h.endsWith(".githubusercontent.com") && !h.startsWith("."))
        }

        fun validateHttpsUrl(urlString: String): URL {
            val url = try {
                URL(urlString)
            } catch (e: Exception) {
                throw SecurityException("Malformed update URL: $urlString", e)
            }
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw SecurityException("Insecure protocol '${url.protocol}' rejected (HTTPS required): $urlString")
            }
            val host = url.host.lowercase()
            if (!isApprovedHost(host)) {
                throw SecurityException("Host '$host' is not an approved GitHub update host: $urlString")
            }
            return url
        }

        fun resolveRedirectUrl(baseUrl: URL, location: String): String {
            val trimmed = location.trim()
            if (trimmed.isEmpty()) throw IOException("Empty redirect Location header")
            return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                URL(baseUrl, trimmed).toString()
            }
        }

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context, settingsRepository: SettingsRepository): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext, settingsRepository).also {
                    instance = it
                }
            }
        }

        fun parseReleaseJson(jsonString: String): AppReleaseInfo? {
            val trimmed = jsonString.trim()
            val json = if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                if (array.length() == 0) return null
                var found: JSONObject? = null
                for (i in 0 until array.length()) {
                    val candidate = array.getJSONObject(i)
                    if (!candidate.optBoolean("draft", false) &&
                        !candidate.optBoolean("prerelease", false)
                    ) {
                        found = candidate
                        break
                    }
                }
                found ?: return null
            } else {
                JSONObject(trimmed)
            }

            if (json.optBoolean("draft", false)) return null
            if (json.optBoolean("prerelease", false)) return null

            val tagName = json.optString("tag_name", "").trim()
            val version = SemanticVersion.parseOrNull(tagName) ?: return null

            val name = json.optString("name", "").ifBlank { tagName }
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            val assets = json.optJSONArray("assets") ?: return null
            var chosenAsset: JSONObject? = null
            val expectedVersionApk = "VRKA-Android-v$version.apk"

            // APK asset selection must require the expected VRKA Android APK naming convention:
            // VRKA-Android-vX.Y.Z.apk. Reject unrelated APK assets and non-APK assets.
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (APK_NAME_PATTERN.matches(assetName)) {
                    if (assetName.equals(expectedVersionApk, ignoreCase = true)) {
                        chosenAsset = asset
                        break
                    } else if (chosenAsset == null) {
                        chosenAsset = asset
                    }
                }
            }

            val asset = chosenAsset ?: return null
            val apkDownloadUrl = asset.optString("browser_download_url", "")
            if (!apkDownloadUrl.startsWith("https://", ignoreCase = true)) return null
            try {
                validateHttpsUrl(apkDownloadUrl)
            } catch (_: Exception) {
                return null
            }

            val apkFileName = asset.optString("name", expectedVersionApk)
            val apkSizeBytes = asset.optLong("size", 0L)

            return AppReleaseInfo(
                tagName = tagName,
                version = version,
                name = name,
                body = body,
                publishedAt = publishedAt,
                apkDownloadUrl = apkDownloadUrl,
                apkFileName = apkFileName,
                apkSizeBytes = apkSizeBytes,
            )
        }
    }

    class RateLimitException(message: String) : IOException(message)
}
