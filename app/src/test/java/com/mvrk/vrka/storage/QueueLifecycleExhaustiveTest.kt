package com.mvrk.vrka.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.DownloadTransferRuntime
import com.mvrk.vrka.EnqueueResult
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.VrkaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class QueueLifecycleExhaustiveTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var settingsState: MutableStateFlow<AppSettings>
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File
    private lateinit var publisher: OutputPublisher
    private lateinit var manager: VrkaDownloadManager

    private lateinit var dbName: String

    private fun deleteDb(name: String = dbName) {
        val base = context.getDatabasePath(name)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun buildDb(name: String = dbName): VrkaDatabase =
        Room.databaseBuilder(context, VrkaDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "queue-test-${System.nanoTime()}.db"
        deleteDb()
        DownloadTransferRuntime.resetForTesting()
        testDir = File(context.cacheDir, "queue-test-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging").apply { mkdirs() }
        settingsState = MutableStateFlow(
            AppSettings(
                outputTreeUri = "",
                saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
                concurrentDownloadsEnabled = false,
                maxConcurrentDownloads = 2,
            ),
        )
        settings = SettingsRepository(context, settingsState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
    }

    @After
    fun tearDown() {
        DownloadTransferRuntime.resetForTesting()
        runBlocking { runCatching { scope.coroutineContext[Job]?.cancelAndJoin() } }
        runCatching { database.close() }
        deleteDb()
        testDir.deleteRecursively()
    }

    private fun buildManager(
        autoRecoverOnInit: Boolean = false,
        stagingDelete: (File) -> Boolean = { it.deleteRecursively() },
        executor: suspend (DownloadJob, File) -> List<File>?,
    ): VrkaDownloadManager {
        publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = repository.publicationStore,
            ops = ops,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION),
        )
        manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repository,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repository.cleanupStore,
            stagingDelete = stagingDelete,
            autoRecoverOnInit = autoRecoverOnInit,
            runtimeInitOverride = { },
            customDownloadExecutor = executor,
            streamValidationOverride = { _, _ -> },
        )
        return manager
    }

    private fun writeMediaFile(dir: File, name: String, seed: Int = 7): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(256) { (it + seed).toByte() })
        }

    private fun enqueueVideo(tag: String, url: String = "https://example.com/watch?v=$tag"): String {
        val res = manager.enqueue(DownloadRequest(url = url, mode = MediaMode.VIDEO, quality = VideoQuality.P1080))
        assertTrue(res is EnqueueResult.Accepted)
        return (res as EnqueueResult.Accepted).jobId
    }

    private fun enqueueAudio(tag: String, format: AudioFormat): String {
        val res = manager.enqueue(
            DownloadRequest(
                url = "https://example.com/watch?v=$tag",
                mode = MediaMode.AUDIO,
                audioFormat = format,
            ),
        )
        assertTrue(res is EnqueueResult.Accepted)
        return (res as EnqueueResult.Accepted).jobId
    }

    private suspend fun awaitAllTerminal(jobIds: Collection<String>, timeoutMs: Long = 10_000L) {
        withTimeout(timeoutMs) {
            while (true) {
                val jobs = jobIds.mapNotNull { id -> manager.jobs.value.firstOrNull { it.id == id } }
                if (jobs.size == jobIds.size && jobs.all { it.state.isTerminal }) break
                delay(20L)
            }
        }
    }

    @Test
    fun q01_q02_q03_singleTwoAndMultipleSequentialDownloadsCompleteWithStagingCleaned() = runBlocking {
        val order = mutableListOf<String>()
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        buildManager { job, dir ->
            val cur = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, cur) }
            synchronized(order) { order += job.request.url }
            delay(15L)
            val f = writeMediaFile(dir, "${job.id}.mp4", seed = order.size)
            inFlight.decrementAndGet()
            listOf(f)
        }

        // Q1: Single
        val j0 = enqueueVideo("single")
        awaitAllTerminal(listOf(j0))
        assertEquals(JobState.DONE, manager.jobs.value.first { it.id == j0 }.state)
        assertFalse(File(stagingRoot, j0).exists())

        // Q2 & Q3: 2 sequential + 3 more (4 sequential queued together)
        val multi = (1..4).map { idx -> enqueueVideo("seq-$idx") }
        awaitAllTerminal(multi)
        multi.forEach { id ->
            val persisted = database.jobDao().byId(id)!!
            assertEquals(JobState.DONE.name, persisted.state)
            assertEquals(100f, persisted.progress, 0.01f)
            assertFalse("Staging for $id must be cleaned after DONE", File(stagingRoot, id).exists())
        }
        assertEquals("Sequential mode must never exceed concurrency 1", 1, peak.get())
    }

    @Test
    fun q04_q05_concurrencyOneVsConcurrencyThree() = runBlocking {
        // Q4: explicit concurrency = 1
        settingsState.value = settingsState.value.copy(concurrentDownloadsEnabled = false, maxConcurrentDownloads = 4)
        val inFlightSeq = AtomicInteger(0)
        val peakSeq = AtomicInteger(0)
        buildManager { job, dir ->
            val c = inFlightSeq.incrementAndGet()
            peakSeq.updateAndGet { maxOf(it, c) }
            delay(35L)
            val f = writeMediaFile(dir, "${job.id}.mp4")
            inFlightSeq.decrementAndGet()
            listOf(f)
        }
        val seqIds = (1..3).map { enqueueVideo("c1-$it") }
        awaitAllTerminal(seqIds)
        assertEquals(1, peakSeq.get())

        // Q5: explicit concurrency = 3
        settingsState.value = settingsState.value.copy(concurrentDownloadsEnabled = true, maxConcurrentDownloads = 3)
        val barrier = CountDownLatch(3)
        val inFlightPar = AtomicInteger(0)
        val peakPar = AtomicInteger(0)
        val release = CountDownLatch(1)
        buildManager { job, dir ->
            val c = inFlightPar.incrementAndGet()
            peakPar.updateAndGet { maxOf(it, c) }
            barrier.countDown()
            assertTrue("Expected 3 concurrent workers to enter simultaneously", barrier.await(5, TimeUnit.SECONDS))
            release.await(5, TimeUnit.SECONDS)
            val f = writeMediaFile(dir, "${job.id}.mp4", seed = job.id.hashCode())
            inFlightPar.decrementAndGet()
            listOf(f)
        }
        val parIds = (1..4).map { enqueueVideo("c3-$it") }
        release.countDown()
        awaitAllTerminal(parIds)
        assertTrue("Expected peak concurrency 3, was ${peakPar.get()}", peakPar.get() == 3)
        parIds.forEach { assertEquals(JobState.DONE, manager.jobs.value.first { j -> j.id == it }.state) }
    }

    @Test
    fun q06_q07_mixedVideoAndAudioWithDifferentOutputFormats() = runBlocking {
        val extByUrl = mapOf(
            "https://example.com/watch?v=vid-mp4" to "mp4",
            "https://example.com/watch?v=vid-mkv" to "mkv",
            "https://example.com/watch?v=vid-webm" to "webm",
            "https://example.com/watch?v=aud-mp3" to "mp3",
            "https://example.com/watch?v=aud-opus" to "opus",
            "https://example.com/watch?v=aud-wav" to "wav",
        )
        settingsState.value = settingsState.value.copy(concurrentDownloadsEnabled = true, maxConcurrentDownloads = 2)
        buildManager { job, dir ->
            val ext = extByUrl.getValue(job.request.url)
            listOf(writeMediaFile(dir, "output.$ext", seed = ext.hashCode()))
        }
        val ids = listOf(
            enqueueVideo("vid-mp4"),
            enqueueAudio("aud-mp3", AudioFormat.MP3),
            enqueueVideo("vid-mkv"),
            enqueueAudio("aud-opus", AudioFormat.OPUS),
            enqueueVideo("vid-webm"),
            enqueueAudio("aud-wav", AudioFormat.WAV),
        )
        awaitAllTerminal(ids)
        ids.forEach { id ->
            val j = manager.jobs.value.first { it.id == id }
            assertEquals("Mixed format job $id (${j.request.url}) failed: ${j.error}", JobState.DONE, j.state)
            assertEquals(1, j.outputUris.size)
            val expectedExt = extByUrl.getValue(j.request.url)
            val storedName = ops.queryDisplayName(Uri.parse(j.outputUris.single()))!!
            assertTrue("Expected .$expectedExt display name, got $storedName", storedName.endsWith(".$expectedExt"))
        }
    }

    @Test
    fun q08_q09_q10_failuresInFirstMiddleAndLastQueueItemsDoNotBlockOthers() = runBlocking {
        // Note: YouTube target HTTP error is terminal and does not invoke browser fallback
        buildManager { job, dir ->
            when {
                job.request.url.contains("fail-1") -> throw IllegalStateException("Insufficient storage (first)")
                job.request.url.contains("fail-mid") -> throw IllegalStateException("Insufficient storage (middle)")
                job.request.url.contains("fail-last") -> throw IllegalStateException("Insufficient storage (last)")
                else -> listOf(writeMediaFile(dir, "${job.id}.mp4"))
            }
        }
        val firstFail = enqueueVideo("fail-1")
        val okA = enqueueVideo("ok-a")
        val midFail = enqueueVideo("fail-mid")
        val okB = enqueueVideo("ok-b")
        val lastFail = enqueueVideo("fail-last")

        val all = listOf(firstFail, okA, midFail, okB, lastFail)
        awaitAllTerminal(all)

        assertEquals(JobState.FAILED, database.jobDao().byId(firstFail)?.state?.let(JobState::valueOf))
        assertEquals(JobState.DONE, database.jobDao().byId(okA)?.state?.let(JobState::valueOf))
        assertEquals(JobState.FAILED, database.jobDao().byId(midFail)?.state?.let(JobState::valueOf))
        assertEquals(JobState.DONE, database.jobDao().byId(okB)?.state?.let(JobState::valueOf))
        assertEquals(JobState.FAILED, database.jobDao().byId(lastFail)?.state?.let(JobState::valueOf))
    }

    @Test
    fun q11_retryAfterFailedDownloadIncrementsAttemptAndSucceeds() = runBlocking {
        val attemptsSeen = AtomicInteger(0)
        buildManager { job, dir ->
            if (attemptsSeen.incrementAndGet() == 1) {
                throw IllegalStateException("Insufficient storage on attempt 1")
            }
            listOf(writeMediaFile(dir, "${job.id}_a${job.attempt}.mp4"))
        }
        val id = enqueueVideo("retry-me")
        awaitAllTerminal(listOf(id))
        val failed = database.jobDao().byId(id)!!
        assertEquals(JobState.FAILED.name, failed.state)

        val retriedId = manager.retry(id)
        assertEquals(id, retriedId)
        withTimeout(10_000L) {
            while (database.jobDao().byId(id)?.state != JobState.DONE.name) {
                delay(20L)
            }
        }
        val done = database.jobDao().byId(id)!!
        assertEquals(JobState.DONE.name, done.state)
        assertEquals(2, repository.attemptDaoCountForJob(id))
    }

    @Test
    fun q12_cancelOneItemWhileOtherItemsAreQueued() = runBlocking {
        val j1Started = CountDownLatch(1)
        val releaseJ1 = CountDownLatch(1)
        buildManager { job, dir ->
            if (job.request.url.endsWith("cancel-target")) {
                j1Started.countDown()
                delay(5_000L) // cancelled before completion
            }
            releaseJ1.await(5, TimeUnit.SECONDS)
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val cancelledId = enqueueVideo("cancel-target")
        val queuedOk = enqueueVideo("after-cancel")
        assertTrue(j1Started.await(5, TimeUnit.SECONDS))

        manager.cancel(cancelledId)
        releaseJ1.countDown()
        awaitAllTerminal(listOf(cancelledId, queuedOk))

        assertEquals(JobState.CANCELLED, manager.jobs.value.first { it.id == cancelledId }.state)
        assertEquals(JobState.DONE, manager.jobs.value.first { it.id == queuedOk }.state)
        assertEquals(JobState.DONE.name, database.jobDao().byId(queuedOk)?.state)
    }

    @Test
    fun q13_waitingForUserPausesPendingDestinationAndResumesOnSelection() = runBlocking {
        settingsState.value = settingsState.value.copy(saveLocationMode = SaveLocationMode.ASK_EVERY_TIME)
        val safTree = "content://saf/tree/root"
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(safTree),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        buildManager { job, dir ->
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val id = enqueueVideo("ask-folder")
        withTimeout(5_000L) {
            while (manager.jobs.value.firstOrNull { it.id == id }?.state != JobState.WAITING_FOR_USER) {
                delay(20L)
            }
        }
        assertTrue(ops.safItems.isEmpty())

        // Supply destination -> resumes execution and publishes to SAF tree
        manager.setJobDestination(id, safTree)
        awaitAllTerminal(listOf(id))
        val live = manager.jobs.value.firstOrNull { it.id == id }
        val done = database.jobDao().byId(id)!!
        assertEquals(
            "live=$live error=${live?.error}",
            JobState.DONE.name,
            done.state,
        )
        assertEquals(1, ops.safItems.size)
    }

    @Test
    fun q14_q15_q19_appAndDatabaseRestartWithAndWithoutScheduledTransferRecovery() = runBlocking {
        val jDownloadingStarted = CountDownLatch(1)
        buildManager { job, dir ->
            jDownloadingStarted.countDown()
            delay(10_000L)
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val jActive = enqueueVideo("in-progress")
        val jQueued = enqueueVideo("waiting-in-queue")
        assertTrue(jDownloadingStarted.await(5, TimeUnit.SECONDS))

        // Q15: Simulate abrupt process kill + plain cold DB restart (no scheduled transfer job)
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        DownloadTransferRuntime.resetForTesting()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        buildManager(autoRecoverOnInit = true) { job, dir ->
            listOf(writeMediaFile(dir, "${job.id}_restarted.mp4"))
        }
        // Cold launch must NEVER treat interrupted job as DONE
        val coldActive = database.jobDao().byId(jActive)!!
        assertFalse("Interrupted job must not become DONE", coldActive.state == JobState.DONE.name)

        // Q14 & Q19: Simulate system-scheduled resumption after restart / DB reopen
        val jResumeA = DownloadJob(
            id = "resume-q",
            request = DownloadRequest(url = "https://example.com/watch?v=resume-q"),
            state = JobState.QUEUED,
            createdAt = 500L,
            updatedAt = 500L,
        )
        val saveRes = repository.saveJobs(listOf(jResumeA), setOf("resume-q"))
        assertTrue("saveJobs failed: ${saveRes.error}", saveRes.success)
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        DownloadTransferRuntime.markTransferJobInvoked()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        buildManager(autoRecoverOnInit = true) { job, dir ->
            listOf(writeMediaFile(dir, "${job.id}_resumed.mp4"))
        }
        awaitAllTerminal(listOf("resume-q"))
        assertEquals(JobState.DONE.name, database.jobDao().byId("resume-q")?.state)
        assertNotNull(database.jobDao().byId(jQueued))
    }

    @Test
    fun q16_networkInterruptionAndRestorationRecoverViaRetry() = runBlocking {
        val networkDown = AtomicBoolean(true)
        buildManager { job, dir ->
            if (networkDown.get()) {
                throw IllegalStateException("Insufficient storage")
            }
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val id = enqueueVideo("net-drop", url = "https://www.youtube.com/watch?v=net-drop")
        awaitAllTerminal(listOf(id))
        assertEquals(JobState.FAILED, manager.jobs.value.first { it.id == id }.state)

        // Restore network & retry
        networkDown.set(false)
        assertEquals(id, manager.retry(id))
        withTimeout(10_000L) {
            while (manager.jobs.value.first { it.id == id }.state != JobState.DONE) {
                delay(20L)
            }
        }
        assertEquals(JobState.DONE.name, database.jobDao().byId(id)?.state)
    }

    @Test
    fun q17_publicationFailureAfterExtractionDoesNotCommitFakeDoneRecord() = runBlocking {
        ops.failInsert = true
        buildManager { job, dir ->
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val id = enqueueVideo("pub-fail")
        awaitAllTerminal(listOf(id))

        val failed = database.jobDao().byId(id)!!
        assertEquals("Failed publication must produce FAILED, never DONE", JobState.FAILED.name, failed.state)
        assertTrue(ops.mediaItems.isEmpty())

        // Provider recovers -> manual retry publishes and commits DONE
        ops.failInsert = false
        assertEquals(id, manager.retry(id))
        withTimeout(10_000L) {
            while (database.jobDao().byId(id)?.state != JobState.DONE.name) delay(20L)
        }
        assertEquals(JobState.DONE.name, database.jobDao().byId(id)?.state)
        assertEquals(1, ops.mediaItems.size)
    }

    @Test
    fun q18_stagingCleanupFailureAfterSuccessfulPublicationStillCommitsDoneAndRetriesLater() = runBlocking {
        val allowDelete = AtomicBoolean(false)
        buildManager(
            stagingDelete = { dir ->
                if (!allowDelete.get()) false else dir.deleteRecursively()
            },
        ) { job, dir ->
            listOf(writeMediaFile(dir, "${job.id}.mp4"))
        }
        val id = enqueueVideo("cleanup-fail")
        awaitAllTerminal(listOf(id))

        val done = database.jobDao().byId(id)!!
        assertEquals(JobState.DONE.name, done.state)
        assertEquals(1, ops.mediaItems.size)
        assertTrue("Staging dir retained while cleanup fails", File(stagingRoot, id).exists())
        assertTrue("Durable STAGING cleanup task must be pending", repository.cleanupStore.pending().any { it.jobId == id })

        // Cleanup recovers on startup reconciliation
        allowDelete.set(true)
        manager.reconcilePublicationJournalOnStartup()
        assertFalse(File(stagingRoot, id).exists())
        assertFalse(repository.cleanupStore.pending().any { it.jobId == id })
    }

    private fun RoomStorageRepository.attemptDaoCountForJob(jobId: String): Int =
        database.attemptDao().forJob(jobId).size
}
