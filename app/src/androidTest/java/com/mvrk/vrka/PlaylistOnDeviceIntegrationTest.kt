package com.mvrk.vrka

import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvrk.vrka.engine.GeckoTransportRequest
import com.mvrk.vrka.engine.GeckoWebExecutorTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaylistOnDeviceIntegrationTest {

    private val targetPlaylistUrl = "https://youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"

    @Test
    fun testPlaylistDisaggregationAndPublicationOnOnePlus11(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for yt-dlp binary runtime",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val publisher = manager.publisher

        // 1. Configure settings for concurrency = 4
        manager.settingsRepository.setConcurrentDownloadsEnabled(true)
        manager.settingsRepository.setMaxConcurrentDownloads(4)

        // 2. Preflight timing and flat expansion
        val startPreflight = System.currentTimeMillis()
        val playlistRequest = DownloadRequest(
            url = targetPlaylistUrl,
            isPlaylist = true,
            mode = MediaMode.AUDIO,
            audioFormat = AudioFormat.MP3,
        )
        val items = manager.expandPlaylist(playlistRequest, maxItems = 25, forceRefresh = true)
        val preflightDuration = System.currentTimeMillis() - startPreflight

        assertNotNull("Playlist expansion must succeed", items)
        assertEquals("Target playlist must expand to exactly 9 items", 9, items!!.size)
        assertTrue("Preflight expansion with --flat-playlist must be fast (< 10s)", preflightDuration < 10_000)

        // Verify each child item has a valid independent watch URL
        items.forEachIndexed { index, item ->
            assertTrue("Item url must be a valid watch URL", item.url.startsWith("https://www.youtube.com/watch?v="))
            assertEquals(index + 1, item.playlistItemIndex)
            assertEquals(9, item.playlistItemCount)
            assertNotNull(item.playlistItemId)
        }

        // 3. Disaggregate into independent batch items
        val playlistGroupId = UUID.randomUUID().toString()
        val batchItems = items.map { item ->
            playlistRequest.copy(
                url = item.url,
                isPlaylist = false,
                playlistItemIndex = item.playlistItemIndex,
                playlistItemCount = item.playlistItemCount,
                playlistItemId = item.playlistItemId,
                playlistGroupId = playlistGroupId,
            ) to item.estimate
        }

        val enqueueResult = manager.enqueueBatch(batchItems, playlistGroupId)
        assertTrue("Batch enqueue must be accepted", enqueueResult is BatchEnqueueResult.Accepted)

        // 4. Verify 9 distinct jobs exist in manager
        val initialJobs = manager.jobs.first().filter { it.request.playlistGroupId == playlistGroupId }
        assertEquals("Exactly 9 distinct DownloadJob records must be created", 9, initialJobs.size)
        val distinctJobIds = initialJobs.map { it.id }.toSet()
        assertEquals("Each job must have a unique ID", 9, distinctJobIds.size)

        // 5. Monitor concurrency: verify concurrency pool limit (at most 4 active simultaneously)
        var maxObservedActive = 0
        val jobIds = distinctJobIds.toList()

        val completedJobs = withTimeout(480_000) { // 8-minute timeout for 9 audio downloads over mobile network
            while (true) {
                val currentJobs = manager.jobs.first().filter { it.id in jobIds }
                val activeCount = currentJobs.count { it.state == JobState.DOWNLOADING || it.state == JobState.PREPARING || it.state == JobState.POSTPROCESSING }
                if (activeCount > maxObservedActive) {
                    maxObservedActive = activeCount
                }
                assertTrue("Active concurrent downloads must not exceed 4 (observed: $activeCount)", activeCount <= 4)

                val allTerminal = currentJobs.size == 9 && currentJobs.all { it.state.isTerminal }
                if (allTerminal) {
                    return@withTimeout currentJobs
                }
                kotlinx.coroutines.delay(1_000)
            }
            error("Unreachable")
        }

        assertTrue("Concurrency must have utilized multiple slots (> 1)", maxObservedActive > 1)

        // 6. Verify all 9 jobs succeeded (state == DONE)
        completedJobs.forEach { job ->
            assertEquals("Job ${job.id} (${job.title}) must succeed with state DONE. Error: ${job.error}", JobState.DONE, job.state)
            assertTrue("Job ${job.id} must have at least one output URI", job.outputUris.isNotEmpty())
        }

        // 7. Verify all 9 files are published and finalized in MediaStore
        val publishedUris = mutableListOf<String>()
        completedJobs.forEach { job ->
            val uriStr = job.outputUris.first()
            publishedUris.add(uriStr)
            val uri = Uri.parse(uriStr)
            assertTrue("Published file $uriStr must exist in storage", publisher.fileExists(uriStr))
            assertTrue(
                "MediaStore row $uriStr must be finalized with IS_PENDING = 0",
                publisher.verifyMediaStoreFinalized(uri, publisher.nodeName(uriStr) ?: ""),
            )
        }
        assertEquals("Exactly 9 distinct files published", 9, publishedUris.distinct().size)

        // 8. Verify all 9 jobs appear separately in Room History
        val historyJobs = manager.jobs.first().filter { it.id in jobIds && it.state == JobState.DONE }
        assertEquals("All 9 jobs must appear separately in History", 9, historyJobs.size)

        // 9. Verify History Deletion Integrity: delete 1 job with file
        val targetJob = completedJobs.first()
        val targetUri = targetJob.outputUris.first()
        assertTrue("Target file must exist before deletion", publisher.fileExists(targetUri))

        val deleteResult = manager.deleteJobWithFiles(targetJob.id)
        assertEquals("Exactly 1 history record should be deleted", 1, deleteResult.deletedRecordsCount)
        assertEquals("No file deletion failures expected", 0, deleteResult.failedFilesCount)

        // Physical file must be removed from disk
        assertFalse("Physical file must be deleted from storage", publisher.fileExists(targetUri))

        // History now contains remaining 8 jobs
        val remainingHistory = manager.jobs.first().filter { it.id in jobIds }
        assertEquals("History should contain exactly 8 remaining jobs", 8, remainingHistory.size)
        assertFalse("Deleted job must not be in history", remainingHistory.any { it.id == targetJob.id })

        // Clean up remaining 8 test jobs and files
        remainingHistory.forEach { job ->
            runCatching { manager.deleteJobWithFiles(job.id) }
        }
    }

    @Test
    fun testMixedGeckoFallbackAndYouTubeConcurrencyOnDevice(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for Gecko/yt-dlp binaries",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads

        manager.settingsRepository.setConcurrentDownloadsEnabled(true)
        manager.settingsRepository.setMaxConcurrentDownloads(4)

        // 1. YouTube audio download
        val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val ytRequest = DownloadRequest(
            url = ytUrl,
            mode = MediaMode.AUDIO,
            audioFormat = AudioFormat.MP3,
        )
        val ytJobId = UUID.randomUUID().toString()
        val ytJob = DownloadJob(
            id = ytJobId,
            request = ytRequest,
            title = "YouTube Concurrency Test",
        )

        // 2. Gecko fallback transport download (direct GeckoWebExecutor stream)
        val geckoRuntime = withContext(Dispatchers.Main) {
            GeckoRuntimeManager.getInstance(app).runtime
        }
        val geckoTransport = GeckoWebExecutorTransport(geckoRuntime)
        val geckoTargetFile = File(app.cacheDir, "gecko_concurrency_test_${System.currentTimeMillis()}.bin")

        // Execute both concurrently
        val ytDeferred = async(Dispatchers.IO) {
            manager.enqueue(ytRequest)
            withTimeout(90_000) {
                while (true) {
                    val jobs = manager.jobs.first()
                    val match = jobs.firstOrNull { it.request.url == ytUrl }
                    if (match != null && match.state.isTerminal) {
                        return@withTimeout match
                    }
                    kotlinx.coroutines.delay(1_000)
                }
                error("Unreachable")
            }
        }

        val geckoDeferred = async(Dispatchers.IO) {
            geckoTransport.downloadToFile(
                request = GeckoTransportRequest(url = "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"),
                destinationFile = geckoTargetFile,
            )
        }

        val ytResult = ytDeferred.await()
        val geckoBytes = geckoDeferred.await()

        assertEquals("YouTube download must complete with DONE", JobState.DONE, ytResult.state)
        assertTrue("Gecko download must complete with positive bytes", geckoBytes > 0)
        assertTrue("Gecko target file must exist on disk", geckoTargetFile.exists())
        assertEquals(geckoBytes, geckoTargetFile.length())

        // Clean up
        geckoTargetFile.delete()
        runCatching { manager.deleteJobWithFiles(ytResult.id) }
        Unit
    }
}
