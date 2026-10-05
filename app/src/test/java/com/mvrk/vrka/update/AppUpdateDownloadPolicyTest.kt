package com.mvrk.vrka.update

import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.mvrk.vrka.worker.AppUpdateDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class AppUpdateDownloadPolicyTest {
    private val digest = "ab".repeat(32)

    @Test
    fun workRequestIsPersistentNetworkConstrainedUniqueAndHasBoundedBackoff() {
        val request = AppUpdateDownloadPolicy.createWorkRequest(release())
        val workSpec = readWorkSpec(request)

        assertTrue(CoroutineWorker::class.java.isAssignableFrom(AppUpdateDownloadWorker::class.java))
        assertEquals(AppUpdateDownloadPolicy.WORK_NAME, AppUpdateDownloadWorker.WORK_NAME)
        assertEquals(ExistingWorkPolicy.KEEP, AppUpdateDownloadPolicy.UNIQUE_WORK_POLICY)
        val constraints = fieldValue(workSpec, "constraints")!!
        assertEquals(NetworkType.CONNECTED, getterValue(constraints, "getRequiredNetworkType"))
        assertEquals(BackoffPolicy.EXPONENTIAL, fieldValue(workSpec, "backoffPolicy"))
        assertEquals(AppUpdateDownloadPolicy.INITIAL_BACKOFF_MILLIS, fieldValue(workSpec, "backoffDelayDuration"))
        assertTrue(request.tags.contains(AppUpdateDownloadPolicy.WORK_NAME))
        val input = fieldValue(workSpec, "input") as androidx.work.Data
        assertEquals("5.0.2", input.getString(AppUpdateDownloadWorker.KEY_TARGET_VERSION))
        assertEquals(digest, input.getString(AppUpdateDownloadWorker.KEY_EXPECTED_SHA256))
    }

    @Test
    fun connectionAbortAndTemporaryServerStatusesRetryButSecurityFailuresDoNot() {
        assertEquals(
            AppUpdateDownloadPolicy.FailureDisposition.RETRY,
            AppUpdateDownloadPolicy.dispositionFor(IOException("Software caused connection abort")),
        )
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { code ->
            assertTrue("HTTP $code should retry", AppUpdateDownloadPolicy.isRetryableHttpStatus(code))
        }
        listOf(301, 400, 401, 403, 404, 422, 501).forEach { code ->
            assertFalse("HTTP $code must fail permanently", AppUpdateDownloadPolicy.isRetryableHttpStatus(code))
        }
        assertEquals(
            AppUpdateDownloadPolicy.FailureDisposition.PERMANENT,
            AppUpdateDownloadPolicy.dispositionFor(SecurityException("HTTPS downgrade")),
        )
        assertEquals(
            AppUpdateDownloadPolicy.FailureDisposition.PERMANENT,
            AppUpdateDownloadPolicy.dispositionFor(PermanentAppUpdateException("checksum mismatch")),
        )
        assertEquals(
            AppUpdateDownloadPolicy.FailureDisposition.PERMANENT,
            AppUpdateDownloadPolicy.dispositionFor(IOException("Wrapped permanent error", PermanentAppUpdateException("bad apk"))),
        )
        assertEquals(
            AppUpdateDownloadPolicy.FailureDisposition.PERMANENT,
            AppUpdateDownloadPolicy.dispositionFor(RuntimeException("Wrapped security error", SecurityException("bad host"))),
        )
    }

    @Test
    fun retriesStopAfterTheConfiguredAttemptLimit() {
        val transient = IOException("connection reset")
        assertTrue(AppUpdateDownloadPolicy.shouldRetry(0, transient))
        assertTrue(AppUpdateDownloadPolicy.shouldRetry(AppUpdateDownloadPolicy.MAX_RETRY_ATTEMPTS - 2, transient))
        assertFalse(AppUpdateDownloadPolicy.shouldRetry(AppUpdateDownloadPolicy.MAX_RETRY_ATTEMPTS - 1, transient))
        assertFalse(AppUpdateDownloadPolicy.shouldRetry(0, SecurityException("bad host")))
    }

    @Test
    fun stagedBytesMustMatchExpectedLengthAndGitHubSha256() {
        assertEquals(
            AppUpdateDownloadPolicy.PayloadValidation.VERIFIED,
            AppUpdateDownloadPolicy.validatePayload(500L, 500L, digest, "sha256:$digest"),
        )
        assertEquals(
            AppUpdateDownloadPolicy.PayloadValidation.INCOMPLETE,
            AppUpdateDownloadPolicy.validatePayload(499L, 500L, digest, digest),
        )
        assertEquals(
            AppUpdateDownloadPolicy.PayloadValidation.INVALID,
            AppUpdateDownloadPolicy.validatePayload(501L, 500L, digest, digest),
        )
        assertEquals(
            AppUpdateDownloadPolicy.PayloadValidation.INVALID,
            AppUpdateDownloadPolicy.validatePayload(500L, 500L, "cd".repeat(32), digest),
        )
        assertEquals(
            AppUpdateDownloadPolicy.PayloadValidation.INVALID,
            AppUpdateDownloadPolicy.validatePayload(500L, 500L, digest, "bad-checksum"),
        )
    }

    @Test
    fun completedPrivateStagingRemainsVerifiableAfterWorkerRecreation() {
        val directory = Files.createTempDirectory("vrka-app-update-stage").toFile()
        try {
            val staged = directory.resolve("VRKA-Android-v5.0.2.apk")
            staged.writeBytes(ByteArray(500) { it.toByte() })
            val firstWorkerHash = AppUpdateDownloadPolicy.sha256(staged)
            val recreatedWorkerFile = directory.resolve(staged.name)

            assertTrue(recreatedWorkerFile.exists())
            assertEquals(
                AppUpdateDownloadPolicy.PayloadValidation.VERIFIED,
                AppUpdateDownloadPolicy.validatePayload(
                    recreatedWorkerFile.length(),
                    500L,
                    AppUpdateDownloadPolicy.sha256(recreatedWorkerFile),
                    firstWorkerHash,
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyCompleteVerifiedNewerApksRestoreAsReadyToInstall() {
        val current = SemanticVersion(5, 0, 1)
        val target = SemanticVersion(5, 0, 2)
        assertTrue(
            AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                AppUpdateDownloadWorker.STATE_READY_TO_INSTALL,
                fileExists = true,
                apkVerified = true,
                targetVersion = target,
                installedVersion = current,
            ),
        )
        assertFalse(
            AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                AppUpdateDownloadWorker.STATE_DOWNLOADING,
                fileExists = true,
                apkVerified = true,
                targetVersion = target,
                installedVersion = current,
            ),
        )
        assertFalse(
            AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                AppUpdateDownloadWorker.STATE_READY_TO_INSTALL,
                fileExists = true,
                apkVerified = false,
                targetVersion = target,
                installedVersion = current,
            ),
        )
        assertFalse(
            AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                AppUpdateDownloadWorker.STATE_READY_TO_INSTALL,
                fileExists = false,
                apkVerified = true,
                targetVersion = target,
                installedVersion = current,
            ),
        )
        assertFalse(
            AppUpdateDownloadPolicy.canRestoreReadyToInstall(
                AppUpdateDownloadWorker.STATE_READY_TO_INSTALL,
                fileExists = true,
                apkVerified = true,
                targetVersion = current,
                installedVersion = current,
            ),
        )
    }

    @Test
    fun readyNotificationNamesVersionAndReturnsToUpdateFlow() {
        assertEquals("VRKA Android 5.0.2 is ready to install.", AppUpdateNotificationFactory.readyMessage("5.0.2"))
        assertEquals("com.mvrk.vrka.action.OPEN_APP_UPDATE", com.mvrk.vrka.MainActivity.ACTION_OPEN_APP_UPDATE)
    }

    private fun release() = AppReleaseInfo(
        tagName = "v5.0.2",
        version = SemanticVersion(5, 0, 2),
        name = "VRKA Android 5.0.2",
        body = "",
        publishedAt = "2026-10-05T00:00:00Z",
        apkDownloadUrl = "https://github.com/MaverickRox/VRKA-Android/releases/download/v5.0.2/VRKA-Android-v5.0.2.apk",
        apkFileName = "VRKA-Android-v5.0.2.apk",
        apkSizeBytes = 500,
        apkSha256 = digest,
    )

    private fun readWorkSpec(request: androidx.work.OneTimeWorkRequest): Any = fieldValue(request, "workSpec")!!

    private fun fieldValue(target: Any, fieldName: String): Any? {
        val field = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { klass -> runCatching { klass.getDeclaredField(fieldName) }.getOrNull() }
            .first()
        field.isAccessible = true
        return field.get(target)
    }

    private fun getterValue(target: Any, getter: String): Any? =
        target.javaClass.getMethod(getter).invoke(target)
}
