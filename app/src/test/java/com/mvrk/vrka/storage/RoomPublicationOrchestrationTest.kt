package com.mvrk.vrka.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VrkaDownloadManager
import com.mvrk.vrka.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPublicationOrchestrationTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var publisher: OutputPublisher
    private lateinit var manager: VrkaDownloadManager
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File

    private fun deleteDb() {
        val base = context.getDatabasePath(VrkaDatabase.NAME)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun buildDb(): VrkaDatabase =
        Room.databaseBuilder(context, VrkaDatabase::class.java, VrkaDatabase.NAME)
            .allowMainThreadQueries()
            .build()

    private fun makePublisher(journal: PublicationStore): OutputPublisher = OutputPublisher(
        context = context,
        settingsRepository = settings,
        journal = journal,
        ops = ops,
        sdkInt = 34,
        mediaStoreCollection = Uri.parse("content://media/external/downloads"),
    )

    private fun makeManager(repo: RoomStorageRepository): VrkaDownloadManager =
        VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repo,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repo.cleanupStore,
            autoRecoverOnInit = false,
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDb()
        testDir = File(context.cacheDir, "room-orchestration-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging")
        settings = SettingsRepository(context, MutableStateFlow(AppSettings(outputTreeUri = "")))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        publisher = makePublisher(repository.publicationStore)
        manager = makeManager(repository)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        deleteDb()
        testDir.deleteRecursively()
    }

    @Test
    fun publishAllCommitAndFreshRuntimeRecoveryAreDurableAndIdempotent() = runBlocking {
        val job = DownloadJob(
            id = "room-orch-job",
            request = DownloadRequest(url = "https://example.com/video", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = JobState.POSTPROCESSING,
            detail = "Publishing",
            publishStarted = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        assertTrue(repository.saveJobs(listOf(job), emptySet()).success)
        repository.loadCache()
        manager = makeManager(repository)
        val staging = File(File(stagingRoot, job.id), "video.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(256))
        }

        val published = publisher.publishAll(
            files = listOf(staging to "video.mp4"),
            destinationTreeUri = null,
            jobId = job.id,
            attemptNumber = job.attempt,
        ).single()
        assertTrue("publishAll must retain staging for the terminal commit", staging.exists())
        assertEquals(1, ops.insertCount)

        val committed = manager.commitTerminalPublishedJob(
            jobId = job.id,
            detail = "Saved to Downloads/VRKA",
            downloadedBytes = 256L,
            reportedTotalBytes = 256L,
            reportedTotalIsExact = true,
            actualFinalFileBytes = 256L,
            outputUris = listOf(published.uri.toString()),
            transactionIds = setOf(published.transactionId),
            attemptNumber = job.attempt,
            batchId = published.batchId,
        )
        assertTrue(committed)
        assertFalse(staging.exists())
        assertEquals(JobState.DONE, repository.cachedJobs().single().state)
        assertEquals(0, database.cleanupTaskDao().all().size)
        assertEquals(1, ops.insertCount)

        // Discard the entire runtime and reopen the same Room database from disk.
        database.close()
        database = buildDb()
        repository = RoomStorageRepository(database)
        repository.loadCache()
        val fresh = makeManager(repository)
        fresh.reconcilePublicationJournalOnStartup()

        assertEquals(1, ops.insertCount)
        assertEquals(JobState.DONE, repository.cachedJobs().single().state)
        assertTrue(repository.publicationStore.unreconciledEntries().isEmpty())
        assertEquals(0, database.cleanupTaskDao().all().size)
    }

    @Test
    fun incompleteBatchIsNotMarkedDoneAndRemainsRecoverable() = runBlocking {
        val job = DownloadJob(
            id = "room-incomplete-job",
            request = DownloadRequest(url = "https://example.com/incomplete", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = JobState.POSTPROCESSING,
            detail = "Publishing",
            publishStarted = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        assertTrue(repository.saveJobs(listOf(job), emptySet()).success)
        repository.loadCache()
        manager = makeManager(repository)
        val staged = File(File(stagingRoot, job.id), "a.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(10))
        }
        val entry = com.mvrk.vrka.PublicationEntry(
            transactionId = "tx-incomplete",
            jobId = job.id,
            outputIndex = 0,
            totalOutputs = 2,
            intendedFilename = "a.mp4",
            pendingFilename = ".vrka_pending_tx-incomplete_a.mp4",
            stagingPath = staged.absolutePath,
            expectedSizeBytes = 10L,
            providerType = com.mvrk.vrka.PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = com.mvrk.vrka.PublicationPhase.CREATED,
            attemptNumber = 1,
            attemptId = JobJsonCodec.attemptIdFor(job.id, 1),
            batchId = "batch-incomplete",
        )
        repository.publicationStore.recordBatchIntents(listOf(entry))

        val committed = manager.commitTerminalPublishedJob(
            jobId = job.id,
            detail = "Bad completion",
            downloadedBytes = 10L,
            reportedTotalBytes = 10L,
            reportedTotalIsExact = true,
            actualFinalFileBytes = 10L,
            outputUris = emptyList(),
            transactionIds = setOf("tx-incomplete"),
            attemptNumber = 1,
            batchId = "batch-incomplete",
        )
        assertFalse("An incomplete batch must never become DONE", committed)
        assertEquals(JobState.POSTPROCESSING, repository.cachedJobs().single().state)
        assertEquals(1, repository.publicationStore.unreconciledEntries().size)
    }
}
