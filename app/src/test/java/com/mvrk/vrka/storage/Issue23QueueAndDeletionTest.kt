package com.mvrk.vrka.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.EnqueueResult
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournal
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationStorageOps
import com.mvrk.vrka.PublicationTransactionStatus
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.VrkaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Issue23QueueAndDeletionTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var settingsState: MutableStateFlow<AppSettings>
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File
    private lateinit var publisher: OutputPublisher
    private lateinit var manager: VrkaDownloadManager

    private fun deleteDb(name: String = VrkaDatabase.NAME) {
        val base = context.getDatabasePath(name)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun buildDb(name: String = VrkaDatabase.NAME): VrkaDatabase =
        Room.databaseBuilder(context, VrkaDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDb()
        testDir = File(context.cacheDir, "issue23-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging").apply { mkdirs() }
        settingsState = MutableStateFlow(AppSettings(outputTreeUri = ""))
        settings = SettingsRepository(context, settingsState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        rebuildManager(repository, ops)
    }

    @After
    fun tearDown() {
        runCatching { scope.cancel() }
        runCatching { database.close() }
        deleteDb()
        testDir.deleteRecursively()
    }

    private fun rebuildManager(
        repo: RoomStorageRepository,
        storageOps: PublicationStorageOps,
    ): VrkaDownloadManager {
        publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = repo.publicationStore,
            ops = storageOps,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION),
        )
        manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repo,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repo.cleanupStore,
            autoRecoverOnInit = false,
        )
        return manager
    }

    private fun makeStagingFile(jobId: String, name: String = "video.mp4", content: ByteArray = ByteArray(128) { 42 }): File =
        File(stagingRoot, "$jobId/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(content)
        }

    private suspend fun seedActiveJob(
        id: String,
        title: String = "Video $id",
        state: JobState = JobState.POSTPROCESSING,
        attempt: Int = 1,
        createdAt: Long = System.currentTimeMillis(),
        destinationTreeUri: String? = null,
    ): DownloadJob {
        val job = DownloadJob(
            id = id,
            request = DownloadRequest(
                url = "https://example.com/watch?v=$id",
                mode = MediaMode.VIDEO,
                quality = VideoQuality.P1080,
                destinationTreeUri = destinationTreeUri,
            ),
            state = state,
            title = title,
            attempt = attempt,
            publishStarted = state == JobState.POSTPROCESSING,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        assertTrue(repository.saveJobs(listOf(job), setOf(id)).success)
        repository.loadCache()
        rebuildManager(repository, ops)
        return job
    }

    private suspend fun publishAndCommitDone(
        job: DownloadJob,
        requestedName: String = "${job.title}.mp4",
        bytes: ByteArray = ByteArray(256) { (it % 239).toByte() },
    ): String {
        val staged = makeStagingFile(job.id, "staged.mp4", bytes)
        val published = publisher.publishAll(
            files = listOf(staged to requestedName),
            destinationTreeUri = job.request.destinationTreeUri,
            jobId = job.id,
            attemptNumber = job.attempt,
        )
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = bytes.size.toLong(),
                reportedTotalBytes = bytes.size.toLong(),
                reportedTotalIsExact = true,
                actualFinalFileBytes = bytes.size.toLong(),
                outputUris = published.map { it.uri.toString() },
                transactionIds = published.mapTo(HashSet()) { it.transactionId },
                attemptNumber = job.attempt,
                batchId = published.first().batchId,
            ),
        )
        return published.single().uri.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // Issue A Reproduction Tests
    // -------------------------------------------------------------------------

    @Test
    fun issueA_mappingPagingSourceInvalidatesWhenHistoryRowsDeleted() = runBlocking {
        val j1 = seedActiveJob("page-del-1", createdAt = 100L)
        val j2 = seedActiveJob("page-del-2", createdAt = 200L)
        publishAndCommitDone(j1)
        publishAndCommitDone(j2)

        val pagingSource = repository.historyPagingSource()
        val invalidated = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        pagingSource.registerInvalidatedCallback {
            invalidated.set(true)
            latch.countDown()
        }

        val page = pagingSource.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page
        assertEquals(2, page.data.size)

        val deleteResult = manager.deleteJobWithFiles(j1.id)
        assertEquals(1, deleteResult.deletedRecordsCount)
        assertEquals(1, deleteResult.deletedFilesCount)
        assertTrue(
            "Room table deletion must invalidate MappingPagingSource so Compose History UI refreshes",
            latch.await(2, TimeUnit.SECONDS) && invalidated.get(),
        )
    }

    @Test
    fun issueA_failedPhysicalDeletionRetainsHistoryRecordAndDurableCleanupObligation() = runBlocking {
        val job = seedActiveJob("del-fail-1", createdAt = 100L)
        val uriText = publishAndCommitDone(job)
        val uri = Uri.parse(uriText)
        assertTrue(ops.fileExists(uri))

        ops.failDelete = true
        val result = manager.deleteJobWithFiles(job.id)

        assertEquals(1, result.failedFilesCount)
        assertEquals(0, result.deletedRecordsCount)
        assertTrue("Provider file must still exist after failed delete", ops.fileExists(uri))
        assertNotNull(
            "History DB row must NOT be deleted when physical deletion fails",
            database.jobDao().byId(job.id),
        )
        assertTrue(
            "Manager jobs StateFlow must still retain job after failed physical deletion",
            manager.jobs.value.any { it.id == job.id },
        )
        val cleanupTasks = database.cleanupTaskDao().forJob(job.id)
        assertTrue(
            "Failed physical deletion must record a durable cleanup obligation in Room",
            cleanupTasks.isNotEmpty(),
        )
    }

    @Test
    fun issueA_missingPhysicalFileDoesNotFailDeletion() = runBlocking {
        val job = seedActiveJob("del-missing-1", createdAt = 100L)
        val uriText = publishAndCommitDone(job)
        val uri = Uri.parse(uriText)
        // Simulate user deleting file in Files app before clearing VRKA History
        assertTrue(ops.deleteUri(uri))
        assertFalse(ops.fileExists(uri))

        val result = manager.deleteJobWithFiles(job.id)

        assertEquals(0, result.failedFilesCount)
        assertEquals(1, result.deletedRecordsCount)
        assertNull(database.jobDao().byId(job.id))
        assertFalse(manager.jobs.value.any { it.id == job.id })
    }

    @Test
    fun issueA_retriedDoneJobWithEarlierFailedPublicationAttemptCanBeDeletedFromHistory() = runBlocking {
        val job = seedActiveJob("retried-del-1", attempt = 1, createdAt = 100L)
        val failedEntry = PublicationEntry(
            transactionId = "tx-attempt1-failed",
            jobId = job.id,
            outputIndex = 0,
            totalOutputs = 1,
            intendedFilename = "Video.mp4",
            pendingFilename = ".vrka_pending_tx-attempt1-failed_Video.mp4",
            stagingPath = File(stagingRoot, "${job.id}/staged.mp4").absolutePath,
            expectedSizeBytes = 128L,
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.CREATED,
            providerUri = null,
            copiedBytes = 0L,
            status = PublicationTransactionStatus.FAILED,
            attemptNumber = 1,
            attemptId = JobJsonCodec.attemptIdFor(job.id, 1),
            batchId = "batch-attempt1-failed",
            errorMessage = "Simulated failure before finalization",
        )
        repository.publicationStore.recordBatchIntents(listOf(failedEntry))
        repository.publicationStore.markFailed(failedEntry.transactionId, "Simulated failure")

        // Now user retried -> attempt 2 succeeded
        val retryJob = job.copy(attempt = 2, state = JobState.POSTPROCESSING)
        assertTrue(repository.saveJobs(listOf(retryJob), setOf(retryJob.id)).success)
        repository.loadCache()
        rebuildManager(repository, ops)
        publishAndCommitDone(retryJob)

        val result = manager.deleteJobWithFiles(retryJob.id)
        assertEquals(0, result.failedFilesCount)
        assertEquals(1, result.deletedRecordsCount)
        assertNull(database.jobDao().byId(retryJob.id))
    }

    // -------------------------------------------------------------------------
    // Issue B Reproduction Tests
    // -------------------------------------------------------------------------

    @Test
    fun issueB_mediaStoreDuplicateDisplayNameCollidingAcrossQueuedDownloadsAllocatesUniqueName() = runBlocking {
        // Real Android Q+ MediaStore auto-renames an un-pended insert when DISPLAY_NAME collides in the
        // same RELATIVE_PATH collection (e.g., "Same Title.mp4" -> "Same Title (1).mp4"), causing
        // verifyMediaStoreFinalized(uri, "Same Title.mp4") to see a MISMATCH and fail Download #2+.
        val autoRenamingOps = object : PublicationStorageOps by ops {
            override fun updateMediaStore(
                uri: Uri,
                values: ContentValues,
                displayName: String?,
                isPending: Boolean?,
            ): Int {
                val targetName = if (displayName != null && isPending == false) {
                    val collision = ops.mediaItems.values.any {
                        it.uri != uri && !it.isPending && it.displayName == displayName
                    }
                    if (collision) {
                        val dot = displayName.lastIndexOf('.')
                        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
                        val ext = if (dot > 0) displayName.substring(dot) else ""
                        "$stem (1)$ext"
                    } else {
                        displayName
                    }
                } else {
                    displayName
                }
                return ops.updateMediaStore(uri, values, targetName, isPending)
            }
        }
        val j1 = seedActiveJob("ms-col-1", title = "Same Title", createdAt = 100L)
        val j2 = seedActiveJob("ms-col-2", title = "Same Title", createdAt = 200L)
        rebuildManager(repository, autoRenamingOps)

        val f1 = makeStagingFile(j1.id, "f1.mp4", ByteArray(64) { 1 })
        val out1 = publisher.publishAll(
            files = listOf(f1 to "Same Title.mp4"),
            destinationTreeUri = null,
            jobId = j1.id,
            attemptNumber = 1,
        )
        assertEquals("Same Title.mp4", autoRenamingOps.queryDisplayName(out1.single().uri))

        val f2 = makeStagingFile(j2.id, "f2.mp4", ByteArray(64) { 2 })
        val out2 = publisher.publishAll(
            files = listOf(f2 to "Same Title.mp4"),
            destinationTreeUri = null,
            jobId = j2.id,
            attemptNumber = 1,
        )
        assertEquals("Same Title (2).mp4", autoRenamingOps.queryDisplayName(out2.single().uri))
    }

    @Test
    fun issueB_terminalCommitSets100PercentProgressAndDoesNotClobberConcurrentActiveJob() = runBlocking {
        // Seed job-1 (DONE commit target) and job-2 (concurrently downloading in memory)
        val j1 = seedActiveJob("comm-1", state = JobState.POSTPROCESSING, createdAt = 100L)
        // Save j1 + j2 into DB first as QUEUED/POSTPROCESSING, then advance j2 ONLY in memory!
        val j2 = DownloadJob(
            id = "comm-2",
            request = DownloadRequest(url = "https://example.com/watch?v=comm-2"),
            state = JobState.QUEUED,
            title = "Job 2",
            progress = 0f,
            createdAt = 200L,
            updatedAt = 200L,
        )
        assertTrue(repository.saveJobs(listOf(j1, j2), setOf(j1.id, j2.id)).success)
        repository.loadCache()
        rebuildManager(repository, ops)

        // Advance j2's in-memory progress while j1 is finishing publication
        val field = VrkaDownloadManager::class.java.getDeclaredField("_jobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobsFlow = field.get(manager) as MutableStateFlow<List<DownloadJob>>
        jobsFlow.value = jobsFlow.value.map {
            if (it.id == "comm-2") it.copy(state = JobState.DOWNLOADING, progress = 63f, detail = "Downloading") else it
        }

        val staged1 = makeStagingFile(j1.id, "v1.mp4", ByteArray(96) { 3 })
        val pub1 = publisher.publishAll(listOf(staged1 to "Video 1.mp4"), null, j1.id, 1)
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = j1.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 96L,
                reportedTotalBytes = 96L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 96L,
                outputUris = pub1.map { it.uri.toString() },
                transactionIds = pub1.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = pub1.single().batchId,
            ),
        )

        val committedJ1 = database.jobDao().byId(j1.id)!!
        assertEquals("Committed DONE job must persist 100f progress, not 1f", 100f, committedJ1.progress, 0.01f)
        val liveJ2 = manager.jobs.value.first { it.id == "comm-2" }
        assertEquals(
            "Committing job-1 must NOT overwrite concurrent active job-2 in-memory state",
            JobState.DOWNLOADING,
            liveJ2.state,
        )
        assertEquals(63f, liveJ2.progress, 0.01f)
    }

    @Test
    fun issueB_pumpQueueNeverLeaksRunningSlotOnFastCompletingJobs() = runBlocking {
        // ASK_EVERY_TIME makes process() return cleanly in microseconds as WAITING_FOR_USER.
        // Under eager launch, process() can finish and run finally { activeRunningJobs.remove(id) }
        // before activeRunningJobs[id] = runningJob executes, permanently stalling sequential queue!
        settingsState.value = AppSettings(
            saveLocationMode = SaveLocationMode.ASK_EVERY_TIME,
            concurrentDownloadsEnabled = false,
            outputTreeUri = "",
        )
        val jobIds = (1..12).map { idx ->
            val res = manager.enqueue(DownloadRequest(url = "https://example.com/fast/$idx"))
            assertTrue(res is EnqueueResult.Accepted)
            (res as EnqueueResult.Accepted).jobId
        }
        withTimeout(10_000L) {
            while (manager.jobs.value.any { it.id in jobIds && it.state == JobState.QUEUED }) {
                kotlinx.coroutines.delay(25L)
            }
        }
        val states = manager.jobs.value.filter { it.id in jobIds }.map { it.state }
        assertTrue("All fast-completing sequential jobs must be dispatched without stalling: $states", states.all { it == JobState.WAITING_FOR_USER })
        val runningField = VrkaDownloadManager::class.java.getDeclaredField("activeRunningJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val runningMap = runningField.get(manager) as Map<*, *>
        assertTrue("activeRunningJobs must not leak entries after completion: ${runningMap.keys}", runningMap.isEmpty())
    }
}
