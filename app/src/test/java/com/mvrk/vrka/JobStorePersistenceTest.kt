package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JobStorePersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestStore(filename: String = "download_jobs.json"): JobStore {
        val file = File(tempFolder.root, filename)
        return JobStore(file)
    }

    private fun sampleRequest(url: String = "https://example.com/video") = DownloadRequest(
        url = url,
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
        prefer60Fps = true,
        audioFormat = AudioFormat.MP3,
        mp3Bitrate = 320,
        isPlaylist = false,
        downloadSubtitles = true,
        automaticCaptions = false,
        embedSubtitles = true,
        subtitleLanguages = "en,es",
        embedMetadata = true,
        embedThumbnail = true,
        sponsorBlock = true,
        sponsorCategories = "sponsor,intro",
        trimStart = "00:00:10",
        trimEnd = "00:01:00",
        customArguments = listOf("--no-cache-dir", "--geo-bypass"),
    )

    @Test
    fun nonExistentFileReturnsEmptyList() {
        val store = createTestStore("non_existent.json")
        val jobs = store.load()
        assertTrue("Non-existent store should return empty list", jobs.isEmpty())
    }

    @Test
    fun queuePersistenceRoundtripTenJobs() {
        val store = createTestStore()
        val originalJobs = (1..10).map { i ->
            DownloadJob(
                id = "job-$i",
                request = sampleRequest("https://example.com/video/$i"),
                state = JobState.DONE,
                title = "Video Title $i",
                detail = "Saved to storage",
                progress = 1.0f,
                speed = "15 MB/s",
                etaSeconds = 0L,
                createdAt = 1000000L + i,
                updatedAt = 2000000L + i,
                outputUris = listOf("content://media/external/video/media/$i"),
                error = "",
                attempt = 1,
            )
        }

        store.save(originalJobs)
        val loadedJobs = store.load()

        assertEquals(10, loadedJobs.size)
        for (i in 0 until 10) {
            val original = originalJobs[i]
            val loaded = loadedJobs[i]
            assertEquals(original.id, loaded.id)
            assertEquals(original.request.url, loaded.request.url)
            assertEquals(original.request.quality, loaded.request.quality)
            assertEquals(original.request.prefer60Fps, loaded.request.prefer60Fps)
            assertEquals(original.request.sponsorBlock, loaded.request.sponsorBlock)
            assertEquals(original.request.trimStart, loaded.request.trimStart)
            assertEquals(original.request.customArguments, loaded.request.customArguments)
            assertEquals(JobState.DONE, loaded.state)
            assertEquals(original.title, loaded.title)
            assertEquals(original.progress, loaded.progress, 0.001f)
            assertEquals(original.outputUris, loaded.outputUris)
        }
    }

    @Test
    fun interruptedActiveJobsRecoverAsFailedOnRestart() {
        val store = createTestStore()
        val activeStates = listOf(
            JobState.QUEUED,
            JobState.PREPARING,
            JobState.WAITING_FOR_USER,
            JobState.BROWSER_FALLBACK,
            JobState.DOWNLOADING,
            JobState.POSTPROCESSING,
        )

        val inFlightJobs = activeStates.mapIndexed { idx, state ->
            DownloadJob(
                id = "active-$idx",
                request = sampleRequest("https://example.com/stream/$idx"),
                state = state,
                title = "In Flight Stream $idx",
                detail = "Downloading 45%",
                progress = 0.45f,
            )
        }

        store.save(inFlightJobs)
        val restoredJobs = store.load()

        assertEquals(activeStates.size, restoredJobs.size)
        for (job in restoredJobs) {
            assertEquals("In-flight job must be recovered as FAILED", JobState.FAILED, job.state)
            assertEquals("Interrupted", job.detail)
            assertTrue(
                "Must inform user download was interrupted with retry prompt",
                job.error.contains("interrupted when Android stopped VRKA") && job.error.contains("Tap retry"),
            )
        }
    }

    @Test
    fun terminalJobsRetainTheirExactStateAndErrors() {
        val store = createTestStore()
        val terminalJobs = listOf(
            DownloadJob(
                id = "term-done",
                request = sampleRequest(),
                state = JobState.DONE,
                title = "Finished",
                detail = "Complete",
                progress = 1.0f,
                error = "",
            ),
            DownloadJob(
                id = "term-failed",
                request = sampleRequest(),
                state = JobState.FAILED,
                title = "Errored",
                detail = "Failed at extraction",
                error = "HTTP 404 Not Found",
            ),
            DownloadJob(
                id = "term-cancelled",
                request = sampleRequest(),
                state = JobState.CANCELLED,
                title = "Stopped",
                detail = "Cancelled by user",
                error = "User cancelled download",
            ),
        )

        store.save(terminalJobs)
        val loaded = store.load()

        assertEquals(3, loaded.size)
        assertEquals(JobState.DONE, loaded[0].state)
        assertEquals("", loaded[0].error)

        assertEquals(JobState.FAILED, loaded[1].state)
        assertEquals("HTTP 404 Not Found", loaded[1].error)

        assertEquals(JobState.CANCELLED, loaded[2].state)
        assertEquals("User cancelled download", loaded[2].error)
    }

    @Test
    fun corruptedJsonFileRecoversGracefully() {
        val targetFile = File(tempFolder.root, "corrupted_jobs.json")
        targetFile.writeText("{ this is definitely not valid json !!! }", Charsets.UTF_8)

        val store = JobStore(targetFile)
        val jobs = store.load()
        assertNotNull(jobs)
        assertTrue("Corrupted JSON should load as empty list without throwing", jobs.isEmpty())
    }

    @Test
    fun capsPersistenceAt250Jobs() {
        val store = createTestStore()
        val manyJobs = (1..300).map { i ->
            DownloadJob(
                id = "job-$i",
                request = sampleRequest(),
                state = JobState.DONE,
                title = "Job $i",
            )
        }

        store.save(manyJobs)
        val loaded = store.load()
        assertEquals("Store must cap saved jobs to 250", 250, loaded.size)
    }
}
