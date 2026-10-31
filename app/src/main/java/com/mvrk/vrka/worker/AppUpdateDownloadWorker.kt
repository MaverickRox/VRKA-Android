package com.mvrk.vrka.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mvrk.vrka.update.AppUpdateDownloadPolicy
import com.mvrk.vrka.update.AppUpdateManager
import com.mvrk.vrka.update.AppUpdateNotificationFactory
import com.mvrk.vrka.update.AppUpdateDownloadPolicy.PayloadValidation
import com.mvrk.vrka.update.AppUpdateHttpException
import com.mvrk.vrka.update.PermanentAppUpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AppUpdateDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return@withContext failure("Missing download URL")
        val apkFileName = inputData.getString(KEY_APK_NAME)
            ?: return@withContext failure("Missing APK file name")
        val targetVersion = inputData.getString(KEY_TARGET_VERSION).orEmpty()
        val expectedSizeBytes = inputData.getLong(KEY_EXPECTED_SIZE, 0L)
        val expectedSha256 = AppUpdateDownloadPolicy.normalizeGitHubSha256(
            inputData.getString(KEY_EXPECTED_SHA256),
        ) ?: return@withContext failure("Missing or invalid APK checksum")
        val expectedCertSha256 = inputData.getString(KEY_EXPECTED_CERT_SHA256).orEmpty()
        val targetVersionCode = inputData.getLong(KEY_TARGET_VERSION_CODE, 0L)
        val metadataRevision = inputData.getLong(KEY_METADATA_REVISION, 0L)

        if (!AppUpdateManager.isValidDownloadTarget(targetVersion, apkFileName)) {
            return@withContext failure("Invalid update version or non-canonical APK filename")
        }
        if (expectedSizeBytes <= 0L) return@withContext failure("Invalid APK size metadata")

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updatesDirectory = File(applicationContext.filesDir, "updates").canonicalFile
        val targetFile = File(updatesDirectory, apkFileName).canonicalFile
        val tempFile = File(updatesDirectory, "$apkFileName.download.tmp")
        val fallbackStageFile = File(updatesDirectory, "$apkFileName.stage.tmp")

        try {
            if (targetFile.parentFile != updatesDirectory) {
                throw PermanentAppUpdateException("APK target escaped the private update directory")
            }
            if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
                throw IOException("Unable to create the private update directory")
            }

            AppUpdateManager.validateHttpsUrl(downloadUrl)
            runCatching {
                setForeground(AppUpdateNotificationFactory.foregroundInfo(applicationContext, targetVersion, 0))
            }.onFailure { e ->
                Log.w(TAG, "Unable to run update worker in foreground: ${e.message}")
            }

            // A completed, verified file survives worker/process recreation and is reused without downloading again.
            val existingError = AppUpdateManager.verifyStagedApk(
                context = applicationContext,
                file = targetFile,
                targetVersion = targetVersion,
                targetVersionCode = targetVersionCode,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                expectedCertSha256 = expectedCertSha256,
            )
            if (targetFile.exists() && existingError == null) {
                markReady(prefs, targetFile, targetVersion, expectedSizeBytes, expectedSha256, metadataRevision)
                AppUpdateNotificationFactory.notifyReady(applicationContext, targetVersion)
                return@withContext success(targetFile, targetVersion)
            }

            prefs.edit()
                .putString(KEY_STATE, STATE_DOWNLOADING)
                .putString(KEY_VERSION, targetVersion)
                .putString(KEY_DOWNLOAD_URL, downloadUrl)
                .putString(KEY_APK_NAME, apkFileName)
                .putLong(KEY_EXPECTED_SIZE, expectedSizeBytes)
                .putString(KEY_EXPECTED_SHA256, expectedSha256)
                .putString(KEY_EXPECTED_CERT_SHA256, expectedCertSha256)
                .putLong(KEY_METADATA_REVISION, metadataRevision)
                .commit()

            var currentUrl = downloadUrl
            var redirectsFollowed = 0
            var downloadedAndVerified = false
            var lastProgressAt = 0L
            var lastNotifiedPercent = -1

            while (!downloadedAndVerified) {
                val url = AppUpdateManager.validateHttpsUrl(currentUrl)
                val connection = url.openConnection() as? HttpURLConnection
                    ?: throw PermanentAppUpdateException("Unsupported APK download connection")
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "VRKA-Android-AppUpdater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                try {
                    when (val responseCode = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            connection.inputStream.use { input ->
                                FileOutputStream(tempFile, false).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var downloadedBytes = 0L
                                    while (true) {
                                        if (isStopped) throw CancellationException("Application update work stopped")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        downloadedBytes += count

                                        // Reject streams that exceed the signed byte count
                                        if (downloadedBytes > expectedSizeBytes) {
                                            throw PermanentAppUpdateException("Downloaded bytes exceeded signed release manifest size ($expectedSizeBytes bytes)")
                                        }

                                        output.write(buffer, 0, count)

                                        val percent = ((downloadedBytes * 100L) / expectedSizeBytes)
                                            .toInt()
                                            .coerceIn(0, 100)
                                        val now = System.currentTimeMillis()
                                        if (now - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_MS || percent == 100) {
                                            lastProgressAt = now
                                            lastNotifiedPercent = percent
                                            setProgress(
                                                workDataOf(
                                                    KEY_PROGRESS to (percent / 100f),
                                                    KEY_DOWNLOADED_BYTES to downloadedBytes,
                                                    KEY_TOTAL_BYTES to expectedSizeBytes,
                                                ),
                                            )
                                            runCatching {
                                                setForeground(
                                                    AppUpdateNotificationFactory.foregroundInfo(
                                                        applicationContext,
                                                        targetVersion,
                                                        percent,
                                                    ),
                                                )
                                            }
                                        } else if (percent >= lastNotifiedPercent + 5) {
                                            lastNotifiedPercent = percent
                                            runCatching {
                                                setForeground(
                                                    AppUpdateNotificationFactory.foregroundInfo(
                                                        applicationContext,
                                                        targetVersion,
                                                        percent,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }

                            if (!tempFile.isFile || tempFile.length() <= 0L) {
                                throw IOException("APK download was empty or incomplete")
                            }
                            val payloadValidation = AppUpdateDownloadPolicy.validatePayload(
                                actualSize = tempFile.length(),
                                expectedSize = expectedSizeBytes,
                                actualSha256 = AppUpdateDownloadPolicy.sha256(tempFile),
                                expectedSha256 = expectedSha256,
                            )
                            when (payloadValidation) {
                                PayloadValidation.VERIFIED -> Unit
                                PayloadValidation.INCOMPLETE -> throw IOException("APK download ended before all bytes arrived")
                                PayloadValidation.INVALID -> throw PermanentAppUpdateException(
                                    "APK checksum or size verification failed",
                                )
                            }

                            // Step 1: Verify downloaded archive bytes and security properties on temporary file (WITHOUT filename check)
                            val archiveError = AppUpdateManager.verifyDownloadedArchive(
                                context = applicationContext,
                                file = tempFile,
                                targetVersion = targetVersion,
                                targetVersionCode = targetVersionCode,
                                expectedSizeBytes = expectedSizeBytes,
                                expectedSha256 = expectedSha256,
                                expectedCertSha256 = expectedCertSha256,
                            )
                            if (archiveError != null) throw PermanentAppUpdateException(archiveError)
                            downloadedAndVerified = true
                        }

                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308,
                        -> {
                            val location = connection.getHeaderField("Location")
                                ?: throw PermanentAppUpdateException("APK redirect omitted its target")
                            if (!AppUpdateManager.canFollowRedirect(redirectsFollowed)) {
                                throw PermanentAppUpdateException("APK download exceeded the redirect limit")
                            }
                            currentUrl = AppUpdateManager.validateRedirectTarget(url, location).toExternalForm()
                            redirectsFollowed++
                        }

                        else -> throw AppUpdateHttpException(
                            responseCode,
                            "APK download returned HTTP $responseCode",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }

            // Step 2: Atomically move verified temporary file into final staging target
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Unable to replace an invalid staged APK")
            }
            moveVerifiedFile(tempFile, targetFile, fallbackStageFile)

            // Step 3: Re-verify final staged APK asserting canonical filename and archive integrity
            val stagedError = AppUpdateManager.verifyStagedApk(
                context = applicationContext,
                file = targetFile,
                targetVersion = targetVersion,
                targetVersionCode = targetVersionCode,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                expectedCertSha256 = expectedCertSha256,
            )
            if (stagedError != null) {
                targetFile.delete()
                throw PermanentAppUpdateException(stagedError)
            }

            markReady(prefs, targetFile, targetVersion, expectedSizeBytes, expectedSha256, metadataRevision)
            AppUpdateNotificationFactory.notifyReady(applicationContext, targetVersion)
            success(targetFile, targetVersion)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val retry = AppUpdateDownloadPolicy.shouldRetry(runAttemptCount, failure)
            Log.w(TAG, "Application update download failed (attempt=$runAttemptCount, retry=$retry): ${failure.message}", failure)
            if (retry) {
                prefs.edit()
                    .putString(KEY_STATE, STATE_DOWNLOADING)
                    .putString(KEY_ERROR, failure.message ?: "Temporary update download failure")
                    .commit()
                Result.retry()
            } else {
                tempFile.delete()
                fallbackStageFile.delete()
                prefs.edit()
                    .putString(KEY_STATE, STATE_FAILED)
                    .putString(KEY_ERROR, failure.message ?: "Update download failed")
                    .commit()
                Result.failure(workDataOf(KEY_ERROR to (failure.message ?: "Update download failed")))
            }
        }
    }

    private fun markReady(
        prefs: android.content.SharedPreferences,
        file: File,
        version: String,
        expectedSizeBytes: Long,
        expectedSha256: String,
        metadataRevision: Long,
    ) {
        val committed = prefs.edit()
            .putString(KEY_STATE, STATE_READY_TO_INSTALL)
            .putString(KEY_FILE_PATH, file.absolutePath)
            .putString(KEY_VERSION, version)
            .putLong(KEY_EXPECTED_SIZE, expectedSizeBytes)
            .putString(KEY_EXPECTED_SHA256, expectedSha256)
            .putLong(KEY_METADATA_REVISION, metadataRevision)
            .commit()
        if (!committed) throw IOException("Unable to persist the verified update state")
    }

    private fun success(file: File, version: String): Result = Result.success(
        workDataOf(
            KEY_FILE_PATH to file.absolutePath,
            KEY_VERSION to version,
            KEY_TARGET_VERSION to version,
        ),
    )

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    private fun moveVerifiedFile(source: File, target: File, fallbackStage: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (fallbackStage.exists() && !fallbackStage.delete()) {
                throw IOException("Unable to clear APK staging file")
            }
            source.copyTo(fallbackStage, overwrite = true)
            FileOutputStream(fallbackStage, true).use { it.fd.sync() }
            if (!fallbackStage.renameTo(target)) {
                throw IOException("Unable to atomically publish the verified APK")
            }
            source.delete()
        }
    }

    companion object {
        private const val TAG = "VRKA-AppUpdateWorker"
        const val WORK_NAME = AppUpdateDownloadPolicy.WORK_NAME
        const val PREFS_NAME = "vrka_app_update_prefs"

        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_APK_NAME = "apk_name"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_TARGET_VERSION = "target_version"
        const val KEY_EXPECTED_CERT_SHA256 = "expected_cert_sha256"
        const val KEY_TARGET_VERSION_CODE = "target_version_code"
        const val KEY_METADATA_REVISION = "metadata_revision"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_VERSION = "version"
        const val KEY_ERROR = "error"
        const val KEY_STATE = "state"
        const val KEY_RELEASE_TAG = "release_tag"
        const val KEY_RELEASE_NAME = "release_name"
        const val KEY_RELEASE_BODY = "release_body"
        const val KEY_RELEASE_PUBLISHED_AT = "release_published_at"

        const val STATE_IDLE = "IDLE"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_READY_TO_INSTALL = "READY_TO_INSTALL"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"

        private const val BUFFER_SIZE = 32 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
    }
}
