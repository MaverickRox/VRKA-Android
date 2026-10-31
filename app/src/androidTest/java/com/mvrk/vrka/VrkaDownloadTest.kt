package com.mvrk.vrka

import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VrkaDownloadTest {

    @Test
    fun testDownloadPipelineOnDevice() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads

        val testUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.360p.vp9.webm"
        val request = DownloadRequest(
            url = testUrl,
            resolvedMediaUrl = testUrl,
            resolvedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36",
            ),
            mode = MediaMode.AUDIO,
            audioFormat = AudioFormat.MP3,
        )

        manager.enqueue(request)

        val completedJob = withTimeout(90_000) {
            var targetJob: DownloadJob? = null
            while (targetJob == null || !targetJob.state.isTerminal) {
                val currentJobs: List<DownloadJob> = manager.jobs.first()
                targetJob = currentJobs.firstOrNull { it.request.url == testUrl }
                if (targetJob != null && targetJob.state.isTerminal) {
                    break
                }
                kotlinx.coroutines.delay(500)
            }
            targetJob
        }

        assertNotNull("Job should exist in manager", completedJob)
        assertEquals(
            "Download should succeed with DONE state. Error: ${completedJob?.error}",
            JobState.DONE,
            completedJob?.state
        )
        assertTrue("Output URIs should not be empty", completedJob?.outputUris?.isNotEmpty() == true)
    }

    @Test
    fun testDownloadOpusStreamCopyOnDevice() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads

        val testUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.360p.vp9.webm"
        val request = DownloadRequest(
            url = testUrl,
            resolvedMediaUrl = testUrl,
            resolvedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36",
            ),
            mode = MediaMode.AUDIO,
            audioFormat = AudioFormat.OPUS,
        )

        manager.enqueue(request)

        val completedJob = withTimeout(90_000) {
            var targetJob: DownloadJob? = null
            while (targetJob == null || !targetJob.state.isTerminal) {
                val currentJobs: List<DownloadJob> = manager.jobs.first()
                targetJob = currentJobs.firstOrNull { it.request.url == testUrl && it.request.audioFormat == AudioFormat.OPUS }
                if (targetJob != null && targetJob.state.isTerminal) {
                    break
                }
                kotlinx.coroutines.delay(500)
            }
            targetJob
        }

        assertNotNull("Job should exist in manager", completedJob)
        assertEquals(
            "Download should succeed with DONE state. Error: ${completedJob?.error}",
            JobState.DONE,
            completedJob?.state
        )
        assertTrue("Output URIs should not be empty", completedJob?.outputUris?.isNotEmpty() == true)
    }

    /**
     * The end-to-end proof for the completion notification: an ordinary download must leave a
     * notification behind after the foreground service is gone. The bundled yt-dlp/FFmpeg
     * binaries are ARM64-only, so this is skipped on x86 emulators instead of being reported
     * as a product failure.
     */
    @Test
    fun realDownloadLeavesACompletionNotification() = runBlocking {
        assumeTrue(
            "bundled runtime is arm64-only; ABIs=${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val notifications = app.getSystemService(NotificationManager::class.java)

        val testUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.360p.vp9.webm"
        manager.enqueue(
            DownloadRequest(
                url = testUrl,
                resolvedMediaUrl = testUrl,
                resolvedHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36",
                ),
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.OPUS,
            ),
        )

        val completed = withTimeout(90_000) {
            var job: DownloadJob? = null
            while (job == null || !job.state.isTerminal) {
                job = manager.jobs.first().firstOrNull { it.request.url == testUrl }
                if (job?.state?.isTerminal == true) break
                kotlinx.coroutines.delay(500)
            }
            job
        }
        assertEquals(JobState.DONE, completed!!.state)

        val posted = withTimeout(5_000) {
            var match: android.service.notification.StatusBarNotification? = null
            while (match == null) {
                match = notifications.activeNotifications
                    .firstOrNull { it.tag == DownloadNotifications.completionTagFor(completed!!.id) }
                if (match == null) kotlinx.coroutines.delay(200)
            }
            match
        }
        assertNotNull("a completed download must leave a notification", posted)
        assertTrue(posted!!.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
    }
}
