package com.mvrk.vrka

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
}
