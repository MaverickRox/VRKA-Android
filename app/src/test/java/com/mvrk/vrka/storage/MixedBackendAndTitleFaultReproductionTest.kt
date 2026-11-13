package com.mvrk.vrka.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 1 reproduction & mixed-backend concurrency harness:
 * - Reproduces stale job.title bug when title updates mid-transfer before preferredOutputName()
 * - Verifies subtitle sidecar outputs (.srt) stay bound to single History job
 * - Exercises 1 fallback-like slow/custom job alongside 2 YouTube-like jobs across completion orders
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MixedBackendAndTitleFaultReproductionTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var dbName: String
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var settingsState: MutableStateFlow<AppSettings>
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "mixed-backend-${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        DownloadTransferRuntime.resetForTesting()
        testDir = File(context.cacheDir, "mixed-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging").apply { mkdirs() }
        settingsState = MutableStateFlow(
            AppSettings(
                outputTreeUri = "",
                saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
                concurrentDownloadsEnabled = true,
                maxConcurrentDownloads = 3,
            ),
        )
        settings = SettingsRepository(context, settingsState)
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
        val dbFile = context.getDatabasePath(dbName)
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        testDir.deleteRecursively()
    }

    private fun buildManager(
        executor: suspend (DownloadJob, File) -> List<File>?,
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
            customDownloadExecutor = executor,
            streamValidationOverride = { _, _ -> },
        )
    }

    private suspend fun awaitAllTerminal(manager: VrkaDownloadManager, ids: Collection<String>) {
        withTimeout(15_000L) {
            while (true) {
                val list = ids.mapNotNull { id -> manager.jobs.value.firstOrNull { it.id == id } }
                if (list.size == ids.size && list.all { it.state.isTerminal }) break
                delay(25L)
            }
        }
    }

    private fun invokeMidTransferTitleUpdate(manager: VrkaDownloadManager, jobId: String, resolvedTitle: String) {
        val updateMethod = VrkaDownloadManager::class.java.declaredMethods.first { it.name == "update" }
        updateMethod.isAccessible = true
        val args = Array<Any?>(updateMethod.parameters.size) { idx ->
            val param = updateMethod.parameters[idx]
            when (idx) {
                0 -> jobId
                2 -> resolvedTitle
                else -> when (param.type) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        }
        updateMethod.invoke(manager, *args)
    }

    @Test
    fun staleTitleBeforeExecution_usesUpdatedTitleInsteadOfFallbackHost() = runBlocking {
        lateinit var managerRef: VrkaDownloadManager
        val manager = buildManager { job, dir ->
            invokeMidTransferTitleUpdate(managerRef, job.id, "Real Discovered Title")
            val media = File(dir, "media.mp4").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(320) { 1 })
            }
            listOf(media)
        }
        managerRef = manager

        val accepted = manager.enqueue(DownloadRequest(url = "https://youtu.be/abc123", mode = MediaMode.VIDEO)) as EnqueueResult.Accepted
        awaitAllTerminal(manager, listOf(accepted.jobId))

        val doneJob = manager.jobs.value.first { it.id == accepted.jobId }
        assertEquals(JobState.DONE, doneJob.state)
        val uri = Uri.parse(doneJob.outputUris.single())
        val displayName = ops.queryDisplayName(uri).orEmpty()
        assertTrue(
            "Published file name '$displayName' must reflect resolved title rather than stale 'youtu.be'",
            displayName.startsWith("Real Discovered Title"),
        )
        assertFalse(displayName.contains("youtu.be"))
    }

    @Test
    fun subtitleSidecar_withSingleVideo_remainsSingleHistoryJob() = runBlocking {
        val manager = buildManager { _, dir ->
            val video = File(dir, "video.mp4").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(500) { 2 })
            }
            val srt = File(dir, "video.en.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:01,000\nHello\n")
            }
            listOf(video, srt)
        }

        val accepted = manager.enqueue(
            DownloadRequest(
                url = "https://www.youtube.com/watch?v=subtest1",
                mode = MediaMode.VIDEO,
                downloadSubtitles = true,
                embedSubtitles = false,
            ),
        ) as EnqueueResult.Accepted
        awaitAllTerminal(manager, listOf(accepted.jobId))

        val terminalJobs = database.jobDao().all()
            .map { repository.decodePageEntity(it) }
            .filter { it.state == JobState.DONE }
        assertEquals("Video + subtitle sidecar must produce exactly 1 History job", 1, terminalJobs.size)
        val finished = terminalJobs.single()
        assertEquals(2, finished.outputUris.size)
        val firstDisplay = ops.queryDisplayName(Uri.parse(finished.outputUris[0])).orEmpty()
        val secondDisplay = ops.queryDisplayName(Uri.parse(finished.outputUris[1])).orEmpty()
        assertTrue("Primary media output must be ordered first, got $firstDisplay", firstDisplay.endsWith(".mp4"))
        assertTrue("Subtitle sidecar must follow primary media, got $secondDisplay", secondDisplay.endsWith(".srt"))
        assertEquals("actualFinalFileBytes must exclude subtitle sidecars", 500L, finished.actualFinalFileBytes)
    }

    @Test
    fun stageInstrumentation_emitsTraceAndCleanupCallerLogs() = runBlocking {
        ShadowLog.clear()
        lateinit var managerRef: VrkaDownloadManager
        val manager = buildManager { job, dir ->
            val media = File(dir, "clip.mp4").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(420) { 9 })
            }
            listOf(media)
        }
        managerRef = manager
        val accepted = manager.enqueue(DownloadRequest(url = "https://www.youtube.com/watch?v=traceme", mode = MediaMode.VIDEO)) as EnqueueResult.Accepted
        awaitAllTerminal(manager, listOf(accepted.jobId))
        var logs = emptyList<String>()
        withTimeout(5_000L) {
            while (true) {
                logs = ShadowLog.getLogsForTag("VRKA_TRACE").map { it.msg }
                if (logs.any { it.contains("CLEANUP_DELETE_CALLER") && it.contains("jobId=${accepted.jobId}") && it.contains("deleteReason=terminal_commit_complete") }) {
                    break
                }
                delay(25L)
            }
        }
        assertTrue(logs.any { it.contains("jobId=${accepted.jobId}") && it.contains("stage=publication-started") })
        assertTrue(logs.any { it.contains("jobId=${accepted.jobId}") && it.contains("stage=plan-output") })
        assertTrue(logs.any { it.contains("jobId=${accepted.jobId}") && it.contains("stage=output-published") })
        assertTrue(logs.any { it.contains("jobId=${accepted.jobId}") && it.contains("stage=commit-terminal-success") })
        assertTrue(logs.any { it.contains("CLEANUP_DELETE_CALLER") && it.contains("jobId=${accepted.jobId}") && it.contains("deleteReason=terminal_commit_complete") })
    }

    @Test
    fun mixedBackend_missAvFallbackAndTwoYoutube_allCompletionOrdersPreserveOutputs() = runBlocking {
        runPermutation(missAvOrder = 1)
        runPermutation(missAvOrder = 2)
        runPermutation(missAvOrder = 3)
        runMissAvFailThenRetry()
    }

    private suspend fun runMissAvFailThenRetry() {
        ops.mediaItems.clear()
        ops.deletedUris.clear()
        val missAttempt = AtomicInteger(0)
        val manager = buildManager { job, dir ->
            when {
                job.request.url.contains("missav") -> {
                    if (missAttempt.incrementAndGet() == 1) {
                        throw IllegalStateException("Simulated transient MissAV transfer failure")
                    }
                    File(dir, "missav_retry.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(410) { 7 })
                    }.let(::listOf)
                }
                job.request.url.contains("yt1") -> {
                    File(dir, "yt1_r.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(355) { 4 })
                    }.let(::listOf)
                }
                else -> {
                    File(dir, "yt2_r.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(365) { 5 })
                    }.let(::listOf)
                }
            }
        }
        manager.awaitStorageReady()
        val idMiss = (manager.enqueue(DownloadRequest(url = "https://missav.example/v/failretry", mode = MediaMode.VIDEO, quality = VideoQuality.P720)) as EnqueueResult.Accepted).jobId
        val idYt1 = (manager.enqueue(DownloadRequest(url = "https://youtube.com/watch?v=yt1_fr", mode = MediaMode.VIDEO, quality = VideoQuality.P1080)) as EnqueueResult.Accepted).jobId
        val idYt2 = (manager.enqueue(DownloadRequest(url = "https://youtube.com/watch?v=yt2_fr", mode = MediaMode.VIDEO, quality = VideoQuality.P1080)) as EnqueueResult.Accepted).jobId

        awaitAllTerminal(manager, listOf(idMiss, idYt1, idYt2))
        assertEquals(JobState.FAILED, manager.jobs.value.first { it.id == idMiss }.state)
        assertEquals(JobState.DONE, manager.jobs.value.first { it.id == idYt1 }.state)
        assertEquals(JobState.DONE, manager.jobs.value.first { it.id == idYt2 }.state)

        val retryId = manager.retry(idMiss)
        assertTrue("retry() must enqueue MissAV attempt 2", retryId != null)
        awaitAllTerminal(manager, listOf(idMiss))

        listOf(idMiss, idYt1, idYt2).forEach { id ->
            val j = manager.jobs.value.first { it.id == id }
            assertEquals("Job $id must finish DONE", JobState.DONE, j.state)
            assertTrue("Output file for $id missing on provider", ops.fileExists(Uri.parse(j.outputUris.single())))
        }
        assertTrue("Unexpected deletions during fail-retry: ${ops.deletedUris}", ops.deletedUris.isEmpty())
    }

    private suspend fun runPermutation(missAvOrder: Int) {
        ops.mediaItems.clear()
        ops.deletedUris.clear()
        val yt1Done = CountDownLatch(1)
        val yt2Done = CountDownLatch(1)
        val missAvDone = CountDownLatch(1)

        val manager = buildManager { job, dir ->
            when {
                job.request.url.contains("missav") -> {
                    when (missAvOrder) {
                        2 -> yt1Done.await(5, TimeUnit.SECONDS)
                        3 -> {
                            yt1Done.await(5, TimeUnit.SECONDS)
                            yt2Done.await(5, TimeUnit.SECONDS)
                        }
                    }
                    File(dir, "missav_fallback.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(400) { 3 })
                    }.let(::listOf).also { missAvDone.countDown() }
                }
                job.request.url.contains("yt1") -> {
                    if (missAvOrder == 1) missAvDone.await(5, TimeUnit.SECONDS)
                    File(dir, "yt1.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(350) { 4 })
                    }.let(::listOf).also { yt1Done.countDown() }
                }
                else -> {
                    if (missAvOrder <= 2) missAvDone.await(5, TimeUnit.SECONDS)
                    yt1Done.await(5, TimeUnit.SECONDS)
                    File(dir, "yt2.mp4").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(360) { 5 })
                    }.let(::listOf).also { yt2Done.countDown() }
                }
            }
        }

        suspend fun acceptOrFail(request: DownloadRequest): String {
            manager.awaitStorageReady()
            return when (val r = manager.enqueue(request)) {
                is EnqueueResult.Accepted -> r.jobId
                is EnqueueResult.Rejected -> {
                    val saveProbe = repository.saveJobs(
                        listOf(DownloadJob(id = "probe-${System.nanoTime()}", request = request)),
                        emptySet(),
                    )
                    org.junit.Assert.fail(
                        "Enqueue rejected for ${request.url}: [${r.reason}] ${r.message}; directSave=(${saveProbe.success}: ${saveProbe.error})",
                    )
                    ""
                }
            }
        }

        val idMiss = acceptOrFail(DownloadRequest(url = "https://missav.example/v/${missAvOrder}", mode = MediaMode.VIDEO, quality = VideoQuality.P720))
        val idYt1 = acceptOrFail(DownloadRequest(url = "https://youtube.com/watch?v=yt1_$missAvOrder", mode = MediaMode.VIDEO, quality = VideoQuality.P1080))
        val idYt2 = acceptOrFail(DownloadRequest(url = "https://youtube.com/watch?v=yt2_$missAvOrder", mode = MediaMode.VIDEO, quality = VideoQuality.P1080))

        awaitAllTerminal(manager, listOf(idMiss, idYt1, idYt2))

        listOf(idMiss, idYt1, idYt2).forEach { id ->
            val j = manager.jobs.value.first { it.id == id }
            assertEquals("Job $id failed in order permutation $missAvOrder: ${j.error}", JobState.DONE, j.state)
            assertTrue("Output file for $id missing on provider", ops.fileExists(Uri.parse(j.outputUris.single())))
        }
        assertTrue("Unexpected deletions during concurrent mixed publish: ${ops.deletedUris}", ops.deletedUris.isEmpty())
    }
}
