package com.mvrk.vrka.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
        restoreSavedUpdateState(prefs, forceShowDialog = true)

        runCatching {
            val workManager = WorkManager.getInstance(context)
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(AppUpdateDownloadWorker.WORK_NAME).collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED,
                        WorkInfo.State.RUNNING,
                        -> {
                            val release = loadStoredRelease(prefs)
                            if (release != null && release.version.isNewerThan(currentVersion)) {
                                _checkState.value = AppUpdateCheckState.UpdateAvailable(release)
                            }
                            val progress = workInfo.progress.getFloat(AppUpdateDownloadWorker.KEY_PROGRESS, 0f)
                            val downloaded = workInfo.progress.getLong(AppUpdateDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                            val total = workInfo.progress.getLong(
                                AppUpdateDownloadWorker.KEY_TOTAL_BYTES,
                                prefs.getLong(AppUpdateDownloadWorker.KEY_EXPECTED_SIZE, 0L),
                            )
                            _downloadState.value = AppUpdateDownloadState.Downloading(progress, downloaded, total)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val filePath = workInfo.outputData.getString(AppUpdateDownloadWorker.KEY_FILE_PATH)
                                ?: prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null)
                            val release = loadStoredRelease(prefs)
                            if (release == null || !release.version.isNewerThan(currentVersion)) {
                                clearInstalledUpdate(prefs, filePath)
                                return@collect
                            }
                            if (filePath != null) {
                                val file = File(filePath)
                                val error = verifyDownloadedApk(
                                    context,
                                    file,
                                    release.version.toString(),
                                    release.apkSizeBytes,
                                    release.apkSha256,
                                )
                                if (AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                                        persistedState = prefs.getString(AppUpdateDownloadWorker.KEY_STATE, null),
                                        fileExists = file.isFile,
                                        apkVerified = error == null,
                                        targetVersion = release.version,
                                        installedVersion = currentVersion,
                                    )
                                ) {
                                    _checkState.value = AppUpdateCheckState.UpdateAvailable(release)
                                    _downloadState.value = AppUpdateDownloadState.ReadyToInstall(file)
                                } else {
                                    deletePrivateUpdateFile(file)
                                    _downloadState.value = AppUpdateDownloadState.Error(error ?: "APK verification failed")
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
                            restoreSavedUpdateState(prefs, forceShowDialog = false)
                            if (_downloadState.value !is AppUpdateDownloadState.ReadyToInstall) {
                                _downloadState.value = AppUpdateDownloadState.Idle
                                if (prefs.getString(AppUpdateDownloadWorker.KEY_STATE, null) ==
                                    AppUpdateDownloadWorker.STATE_DOWNLOADING
                                ) {
                                    prefs.edit().putString(AppUpdateDownloadWorker.KEY_STATE, AppUpdateDownloadWorker.STATE_IDLE).commit()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reopens the persisted update-ready state when the completion notification is tapped. */
    fun restoreReadyUpdateFlow() {
        val prefs = context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedUpdateState(prefs, forceShowDialog = true)
    }

    private fun restoreSavedUpdateState(
        prefs: android.content.SharedPreferences,
        forceShowDialog: Boolean,
    ) {
        val release = loadStoredRelease(prefs) ?: return
        if (!release.version.isNewerThan(currentVersion)) {
            clearInstalledUpdate(prefs, prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null))
            return
        }
        if (forceShowDialog) _checkState.value = AppUpdateCheckState.UpdateAvailable(release)

        when (prefs.getString(AppUpdateDownloadWorker.KEY_STATE, null)) {
            AppUpdateDownloadWorker.STATE_READY_TO_INSTALL -> {
                val savedPath = prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null) ?: return
                val file = File(savedPath)
                val error = verifyDownloadedApk(
                    context,
                    file,
                    release.version.toString(),
                    release.apkSizeBytes,
                    release.apkSha256,
                )
                if (AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                        persistedState = prefs.getString(AppUpdateDownloadWorker.KEY_STATE, null),
                        fileExists = file.isFile,
                        apkVerified = error == null,
                        targetVersion = release.version,
                        installedVersion = currentVersion,
                    )
                ) {
                    _downloadState.value = AppUpdateDownloadState.ReadyToInstall(file)
                } else {
                    deletePrivateUpdateFile(file)
                    prefs.edit()
                        .putString(AppUpdateDownloadWorker.KEY_STATE, AppUpdateDownloadWorker.STATE_FAILED)
                        .putString(AppUpdateDownloadWorker.KEY_ERROR, error)
                        .commit()
                    _downloadState.value = AppUpdateDownloadState.Error(error ?: "APK verification failed")
                }
            }

            AppUpdateDownloadWorker.STATE_DOWNLOADING -> {
                _downloadState.value = AppUpdateDownloadState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSizeBytes,
                )
            }

            AppUpdateDownloadWorker.STATE_FAILED -> {
                _downloadState.value = AppUpdateDownloadState.Error(
                    prefs.getString(AppUpdateDownloadWorker.KEY_ERROR, null) ?: "Download failed",
                )
            }
        }
    }

    private fun loadStoredRelease(prefs: android.content.SharedPreferences): AppReleaseInfo? {
        val version = SemanticVersion.parseOrNull(prefs.getString(AppUpdateDownloadWorker.KEY_TARGET_VERSION, null))
            ?: return null
        val release = AppReleaseInfo(
            tagName = prefs.getString(AppUpdateDownloadWorker.KEY_RELEASE_TAG, null) ?: "v$version",
            version = version,
            name = prefs.getString(AppUpdateDownloadWorker.KEY_RELEASE_NAME, null) ?: "VRKA Android $version",
            body = prefs.getString(AppUpdateDownloadWorker.KEY_RELEASE_BODY, null).orEmpty(),
            publishedAt = prefs.getString(AppUpdateDownloadWorker.KEY_RELEASE_PUBLISHED_AT, null).orEmpty(),
            apkDownloadUrl = prefs.getString(AppUpdateDownloadWorker.KEY_DOWNLOAD_URL, null).orEmpty(),
            apkFileName = prefs.getString(AppUpdateDownloadWorker.KEY_APK_NAME, null).orEmpty(),
            apkSizeBytes = prefs.getLong(AppUpdateDownloadWorker.KEY_EXPECTED_SIZE, 0L),
            apkSha256 = prefs.getString(AppUpdateDownloadWorker.KEY_EXPECTED_SHA256, null).orEmpty(),
        )
        return release.takeIf(::isValidReleaseForDownload)
    }

    private fun clearInstalledUpdate(prefs: android.content.SharedPreferences, savedPath: String?) {
        savedPath?.let { deletePrivateUpdateFile(File(it)) }
        prefs.edit()
            .putString(AppUpdateDownloadWorker.KEY_STATE, AppUpdateDownloadWorker.STATE_IDLE)
            .remove(AppUpdateDownloadWorker.KEY_FILE_PATH)
            .remove(AppUpdateDownloadWorker.KEY_ERROR)
            .remove(AppUpdateDownloadWorker.KEY_VERSION)
            .remove(AppUpdateDownloadWorker.KEY_TARGET_VERSION)
            .remove(AppUpdateDownloadWorker.KEY_RELEASE_TAG)
            .remove(AppUpdateDownloadWorker.KEY_RELEASE_NAME)
            .remove(AppUpdateDownloadWorker.KEY_RELEASE_BODY)
            .remove(AppUpdateDownloadWorker.KEY_RELEASE_PUBLISHED_AT)
            .remove(AppUpdateDownloadWorker.KEY_DOWNLOAD_URL)
            .remove(AppUpdateDownloadWorker.KEY_APK_NAME)
            .remove(AppUpdateDownloadWorker.KEY_EXPECTED_SIZE)
            .remove(AppUpdateDownloadWorker.KEY_EXPECTED_SHA256)
            .commit()
        _downloadState.value = AppUpdateDownloadState.Idle
        if (checkState.value is AppUpdateCheckState.UpdateAvailable) {
            _checkState.value = AppUpdateCheckState.Idle
        }
    }

    private fun deletePrivateUpdateFile(file: File) {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        val privateDirectories = listOf(
            File(context.filesDir, "updates"),
            File(context.cacheDir, "updates"),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (canonical.parentFile?.let { it in privateDirectories } == true) canonical.delete()
    }

    fun checkForUpdate(isManual: Boolean = false) {
        if (activeCheckJob?.isActive == true) {
            Log.d(TAG, "Update check already in progress")
            return
        }

        activeCheckJob = scope.launch {
            try {
                val keepReadyUpdateVisible = !isManual &&
                    _downloadState.value is AppUpdateDownloadState.ReadyToInstall &&
                    _checkState.value is AppUpdateCheckState.UpdateAvailable
                if (!isManual) {
                    val lastCheck = settingsRepository.getLastAppUpdateCheckTimestamp()
                    val now = System.currentTimeMillis()
                    if (shouldSkipAutomaticCheck(isManual, lastCheck, now)) {
                        Log.d(TAG, "Skipping background update check; within 24h gate")
                        return@launch
                    }
                }

                if (!keepReadyUpdateVisible) _checkState.value = AppUpdateCheckState.Checking

                val release = fetchLatestRelease()
                val now = System.currentTimeMillis()
                settingsRepository.setLastAppUpdateCheckTimestamp(now)

                val result = resolveCheckState(release, currentVersion, BuildConfig.VERSION_NAME)
                if (keepReadyUpdateVisible && release != null) {
                    val prefs = context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE)
                    val stagedVersion = prefs.getString(AppUpdateDownloadWorker.KEY_TARGET_VERSION, null)
                    if (stagedVersion != release.version.toString()) {
                        clearInstalledUpdate(prefs, prefs.getString(AppUpdateDownloadWorker.KEY_FILE_PATH, null))
                    }
                }
                if (result is AppUpdateCheckState.UpdateAvailable) {
                    Log.i(TAG, "Update available: ${release?.version} (current: $currentVersion)")
                } else if (result is AppUpdateCheckState.UpToDate) {
                    Log.i(TAG, "App is up-to-date (version: $currentVersion)")
                }
                // Startup checks remain silent on invalid metadata; manual checks surface the error.
                if (!(keepReadyUpdateVisible && (result is AppUpdateCheckState.Error || result is AppUpdateCheckState.UpToDate))) {
                    _checkState.value = if (!isManual && result is AppUpdateCheckState.Error) {
                        AppUpdateCheckState.Idle
                    } else result
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
                } else if (!(_downloadState.value is AppUpdateDownloadState.ReadyToInstall &&
                        _checkState.value is AppUpdateCheckState.UpdateAvailable)
                ) {
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
            val prefs = context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.edit()
                .putString(AppUpdateDownloadWorker.KEY_STATE, AppUpdateDownloadWorker.STATE_DOWNLOADING)
                .putString(AppUpdateDownloadWorker.KEY_VERSION, release.version.toString())
                .putString(AppUpdateDownloadWorker.KEY_TARGET_VERSION, release.version.toString())
                .putString(AppUpdateDownloadWorker.KEY_RELEASE_TAG, release.tagName)
                .putString(AppUpdateDownloadWorker.KEY_RELEASE_NAME, release.name)
                .putString(AppUpdateDownloadWorker.KEY_RELEASE_BODY, release.body)
                .putString(AppUpdateDownloadWorker.KEY_RELEASE_PUBLISHED_AT, release.publishedAt)
                .putString(AppUpdateDownloadWorker.KEY_DOWNLOAD_URL, release.apkDownloadUrl)
                .putString(AppUpdateDownloadWorker.KEY_APK_NAME, release.apkFileName)
                .putLong(AppUpdateDownloadWorker.KEY_EXPECTED_SIZE, release.apkSizeBytes)
                .putString(AppUpdateDownloadWorker.KEY_EXPECTED_SHA256, release.apkSha256)
                .commit()
            if (!saved) throw IOException("Unable to save application update metadata")

            val workManager = WorkManager.getInstance(context)

            workManager.enqueueUniqueWork(
                AppUpdateDownloadWorker.WORK_NAME,
                AppUpdateDownloadPolicy.UNIQUE_WORK_POLICY,
                AppUpdateDownloadPolicy.createWorkRequest(release),
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue AppUpdateDownloadWorker: ${error.message}", error)
            context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(AppUpdateDownloadWorker.KEY_STATE, AppUpdateDownloadWorker.STATE_FAILED)
                .putString(AppUpdateDownloadWorker.KEY_ERROR, error.message ?: "Failed to start update download")
                .commit()
            _downloadState.value = AppUpdateDownloadState.Error(error.message ?: "Failed to start background download")
        }
    }

    fun installApk(file: File) {
        try {
            val canonicalFile = file.canonicalFile
            val prefs = context.getSharedPreferences(AppUpdateDownloadWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val expectedVersion = prefs.getString(AppUpdateDownloadWorker.KEY_TARGET_VERSION, null)
                ?: SemanticVersion.parseOrNull(canonicalFile.name.substringAfter("-v").removeSuffix(".apk"))?.toString().orEmpty()
            val verificationError = verifyDownloadedApk(
                context = context,
                file = canonicalFile,
                targetVersion = expectedVersion,
                expectedSizeBytes = prefs.getLong(AppUpdateDownloadWorker.KEY_EXPECTED_SIZE, canonicalFile.length()),
                expectedSha256 = prefs.getString(AppUpdateDownloadWorker.KEY_EXPECTED_SHA256, null).orEmpty(),
            )
            if (verificationError != null) {
                _downloadState.value = AppUpdateDownloadState.Error(verificationError)
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

            context.startActivity(installIntent)
            _downloadState.value = AppUpdateDownloadState.ReadyToInstall(canonicalFile)
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
            if (release.apkSizeBytes <= 0L || AppUpdateDownloadPolicy.normalizeGitHubSha256(release.apkSha256) == null) return false
            return runCatching { validateHttpsUrl(release.apkDownloadUrl) }.isSuccess
        }

        /** Verifies the staged bytes, package/version metadata and signing identity before ReadyToInstall or installer handoff. */
        fun verifyDownloadedApk(
            context: Context,
            file: File,
            targetVersion: String,
            expectedSizeBytes: Long,
            expectedSha256: String,
        ): String? {
            val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
                ?: return "APK verification failed: update file path is invalid"
            val privateDirectories = listOf(
                File(context.filesDir, "updates"),
                File(context.cacheDir, "updates"),
            ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            if (canonicalFile.parentFile?.let { it in privateDirectories } != true || !canonicalFile.isFile) {
                return "APK verification failed: update file is outside private staging"
            }

            val expectedVersionValue = parseCanonicalVersion(targetVersion)
                ?: return "APK verification failed: release version is not canonical"
            if (!isValidDownloadTarget(targetVersion, canonicalFile.name)) {
                return "APK verification failed: APK filename does not match the expected release"
            }
            val expectedDigest = AppUpdateDownloadPolicy.normalizeGitHubSha256(expectedSha256)
                ?: return "APK verification failed: expected checksum is invalid"
            if (expectedSizeBytes <= 0L || canonicalFile.length() != expectedSizeBytes) {
                return "APK verification failed: downloaded APK size is incomplete or unexpected"
            }
            val actualDigest = runCatching { AppUpdateDownloadPolicy.sha256(canonicalFile) }.getOrNull()
                ?: return "APK verification failed: unable to read staged APK"
            if (actualDigest != expectedDigest) {
                return "APK verification failed: downloaded APK checksum does not match release metadata"
            }

            return try {
                val pm = context.packageManager
                val archiveInfo = packageArchiveInfo(pm, canonicalFile.absolutePath)
                    ?: return "APK verification failed: corrupt or invalid package archive"
                val installedInfo = packageInfo(pm, context.packageName)
                val metadataError = validateInstallMetadata(
                    apkFileName = canonicalFile.name,
                    apkPackageName = archiveInfo.packageName,
                    apkVersionName = archiveInfo.versionName,
                    apkVersionCode = packageVersionCode(archiveInfo),
                    installedPackageName = installedInfo.packageName,
                    installedVersionName = installedInfo.versionName,
                    installedVersionCode = packageVersionCode(installedInfo),
                )
                if (metadataError != null) return metadataError
                if (archiveInfo.versionName != expectedVersionValue.toString()) {
                    return "APK verification failed: package version does not match the selected release"
                }
                if (!haveSameSignerSet(
                        packageSignerFingerprints(installedInfo),
                        packageSignerFingerprints(archiveInfo),
                    )
                ) {
                    return "APK verification failed: signing certificate mismatch or unavailable signer metadata"
                }
                null
            } catch (error: Exception) {
                "APK verification failed: ${error.message ?: "package metadata could not be read"}"
            }
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
            val apkSha256 = AppUpdateDownloadPolicy.normalizeGitHubSha256(asset.optString("digest", ""))
                ?: return null
            if (apkSizeBytes <= 0L) return null

            return AppReleaseInfo(
                tagName = tagName,
                version = version,
                name = name,
                body = body,
                publishedAt = publishedAt,
                apkDownloadUrl = apkDownloadUrl,
                apkFileName = apkFileName,
                apkSizeBytes = apkSizeBytes,
                apkSha256 = apkSha256,
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
