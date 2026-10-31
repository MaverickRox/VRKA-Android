package com.mvrk.vrka.update

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.mvrk.vrka.worker.AppUpdateDownloadWorker
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object AppUpdateDownloadPolicy {
    const val WORK_NAME = "VRKA_APP_UPDATE_WORK"
    const val MAX_RETRY_ATTEMPTS = 5
    const val INITIAL_BACKOFF_MILLIS = 30_000L

    val UNIQUE_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    enum class PayloadValidation {
        VERIFIED,
        INCOMPLETE,
        INVALID,
    }

    enum class FailureDisposition {
        RETRY,
        PERMANENT,
    }

    fun normalizeGitHubSha256(digest: String?): String? {
        val normalized = digest
            ?.trim()
            ?.removePrefix("sha256:")
            ?.lowercase(Locale.ROOT)
            ?: return null
        return normalized.takeIf(SHA256_PATTERN::matches)
    }

    fun validatePayload(
        actualSize: Long,
        expectedSize: Long,
        actualSha256: String,
        expectedSha256: String,
    ): PayloadValidation {
        if (expectedSize <= 0L || actualSize <= 0L) return PayloadValidation.INVALID
        if (actualSize < expectedSize) return PayloadValidation.INCOMPLETE
        if (actualSize > expectedSize) return PayloadValidation.INVALID
        val expected = normalizeGitHubSha256(expectedSha256) ?: return PayloadValidation.INVALID
        val actual = normalizeGitHubSha256(actualSha256) ?: return PayloadValidation.INVALID
        return if (actual == expected) PayloadValidation.VERIFIED else PayloadValidation.INVALID
    }

    fun canRestoreReadyToInstall(
        persistedState: String?,
        fileExists: Boolean,
        apkVerified: Boolean,
        targetVersion: SemanticVersion,
        installedVersion: SemanticVersion,
    ): Boolean = persistedState == AppUpdateDownloadWorker.STATE_READY_TO_INSTALL &&
        fileExists && apkVerified && targetVersion.isNewerThan(installedVersion)

    fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun dispositionFor(failure: Throwable): FailureDisposition {
        var current: Throwable? = failure
        while (current != null) {
            if (current is PermanentAppUpdateException ||
                current is SecurityException ||
                current is IllegalArgumentException
            ) {
                return FailureDisposition.PERMANENT
            }
            if (current is AppUpdateHttpException) {
                return if (isRetryableHttpStatus(current.statusCode)) {
                    FailureDisposition.RETRY
                } else {
                    FailureDisposition.PERMANENT
                }
            }
            current = current.cause
        }
        return if (failure is IOException) {
            FailureDisposition.RETRY
        } else {
            FailureDisposition.PERMANENT
        }
    }

    fun shouldRetry(runAttemptCount: Int, failure: Throwable): Boolean =
        runAttemptCount < MAX_RETRY_ATTEMPTS - 1 && dispositionFor(failure) == FailureDisposition.RETRY

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in setOf(500, 502, 503, 504)

    fun createWorkRequest(release: AppReleaseInfo): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    AppUpdateDownloadWorker.KEY_DOWNLOAD_URL to release.apkDownloadUrl,
                    AppUpdateDownloadWorker.KEY_APK_NAME to release.apkFileName,
                    AppUpdateDownloadWorker.KEY_EXPECTED_SIZE to release.apkSizeBytes,
                    AppUpdateDownloadWorker.KEY_EXPECTED_SHA256 to release.apkSha256,
                    AppUpdateDownloadWorker.KEY_TARGET_VERSION to release.version.toString(),
                    AppUpdateDownloadWorker.KEY_EXPECTED_CERT_SHA256 to release.certificateSha256,
                    AppUpdateDownloadWorker.KEY_METADATA_REVISION to release.metadataRevision,
                    AppUpdateDownloadWorker.KEY_TARGET_VERSION_CODE to (release.manifest?.versionCode ?: 0L),
                ),
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(WORK_NAME)
            .build()
    }

    private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
}

class AppUpdateHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class PermanentAppUpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
