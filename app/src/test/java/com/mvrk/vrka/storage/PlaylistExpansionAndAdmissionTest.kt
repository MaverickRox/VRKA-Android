package com.mvrk.vrka.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.BatchEnqueueResult
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.DownloadTransferRuntime
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PlaylistRouting
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.SizeEstimate
import com.mvrk.vrka.SizeEstimateCalculator
import com.mvrk.vrka.SizeEstimateKind
import com.mvrk.vrka.VrkaDownloadManager
import com.mvrk.vrka.VrkaDownloadManager.Companion.MAX_ADMITTED_JOBS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PlaylistExpansionAndAdmissionTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadTransferRuntime.resetForTesting()
        testDir = File(context.cacheDir, "playlist-test-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging").apply { mkdirs() }
        settings = SettingsRepository(
            context,
            kotlinx.coroutines.flow.MutableStateFlow(
                AppSettings(
                    outputTreeUri = "",
                    saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
                    concurrentDownloadsEnabled = true,
                    maxConcurrentDownloads = 3,
                ),
            ),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomStorageRepository(database)
    }

    @After
    fun tearDown() {
        DownloadTransferRuntime.resetForTesting()
        runBlocking { runCatching { scope.coroutineContext[Job]?.cancelAndJoin() } }
        runCatching { database.close() }
        testDir.deleteRecursively()
    }

    private fun buildManager(
        executor: (suspend (DownloadJob, File) -> List<File>?)? = null,
    ): VrkaDownloadManager {
        val publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = repository.publicationStore,
            ops = ops,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION),
        )
        return VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repository,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repository.cleanupStore,
            autoRecoverOnInit = false,
            runtimeInitOverride = {},
            customDownloadExecutor = executor ?: { job, destDir ->
                val f = File(destDir, "${job.title.ifBlank { job.id }}.mp4").apply {
                    writeBytes(ByteArray(256) { 0x42 })
                }
                listOf(f)
            },
            streamValidationOverride = { _, _ -> },
        )
    }

    @Test
    fun testPlaylistRouting_singleVideoWithPlaylistContext_stripsParameters() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345&index=3"
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext(watchUrl))
        assertFalse(PlaylistRouting.isPurePlaylistUrl(watchUrl))

        val stripped = PlaylistRouting.stripPlaylistContext(watchUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", stripped)

        val shortUrl = "https://youtu.be/dQw4w9WgXcQ?list=PL12345&feature=share"
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext(shortUrl))
        assertFalse(PlaylistRouting.isPurePlaylistUrl(shortUrl))

        val shortStripped = PlaylistRouting.stripPlaylistContext(shortUrl)
        assertTrue(shortStripped.contains("youtu.be/dQw4w9WgXcQ"))
        assertTrue(shortStripped.contains("feature=share"))
        assertFalse(shortStripped.contains("list="))
    }

    @Test
    fun testPlaylistRouting_extractPlaylistUrl_producesCanonicalPlaylistUrl() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345&index=3"
        val extractedFromWatch = PlaylistRouting.extractPlaylistUrl(watchUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL12345", extractedFromWatch)
        assertTrue(PlaylistRouting.isPurePlaylistUrl(extractedFromWatch!!))

        val shortUrl = "https://youtu.be/dQw4w9WgXcQ?list=PL12345&feature=share"
        val extractedFromShort = PlaylistRouting.extractPlaylistUrl(shortUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL12345", extractedFromShort)
        assertTrue(PlaylistRouting.isPurePlaylistUrl(extractedFromShort!!))

        val plainVideoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        org.junit.Assert.assertNull(PlaylistRouting.extractPlaylistUrl(plainVideoUrl))
    }

    @Test
    fun testPlaylistRouting_purePlaylistUrl_detectedProperly() {
        val purePlaylist = "https://www.youtube.com/playlist?list=PL12345"
        assertTrue(PlaylistRouting.isPurePlaylistUrl(purePlaylist))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext(purePlaylist))

        val channelUrl = "https://www.youtube.com/@Artist/videos"
        assertFalse(PlaylistRouting.isPurePlaylistUrl(channelUrl))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext(channelUrl))
    }

    @Test
    fun testPlaylistRouting_strictlyRequiresGenuineYouTubePlaylistAttributes() {
        // Normal YouTube videos without list must NEVER trigger playlist context
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))

        // Non-YouTube URLs with query parameters must NEVER trigger playlist context
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://instagram.com/reel/123/?v=abc&list=PL12345"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.tiktok.com/@user/video/12345?v=abc&list=PL12345"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://missav.ai/dm123/en?list=PL12345&v=abc"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://example.com/watch?v=abc&list=PL12345"))

        // YouTube URLs with empty or invalid list parameters must NEVER trigger playlist context
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list="))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list= "))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=a"))

        // Pure playlists must not be recognized as single-video-with-playlist-context
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/playlist?list=PL12345"))
        assertFalse(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"))

        // Genuine YouTube composite links (video with playlist attribute) MUST trigger playlist context
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345"))
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"))
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ"))
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext("https://youtu.be/dQw4w9WgXcQ?list=PL12345"))
        assertTrue(PlaylistRouting.isVideoWithPlaylistContext("https://www.youtube.com/shorts/dQw4w9WgXcQ?list=PL12345"))
    }

    @Test
    fun testMetadataExpansion_preservesDuplicateItemsWithDistinctIdsAndIndices() {
        val metadataJson = """
            {
                "_type": "playlist",
                "title": "Test Duplicate Playlist",
                "entries": [
                    {
                        "id": "video1",
                        "title": "Track One",
                        "webpage_url": "https://www.youtube.com/watch?v=video1",
                        "duration": 120,
                        "formats": [{"format_id": "18", "ext": "mp4", "filesize": 1000}]
                    },
                    {
                        "id": "video2",
                        "title": "Track Two",
                        "webpage_url": "https://www.youtube.com/watch?v=video2",
                        "duration": 180,
                        "formats": [{"format_id": "18", "ext": "mp4", "filesize": 2000}]
                    },
                    {
                        "id": "video1",
                        "title": "Track One (Reprise)",
                        "webpage_url": "https://www.youtube.com/watch?v=video1",
                        "duration": 120,
                        "formats": [{"format_id": "18", "ext": "mp4", "filesize": 1000}]
                    }
                ]
            }
        """.trimIndent()

        val baseRequest = DownloadRequest(
            url = "https://www.youtube.com/playlist?list=PL12345",
            isPlaylist = true,
        )

        val items = SizeEstimateCalculator.expandPlaylistFromMetadataJson(
            jsonText = metadataJson,
            baseRequest = baseRequest,
            maxItems = 25,
        )

        assertNotNull(items)
        assertEquals(3, items!!.size)

        // Item 1
        assertEquals("Track One", items[0].title)
        assertEquals("https://www.youtube.com/watch?v=video1", items[0].url)
        assertEquals(1, items[0].playlistItemIndex)
        assertEquals(3, items[0].playlistItemCount)

        // Item 2
        assertEquals("Track Two", items[1].title)
        assertEquals("https://www.youtube.com/watch?v=video2", items[1].url)
        assertEquals(2, items[1].playlistItemIndex)
        assertEquals(3, items[1].playlistItemCount)

        // Item 3 (Repeated video1, must not be deduplicated!)
        assertEquals("Track One (Reprise)", items[2].title)
        assertEquals("https://www.youtube.com/watch?v=video1", items[2].url)
        assertEquals(3, items[2].playlistItemIndex)
        assertEquals(3, items[2].playlistItemCount)

        // All item IDs must be unique
        val itemIds = items.map { it.playlistItemId }.toSet()
        assertEquals(3, itemIds.size)
    }

    @Test
    fun testMetadataExpansion_respectsBoundedMaxItemsAndRanges() {
        val metadataJson = """
            {
                "_type": "playlist",
                "title": "Bounded Playlist",
                "entries": [
                    {"id": "v1", "title": "Track 1"},
                    {"id": "v2", "title": "Track 2"},
                    {"id": "v3", "title": "Track 3"},
                    {"id": "v4", "title": "Track 4"},
                    {"id": "v5", "title": "Track 5"}
                ]
            }
        """.trimIndent()

        val baseRequest = DownloadRequest(
            url = "https://www.youtube.com/playlist?list=PL12345",
            isPlaylist = true,
            playlistStart = 2,
            playlistEnd = 5,
        )

        // Range 2..5 slice is [v2, v3, v4, v5], maxItems = 2 limits to [v2, v3]
        val boundedItems = SizeEstimateCalculator.expandPlaylistFromMetadataJson(
            jsonText = metadataJson,
            baseRequest = baseRequest,
            maxItems = 2,
        )

        assertNotNull(boundedItems)
        assertEquals(2, boundedItems!!.size)
        assertEquals("Track 2", boundedItems[0].title)
        assertEquals(1, boundedItems[0].playlistItemIndex)
        assertEquals(2, boundedItems[0].playlistItemCount)
        assertEquals("Track 3", boundedItems[1].title)
        assertEquals(2, boundedItems[1].playlistItemIndex)
    }

    @Test
    fun testAtomicCapacityAdmission_rejectsAllWhenCapacityExceeded() = runBlocking {
        val manager = buildManager()
        manager.awaitStorageReady()

        // Populate active non-terminal jobs up to MAX_ADMITTED_JOBS - 1
        val dummyJobs = (1 until MAX_ADMITTED_JOBS).map { i ->
            DownloadJob(
                id = "dummy-$i",
                request = DownloadRequest(url = "https://example.com/item$i.mp4"),
                state = JobState.QUEUED,
            )
        }
        manager.mutate(persist = false) { dummyJobs }

        val activeCountBefore = manager.jobs.value.count { !it.state.isTerminal }
        assertEquals(MAX_ADMITTED_JOBS - 1, activeCountBefore)

        // Attempting to admit a batch of 2 items must fail atomically (capacity exceeded by 1)
        val batchItems = listOf(
            DownloadRequest(url = "https://example.com/batch1.mp4") to null,
            DownloadRequest(url = "https://example.com/batch2.mp4") to null,
        )

        val batchResult = manager.enqueueBatch(batchItems)
        assertTrue(batchResult is BatchEnqueueResult.Rejected)
        assertEquals("queue_capacity", (batchResult as BatchEnqueueResult.Rejected).reason)

        // Verify active jobs count is completely unchanged: exactly 0 items were admitted!
        val activeCountAfter = manager.jobs.value.count { !it.state.isTerminal }
        assertEquals(MAX_ADMITTED_JOBS - 1, activeCountAfter)
    }

    @Test
    fun testBatchEnqueue_successAdmitsAllItemsWithSharedGroupIdAndIndices() = runBlocking {
        val manager = buildManager()
        manager.awaitStorageReady()
        val playlistGroupId = UUID.randomUUID().toString()

        val batchItems = listOf(
            DownloadRequest(
                url = "https://example.com/item1.mp4",
                playlistItemIndex = 1,
                playlistItemCount = 3,
            ) to SizeEstimate(SizeEstimateKind.EXACT, bytes = 1000L, title = "Item 1"),
            DownloadRequest(
                url = "https://example.com/item2.mp4",
                playlistItemIndex = 2,
                playlistItemCount = 3,
            ) to SizeEstimate(SizeEstimateKind.EXACT, bytes = 2000L, title = "Item 2"),
            DownloadRequest(
                url = "https://example.com/item3.mp4",
                playlistItemIndex = 3,
                playlistItemCount = 3,
            ) to SizeEstimate(SizeEstimateKind.EXACT, bytes = 3000L, title = "Item 3"),
        )

        val result = manager.enqueueBatch(batchItems, playlistGroupId = playlistGroupId)
        assertTrue(result is BatchEnqueueResult.Accepted)
        val accepted = result as BatchEnqueueResult.Accepted
        assertEquals(3, accepted.jobIds.size)
        assertEquals(playlistGroupId, accepted.playlistGroupId)

        val currentJobs = manager.jobs.value
        val batchJobs = currentJobs.filter { it.playlistGroupId == playlistGroupId }
        assertEquals(3, batchJobs.size)

        val indices = batchJobs.mapNotNull { it.playlistItemIndex }.sorted()
        assertEquals(listOf(1, 2, 3), indices)
        batchJobs.forEach { job ->
            assertEquals(3, job.playlistItemCount)
            assertEquals(playlistGroupId, job.playlistGroupId)
            assertNotNull(job.estimatedTotalBytes)
        }
    }

    @Test
    fun testConcurrentJobs_completeIndependentlyWithoutBlockingSiblings() = runBlocking {
        val job2FailedLatch = CountDownLatch(1)
        val job1DoneLatch = CountDownLatch(1)
        val job3DoneLatch = CountDownLatch(1)

        val manager = buildManager(
            executor = { job, destDir ->
                when (job.title) {
                    "Job 1" -> {
                        val f = File(destDir, "job1.mp4").apply { writeBytes(ByteArray(100)) }
                        job1DoneLatch.countDown()
                        listOf(f)
                    }
                    "Job 2 (Failing)" -> {
                        job2FailedLatch.countDown()
                        throw IllegalStateException("Insufficient storage on device")
                    }
                    "Job 3" -> {
                        val f = File(destDir, "job3.mp4").apply { writeBytes(ByteArray(300)) }
                        job3DoneLatch.countDown()
                        listOf(f)
                    }
                    else -> emptyList()
                }
            },
        )
        manager.awaitStorageReady()

        val playlistGroupId = UUID.randomUUID().toString()
        val items = listOf(
            DownloadRequest(url = "https://example.com/1.mp4") to SizeEstimate(SizeEstimateKind.EXACT, 100L, "Job 1"),
            DownloadRequest(url = "https://example.com/2.mp4") to SizeEstimate(SizeEstimateKind.EXACT, 200L, "Job 2 (Failing)"),
            DownloadRequest(url = "https://example.com/3.mp4") to SizeEstimate(SizeEstimateKind.EXACT, 300L, "Job 3"),
        )

        val result = manager.enqueueBatch(items, playlistGroupId)
        assertTrue(result is BatchEnqueueResult.Accepted)
        val accepted = result as BatchEnqueueResult.Accepted

        // Wait for execution latches
        assertTrue("Job 1 should finish", job1DoneLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Job 2 failure should trigger", job2FailedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Job 3 should finish despite Job 2 failing", job3DoneLatch.await(5, TimeUnit.SECONDS))

        // Wait for all 3 jobs to reach terminal states
        withTimeout(10_000L) {
            while (true) {
                val current = accepted.jobIds.mapNotNull { id -> manager.jobs.value.firstOrNull { it.id == id } }
                if (current.size == 3 && current.all { it.state.isTerminal }) break
                delay(20L)
            }
        }

        val finalJobs = manager.jobs.value.filter { it.playlistGroupId == playlistGroupId }
        val job1 = finalJobs.find { it.title == "Job 1" }
        val job2 = finalJobs.find { it.title == "Job 2 (Failing)" }
        val job3 = finalJobs.find { it.title == "Job 3" }

        assertNotNull(job1)
        assertNotNull(job2)
        assertNotNull(job3)

        assertEquals(JobState.DONE, job1?.state)
        assertEquals(JobState.FAILED, job2?.state)
        assertEquals(JobState.DONE, job3?.state)

        // Job 1 and Job 3 succeeded and published their outputs independently
        assertFalse(job1!!.outputUris.isEmpty())
        assertFalse(job3!!.outputUris.isEmpty())
    }
}
