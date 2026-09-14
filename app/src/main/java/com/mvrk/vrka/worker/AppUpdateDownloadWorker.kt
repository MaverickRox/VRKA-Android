package com.mvrk.vrka.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mvrk.vrka.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

class AppUpdateDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing download URL"))
        val apkFileName = inputData.getString(KEY_APK_NAME)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing APK file name"))
        val targetVersion = inputData.getString(KEY_TARGET_VERSION) ?: ""
        val expectedSizeBytes = inputData.getLong(KEY_EXPECTED_SIZE, 0L)

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_STATE, STATE_DOWNLOADING)
            .putString(KEY_VERSION, targetVersion)
            .apply()

        val updatesDir = File(applicationContext.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val targetFile = File(updatesDir, apkFileName)
        val tempFile = File(updatesDir, "$apkFileName.download.tmp")

        try {
            Log.i(TAG, "Starting persistent APK download from $downloadUrl to ${targetFile.absolutePath}...")
            var currentUrl = downloadUrl
            var redirects = 0
            val maxRedirects = 5
            var success = false

            while (redirects < maxRedirects && !success) {
                val url = AppUpdateManager.validateHttpsUrl(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VRKA-Android-AppUpdater")
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000

                try {
                    when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSizeBytes
                            var downloadedBytes = 0L

                            conn.inputStream.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    var lastUpdate = 0L

                                    while (input.read(buffer).also { read = it } != -1) {
                                        if (isStopped) {
                                            tempFile.delete()
                                            prefs.edit().putString(KEY_STATE, STATE_CANCELLED).apply()
                                            return@withContext Result.failure(workDataOf(KEY_ERROR to "Download cancelled"))
                                        }
                                        output.write(buffer, 0, read)
                                        downloadedBytes += read

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate > 250 || downloadedBytes == totalBytes) {
                                            lastUpdate = now
                                            val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                                            setProgress(
                                                workDataOf(
                                                    KEY_PROGRESS to progress,
                                                    KEY_DOWNLOADED_BYTES to downloadedBytes,
                                                    KEY_TOTAL_BYTES to totalBytes,
                                                )
                                            )
                                        }
                                    }
                                    output.flush()
                                }
                            }

                            if (!tempFile.exists() || tempFile.length() == 0L) {
                                throw IOException("Downloaded file is empty")
                            }

                            if (targetFile.exists()) targetFile.delete()
                            if (!tempFile.renameTo(targetFile)) {
                                tempFile.copyTo(targetFile, overwrite = true)
                                tempFile.delete()
                            }

                            success = true
                        }
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307, 308 -> {
                            val location = conn.getHeaderField("Location")
                                ?: throw IOException("HTTP redirect $code without Location header")
                            val nextUrl = AppUpdateManager.resolveRedirectUrl(url, location)
                            AppUpdateManager.validateHttpsUrl(nextUrl)
                            currentUrl = nextUrl
                            redirects++
                        }
                        else -> throw IOException("HTTP error $code from $currentUrl")
                    }
                } finally {
                    conn.disconnect()
                }
            }

            if (!success || !targetFile.exists() || targetFile.length() == 0L) {
                throw IOException("APK download failed or exceeded redirects")
            }

            Log.i(TAG, "APK download completed: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            prefs.edit()
                .putString(KEY_STATE, STATE_READY_TO_INSTALL)
                .putString(KEY_FILE_PATH, targetFile.absolutePath)
                .putString(KEY_VERSION, targetVersion)
                .apply()

            Result.success(
                workDataOf(
                    KEY_FILE_PATH to targetFile.absolutePath,
                    KEY_VERSION to targetVersion,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "AppUpdateDownloadWorker failed: ${e.message}", e)
            tempFile.delete()
            prefs.edit()
                .putString(KEY_STATE, STATE_FAILED)
                .putString(KEY_ERROR, e.message ?: "Download failed")
                .apply()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    companion object {
        private const val TAG = "VRKA-AppUpdateWorker"
        const val WORK_NAME = "VRKA_APP_UPDATE_WORK"
        const val PREFS_NAME = "vrka_app_update_prefs"

        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_APK_NAME = "apk_name"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_TARGET_VERSION = "target_version"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_VERSION = "version"
        const val KEY_ERROR = "error"
        const val KEY_STATE = "state"

        const val STATE_IDLE = "IDLE"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_READY_TO_INSTALL = "READY_TO_INSTALL"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"
    }
}
