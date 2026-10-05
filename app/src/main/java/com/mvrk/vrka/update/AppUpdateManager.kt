package com.mvrk.vrka.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mvrk.vrka.BuildConfig
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.worker.AppUpdateDownloadWorker
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
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    init {
        restoreStateAndObserveWorker()
    }

    private fun restoreStateAndObserveWorker() {
        val prefs = context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val savedState = prefs.getString(AppUpdateDownloadWorker.KEY_STATE, null)
        val savedFilePath = prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null)
        if (savedState == AppUpdateDownloadWorker.STATE_READY_TO_INSTALL && !savedFilePath.isNullOrBlank()) {
            val file = File(savedFilePath)
            if (file.exists() && file.length() > 0L) {
                _downloadState.value = AppUpdateDownloadState.ReadyToInstall(file)
            }
        }

        runCatching {
            val workManager = WorkManager.getInstance(context)
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(AppUpdateDownloadWorker.WORK_NAME).collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getFloat(AppUpdateDownloadWorker.KEY_PROGRESS, 0f)
                            val downloaded = workInfo.progress.getLong(AppUpdateDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                            val total = workInfo.progress.getLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, 0L)
                            _downloadState.value = AppUpdateDownloadState.Downloading(progress, downloaded, total)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val filePath = workInfo.outputData.getString(AppUpdateDownloadWorker.KEY_FILE_PATH)
                                ?: prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null)
                            if (filePath != null) {
                                val file = File(filePath)
                                if (file.exists() && file.length() > 0L) {
                                    _downloadState.value = AppUpdateDownloadState.ReadyToInstall(file)
                                }
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString(AppUpdateDownloadWorker.KEY_ERROR)
                                ?: prefs.getString(AppUpdateDownloadWorker.KEY_ERROR, null)
                                ?: "Download failed"
                            _downloadState.value = AppUpdateDownloadState.Error(error)
                        }
                        WorkInfo.State.CANCELLED -> {
                            _downloadState.value = AppUpdateDownloadState.Idle
                        }
                        else -> Unit
                    }
                }
            }
        }
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
                    if (shouldSkipAutomaticCheck(isManual, lastCheck, now)) {
                        Log.d(TAG, "Skipping background update check; within 24h gate")
                        return@launch
                    }
                }

                _checkState.value = AppUpdateCheckState.Checking

                val release = fetchLatestRelease()
                val now = System.currentTimeMillis()
                settingsRepository.setLastAppUpdateCheckTimestamp(now)

                val result = resolveCheckState(release, currentVersion, BuildConfig.VERSION_NAME)
                if (result is AppUpdateCheckState.UpdateAvailable) {
                    Log.i(TAG, "Update available: ${release?.version} (current: $currentVersion)")
                } else if (result is AppUpdateCheckState.UpToDate) {
                    Log.i(TAG, "App is up-to-date (version: $currentVersion)")
                }
                // Startup checks remain silent on invalid metadata; manual checks surface the error.
                _checkState.value = if (!isManual && result is AppUpdateCheckState.Error) {
                    AppUpdateCheckState.Idle
                } else result
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
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(AppUpdateDownloadWorker.WORK_NAME)
        }
    }

    fun resetState() {
        _checkState.value = AppUpdateCheckState.Idle
        _downloadState.value = AppUpdateDownloadState.Idle
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(AppUpdateDownloadWorker.WORK_NAME)
        }
    }

    fun downloadAndInstall(release: AppReleaseInfo) {
        if (!isValidReleaseForDownload(release)) {
            _downloadState.value = AppUpdateDownloadState.Error("Update release metadata failed security validation")
            return
        }
        if (_downloadState.value is AppUpdateDownloadState.Downloading) return

        _downloadState.value = AppUpdateDownloadState.Downloading(0f, 0L, release.apkSizeBytes)

        runCatching {
            val workManager = WorkManager.getInstance(context)
            val inputData = workDataOf(
                AppUpdateDownloadWorker.KEY_DOWNLOAD_URL to release.apkDownloadUrl,
                AppUpdateDownloadWorker.KEY_APK_NAME to release.apkFileName,
                AppUpdateDownloadWorker.KEY_EXPECTED_SIZE to release.apkSizeBytes,
                AppUpdateDownloadWorker.KEY_TARGET_VERSION to release.version.toString(),
            )
            val workRequest = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                .setInputData(inputData)
                .build()

            workManager.enqueueUniqueWork(
                AppUpdateDownloadWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue AppUpdateDownloadWorker: ${error.message}", error)
            _downloadState.value = AppUpdateDownloadState.Error(error.message ?: "Failed to start background download")
        }
    }

    fun installApk(file: File) {
        try {
            val updatesDirectory = File(context.cacheDir, "updates").canonicalFile
            val canonicalFile = file.canonicalFile
            if (canonicalFile.parentFile != updatesDirectory || !canonicalFile.isFile || canonicalFile.length() <= 0L) {
                _downloadState.value = AppUpdateDownloadState.Error("APK verification failed: update file is not in private update cache")
                return
            }

            // Verify package identity, canonical version metadata, and monotonic version code before handoff.
            val pm = context.packageManager
            val archiveInfo = packageArchiveInfo(pm, canonicalFile.absolutePath)

            if (archiveInfo == null) {
                _downloadState.value = AppUpdateDownloadState.Error("APK verification failed: corrupt or invalid package archive")
                return
            }

            val currentPkgInfo = packageInfo(pm, context.packageName)
            val metadataError = validateInstallMetadata(
                apkFileName = canonicalFile.name,
                apkPackageName = archiveInfo.packageName,
                apkVersionName = archiveInfo.versionName,
                apkVersionCode = packageVersionCode(archiveInfo),
                installedPackageName = currentPkgInfo.packageName,
                installedVersionName = currentPkgInfo.versionName,
                installedVersionCode = packageVersionCode(currentPkgInfo),
            )
            if (metadataError != null) {
                _downloadState.value = AppUpdateDownloadState.Error(metadataError)
                return
            }

            val currentSigners = packageSignerFingerprints(currentPkgInfo)
            val candidateSigners = packageSignerFingerprints(archiveInfo)
            if (!haveSameSignerSet(currentSigners, candidateSigners)) {
                _downloadState.value = AppUpdateDownloadState.Error("APK verification failed: signing certificate mismatch or unavailable signer metadata")
                return
            }

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

    private fun httpGet(urlString: String): String {
        var currentUrl = urlString
        var redirects = 0

        while (true) {
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
                        if (!canFollowRedirect(redirects)) throw IOException("Too many redirects: $redirects")
                        currentUrl = validateRedirectTarget(url, location).toExternalForm()
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
    }

    private suspend fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var redirects = 0

        while (true) {
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
                        if (!canFollowRedirect(redirects)) throw IOException("Too many redirects downloading APK")
                        currentUrl = validateRedirectTarget(url, location).toExternalForm()
                        redirects++
                    }
                    else -> throw IOException("Download failed with HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
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
        const val MAX_REDIRECTS = 5

        val ALLOWED_UPDATE_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "raw.githubusercontent.com",
        )

        private val CANONICAL_VERSION_PATTERN = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
        private val RELEASE_TAG_PATTERN = Regex("""^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
        val APK_NAME_PATTERN = Regex("""^VRKA-Android-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\.apk$""")

        fun shouldSkipAutomaticCheck(isManual: Boolean, lastCheckMillis: Long, nowMillis: Long): Boolean =
            !isManual && nowMillis - lastCheckMillis < TWENTY_FOUR_HOURS_MS

        fun resolveCheckState(
            release: AppReleaseInfo?,
            currentVersion: SemanticVersion,
            currentVersionName: String,
        ): AppUpdateCheckState = when {
            release == null -> AppUpdateCheckState.Error("Latest release metadata is invalid or has no canonical APK")
            release.version.isNewerThan(currentVersion) -> AppUpdateCheckState.UpdateAvailable(release)
            else -> AppUpdateCheckState.UpToDate(currentVersionName)
        }

        fun canFollowRedirect(redirectsFollowed: Int): Boolean = redirectsFollowed in 0 until MAX_REDIRECTS

        fun validateRedirectTarget(baseUrl: URL, location: String): URL =
            validateHttpsUrl(resolveRedirectUrl(baseUrl, location))

        fun isValidDownloadTarget(targetVersion: String, apkFileName: String): Boolean {
            val version = parseCanonicalVersion(targetVersion) ?: return false
            return apkFileName == canonicalApkName(version)
        }

        fun isValidReleaseForDownload(release: AppReleaseInfo): Boolean {
            val tagVersion = parseReleaseTag(release.tagName) ?: return false
            if (tagVersion != release.version || release.apkFileName != canonicalApkName(tagVersion)) return false
            return runCatching { validateHttpsUrl(release.apkDownloadUrl) }.isSuccess
        }

        fun validateInstallMetadata(
            apkFileName: String,
            apkPackageName: String?,
            apkVersionName: String?,
            apkVersionCode: Long,
            installedPackageName: String?,
            installedVersionName: String?,
            installedVersionCode: Long,
        ): String? {
            val filenameMatch = APK_NAME_PATTERN.matchEntire(apkFileName)
                ?: return "APK verification failed: non-canonical APK filename"
            val apkVersion = versionFromGroups(filenameMatch.groupValues)
                ?: return "APK verification failed: malformed APK version"
            if (apkPackageName.isNullOrBlank() || apkPackageName != installedPackageName) {
                return "APK verification failed: package name mismatch"
            }
            if (apkVersionName != apkVersion.toString()) {
                return "APK verification failed: version name does not match the canonical APK filename"
            }
            if (installedVersionName != null) {
                val installedVersion = SemanticVersion.parseOrNull(installedVersionName)
                if (installedVersion != null && !apkVersion.isNewerThan(installedVersion)) {
                    return "APK verification failed: downgrade or same-version installation rejected"
                }
            }
            if (apkVersionCode <= installedVersionCode) {
                return "APK verification failed: version code is not newer than the installed app"
            }
            return null
        }

        fun haveSameSignerSet(installedSigners: Set<String>, candidateSigners: Set<String>): Boolean =
            installedSigners.isNotEmpty() && candidateSigners.isNotEmpty() && installedSigners == candidateSigners

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
            val uri = try {
                url.toURI()
            } catch (e: Exception) {
                throw SecurityException("Malformed update URL: $urlString", e)
            }
            if (!uri.rawUserInfo.isNullOrEmpty() || (uri.port != -1 && uri.port != 443) || uri.rawFragment != null) {
                throw SecurityException("Unsafe URL authority or fragment rejected: $urlString")
            }
            val host = url.host.lowercase(java.util.Locale.ROOT)
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
            return try {
                parseReleaseJsonUnchecked(jsonString)
            } catch (_: Exception) {
                null
            }
        }

        private fun parseReleaseJsonUnchecked(jsonString: String): AppReleaseInfo? {
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
            val version = parseReleaseTag(tagName) ?: return null

            val name = json.optString("name", "").ifBlank { tagName }
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            val assets = json.optJSONArray("assets") ?: return null
            var chosenAsset: JSONObject? = null
            val expectedVersionApk = canonicalApkName(version)

            // APK asset selection must require the expected VRKA Android APK naming convention:
            // VRKA-Android-vX.Y.Z.apk. Reject unrelated APK assets and non-APK assets.
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName == expectedVersionApk) {
                    if (chosenAsset != null) return null
                    chosenAsset = asset
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

        private fun parseReleaseTag(tagName: String): SemanticVersion? {
            val match = RELEASE_TAG_PATTERN.matchEntire(tagName) ?: return null
            return versionFromGroups(match.groupValues)
        }

        private fun parseCanonicalVersion(version: String): SemanticVersion? {
            val match = CANONICAL_VERSION_PATTERN.matchEntire(version) ?: return null
            return versionFromGroups(match.groupValues)
        }

        private fun versionFromGroups(groups: List<String>): SemanticVersion? {
            if (groups.size < 4) return null
            val major = groups[1].toIntOrNull() ?: return null
            val minor = groups[2].toIntOrNull() ?: return null
            val patch = groups[3].toIntOrNull() ?: return null
            return SemanticVersion(major, minor, patch)
        }

        private fun canonicalApkName(version: SemanticVersion): String = "VRKA-Android-v$version.apk"

        private fun packageArchiveInfo(pm: PackageManager, path: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

        private fun packageInfo(pm: PackageManager, packageName: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

        private fun packageVersionCode(info: android.content.pm.PackageInfo): Long {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.longVersionCode
            @Suppress("DEPRECATION")
            return info.versionCode.toLong()
        }

        private fun packageSignerFingerprints(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.toSet()
        }
    }

    class RateLimitException(message: String) : IOException(message)
}
