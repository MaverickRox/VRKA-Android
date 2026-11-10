package com.mvrk.vrka.storage

import android.content.ContentValues
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
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournalLoadState
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationTransactionStatus
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.VrkaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageDefectRemediationTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var ops: PublicationJournalRecoveryTest.TestPublicationStorageOps
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDb()
        testDir = File(context.cacheDir, "storage-defects-${System.nanoTime()}").apply { mkdirs() }
        ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        stagingRoot = File(testDir, "files/staging")
        settings = SettingsRepository(context, MutableStateFlow(AppSettings(outputTreeUri = "")))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        deleteDb()
        testDir.deleteRecursively()
    }

    private suspend fun reopen(): RoomStorageRepository {
        database.close()
        database = buildDb()
        repository = RoomStorageRepository(database)
        repository.loadCache()
        ops.failDelete = false
        return repository
    }

    private fun managerFor(repo: RoomStorageRepository): VrkaDownloadManager {
        val publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = repo.publicationStore,
            ops = ops,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse("content://media/external/downloads"),
        )
        return VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repo,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repo.cleanupStore,
            autoRecoverOnInit = false,
        )
    }

    private suspend fun seedJob(id: String, attempt: Int = 1, state: JobState = JobState.POSTPROCESSING) {
        val job = DownloadJob(
            id = id,
            request = DownloadRequest(url = "https://example.com/$id", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = state,
            attempt = attempt,
            publishStarted = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        assertTrue(repository.saveJobs(listOf(job), emptySet()).success)
        repository.loadCache()
    }

    private data class Seed(
        val entry: PublicationEntry,
        val uri: Uri,
    )

    private suspend fun seedFinalized(
        jobId: String,
        txId: String,
        batchId: String,
        attemptNumber: Int = 1,
        index: Int = 0,
        total: Int = 1,
        createdAt: Long = 1_000L,
        size: Int = 32,
    ): Seed {
        val pending = ".vrka_pending_${txId}_video-$index.mp4"
        val finalName = "video-$index.mp4"
        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues(),
            displayName = pending,
            isPending = true,
        )!!
        val bytes = ByteArray(size) { (it % 251).toByte() }
        ops.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        ops.mediaItems.getValue(uri.toString()).apply {
            displayName = finalName
            isPending = false
        }
        val entry = PublicationEntry(
            transactionId = txId,
            jobId = jobId,
            outputIndex = index,
            totalOutputs = total,
            intendedFilename = finalName,
            pendingFilename = pending,
            stagingPath = File(stagingRoot, "$jobId/$finalName").absolutePath,
            expectedSizeBytes = bytes.size.toLong(),
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.FINALIZED,
            providerUri = uri.toString(),
            copiedBytes = bytes.size.toLong(),
            status = PublicationTransactionStatus.COMPLETED,
            attemptNumber = attemptNumber,
            attemptId = JobJsonCodec.attemptIdFor(jobId, attemptNumber),
            batchId = batchId,
            expectedSha256 = sha256(bytes),
            observedSha256 = sha256(bytes),
            verificationLevel = "HASH_VERIFIED",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        repository.publicationStore.recordBatchIntents(listOf(entry))
        return Seed(entry, uri)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun indexesFromDifferentAttemptsNeverCompleteAJob() = runBlocking {
        seedJob("mixed-attempt", attempt = 2)
        val old = seedFinalized("mixed-attempt", "tx-old-attempt", "batch-old", attemptNumber = 1, index = 0, total = 2)
        val current = seedFinalized("mixed-attempt", "tx-current-attempt", "batch-current", attemptNumber = 2, index = 1, total = 2, createdAt = 2_000L)

        val repo = reopen()
        managerFor(repo).reconcilePublicationJournalOnStartup()

        assertFalse(repo.cachedJobs().single { it.id == "mixed-attempt" }.state == JobState.DONE)
        assertTrue("Old provider object must be preserved", ops.fileExists(old.uri))
        assertTrue("Current provider object must be preserved", ops.fileExists(current.uri))
        assertEquals(1, repo.publicationStore.unreconciledEntries().size)
        assertEquals("tx-old-attempt", repo.publicationStore.unreconciledEntries().single().transactionId)
    }

    @Test
    fun indexesFromDifferentBatchesOfTheSameAttemptNeverCompleteAJob() = runBlocking {
        seedJob("mixed-batch", attempt = 1)
        seedFinalized("mixed-batch", "tx-batch-a", "batch-a", index = 0, total = 2, createdAt = 1_000L)
        seedFinalized("mixed-batch", "tx-batch-b", "batch-b", index = 1, total = 2, createdAt = 2_000L)

        val repo = reopen()
        managerFor(repo).reconcilePublicationJournalOnStartup()

        assertFalse(repo.cachedJobs().single { it.id == "mixed-batch" }.state == JobState.DONE)
    }

    @Test
    fun oneCompleteOrderedBatchRecoversToDone() = runBlocking {
        seedJob("complete-batch", attempt = 1)
        val first = seedFinalized("complete-batch", "tx-complete-a", "batch-complete", index = 0, total = 2)
        val second = seedFinalized("complete-batch", "tx-complete-b", "batch-complete", index = 1, total = 2, createdAt = 1_100L)

        val repo = reopen()
        managerFor(repo).reconcilePublicationJournalOnStartup()

        val durable = repo.cachedJobs().single { it.id == "complete-batch" }
        assertEquals(JobState.DONE, durable.state)
        assertEquals(listOf(first.uri.toString(), second.uri.toString()), durable.outputUris)
        assertTrue(repo.publicationStore.unreconciledEntries().isEmpty())
    }

    @Test
    fun missingIndexKeepsJobIncompleteAndPreservesOutputs() = runBlocking {
        seedJob("missing-index", attempt = 1)
        val first = seedFinalized("missing-index", "tx-missing-a", "batch-missing", index = 0, total = 3)
        val third = seedFinalized("missing-index", "tx-missing-c", "batch-missing", index = 2, total = 3, createdAt = 1_100L)

        val repo = reopen()
        managerFor(repo).reconcilePublicationJournalOnStartup()

        val durable = repo.cachedJobs().single { it.id == "missing-index" }
        assertFalse(durable.state == JobState.DONE)
        assertTrue(ops.fileExists(first.uri))
        assertTrue(ops.fileExists(third.uri))
    }

    @Test
    fun stagingCleanupSurvivesHistoryDeletionAndCompletesAfterReopen() = runBlocking {
        seedJob("cleanup-job", attempt = 1)
        val seeded = seedFinalized("cleanup-job", "tx-cleanup", "batch-cleanup", index = 0, total = 1)
        val staging = File(stagingRoot, "cleanup-job/video-0.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(32))
        }
        assertTrue(
            repository.commitTerminalPublishedJob(
                jobId = "cleanup-job",
                attemptNumber = 1,
                batchId = "batch-cleanup",
                outputUris = listOf(seeded.uri.toString()),
                detail = "Saved",
                downloadedBytes = 32L,
                reportedTotalBytes = 32L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 32L,
                subtitleStatus = null,
            ),
        )
        assertEquals(1, database.cleanupTaskDao().all().size)

        assertTrue(repository.deleteJobs(listOf("cleanup-job")))
        assertEquals(null, database.jobDao().byId("cleanup-job"))
        assertEquals("Staging cleanup must outlive the History row", 1, database.cleanupTaskDao().all().size)

        val repo = reopen()
        managerFor(repo).reconcilePublicationJournalOnStartup()

        assertFalse(staging.exists())
        assertEquals(0, database.cleanupTaskDao().all().size)
    }

    @Test
    fun providerCleanupBlocksHistoryAndClearJob() = runBlocking {
        seedJob("provider-cleanup", attempt = 1, state = JobState.FAILED)
        val pending = ".vrka_pending_tx-provider_cleanup-video.mp4"
        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues(),
            displayName = pending,
            isPending = true,
        )!!
        ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(5)) }
        repository.publicationStore.recordBatchIntents(
            listOf(
                PublicationEntry(
                    transactionId = "tx-provider",
                    jobId = "provider-cleanup",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    pendingFilename = pending,
                    stagingPath = File(stagingRoot, "provider-cleanup/video.mp4").absolutePath,
                    expectedSizeBytes = 32L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.DATA_COPIED,
                    providerUri = uri.toString(),
                    copiedBytes = 5L,
                    status = PublicationTransactionStatus.CLEANUP_PENDING,
                    attemptNumber = 1,
                    attemptId = JobJsonCodec.attemptIdFor("provider-cleanup", 1),
                    batchId = "batch-provider",
                ),
            ),
        )
        repository.publicationStore.markCleanupPending("tx-provider", "delete failed")

        assertFalse(repository.deleteJobs(listOf("provider-cleanup")))
        assertNotNull(database.jobDao().byId("provider-cleanup"))
        repository.publicationStore.clearJob("provider-cleanup")
        assertEquals(1, database.publicationOutputDao().forJob("provider-cleanup").size)
        assertTrue(ops.fileExists(uri))

        val manager = managerFor(repository)
        manager.deleteHistoryRecord("provider-cleanup")
        kotlinx.coroutines.withTimeout(5_000) {
            while (manager.diagnostics.value.none { it.stage == "History deletion" }) {
                kotlinx.coroutines.delay(25)
            }
        }
        assertTrue(manager.diagnostics.value.any { it.stage == "History deletion" })
    }

    @Test
    fun deadLetterProviderCleanupIsVisibleAndManuallyRetryable() = runBlocking {
        seedJob("dead-letter-job", attempt = 1)
        val pending = ".vrka_pending_tx-dead-letter-video.mp4"
        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues(),
            displayName = pending,
            isPending = true,
        )!!
        ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(5)) }
        repository.publicationStore.recordBatchIntents(
            listOf(
                PublicationEntry(
                    transactionId = "tx-dead-letter",
                    jobId = "dead-letter-job",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    pendingFilename = pending,
                    stagingPath = File(stagingRoot, "dead-letter-job/video.mp4").absolutePath,
                    expectedSizeBytes = 32L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.DATA_COPIED,
                    providerUri = uri.toString(),
                    copiedBytes = 5L,
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = JobJsonCodec.attemptIdFor("dead-letter-job", 1),
                    batchId = "batch-dead-letter",
                ),
            ),
        )
        ops.failDelete = true
        repeat(10) {
            managerFor(repository).reconcilePublicationJournalOnStartup()
        }
        val dead = repository.cleanupStore.deadLetters().single { it.transactionId == "tx-dead-letter" }
        assertTrue(dead.deadLetter)
        assertTrue(dead.retryCount >= 10)

        val repo = reopen()
        val manager = managerFor(repo)
        manager.reconcilePublicationJournalOnStartup()
        manager.refreshDeadLetterCleanupTasks()
        assertTrue("Dead-letter work must remain visible", manager.deadLetterCleanupTasks.value.isNotEmpty())
        assertTrue("Dead-letter cleanup must not be retried automatically", ops.fileExists(uri))

        ops.failDelete = false
        assertTrue(manager.retryDeadLetterCleanup(dead.taskId))
        assertFalse(ops.fileExists(uri))
        assertTrue(repo.cleanupStore.deadLetters().none { it.transactionId == "tx-dead-letter" })
    }

    @Test
    fun corruptRoomJobIsQuarantinedAndShownWithoutCrashingPaging() = runBlocking {
        seedJob("valid-job", attempt = 1, state = JobState.DONE)
        val validId = "valid-job"
        val valid = database.jobDao().byId(validId)!!
        database.jobDao().upsert(
            valid.copy(
                id = "corrupt-job",
                state = JobState.DONE.name,
                subtitleStatus = "NOT_A_STATUS",
                requestJson = "{\"url\":\"https://example.com/secret?token=abc\"}",
            ),
        )

        val report = repository.loadCache()
        assertEquals(1, report.quarantinedJobs)
        val cached = repository.cachedJobs()
        assertEquals(2, cached.size)
        val damaged = cached.single { it.id == "corrupt-job" }
        assertTrue(damaged.recoveryRequired)
        assertEquals("Damaged history record", damaged.title)
        assertTrue(database.migrationDao().records().any { it.sourceKey == "room_jobs" })

        val source = repository.historyPagingSource()
        val page = source.load(
            androidx.paging.PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false,
            ),
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(2, page.data.size)
        assertTrue(page.data.any { it.recoveryRequired })

        // Persisting the cache must not replace the raw quarantined row with the safe placeholder.
        repository.saveJobs(repository.cachedJobs(), emptySet())
        assertEquals("NOT_A_STATUS", database.jobDao().byId("corrupt-job")!!.subtitleStatus)
        assertTrue(database.migrationDao().records().any { it.sourceKey == "room_jobs" })
    }

    @Test
    fun storageRuntimeReportsPartialReadinessWhenARowIsQuarantined() = runBlocking {
        seedJob("runtime-valid", attempt = 1, state = JobState.DONE)
        val valid = database.jobDao().byId("runtime-valid")!!
        database.jobDao().upsert(
            valid.copy(
                id = "runtime-corrupt",
                state = JobState.DONE.name,
                subtitleStatus = "NOT_A_STATUS",
            ),
        )
        database.close()

        val runtime = StorageRuntime(context, scope)
        try {
            runtime.start()
            val status = runtime.awaitReady()
            assertEquals("Runtime failed: ${status.message}", StorageReadiness.READY, status.readiness)
            assertEquals(1, status.quarantinedJobs)
            assertTrue(status.partialReadiness)
            assertTrue(status.message.isNotBlank())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun storageRuntimeMigratesAFileBackedLegacyJobBeforeReady() = runBlocking {
        val legacyFile = File(context.filesDir, "download_jobs.json")
        legacyFile.writeText(
            """{"schemaVersion":2,"jobs":[{"id":"runtime-legacy-job","state":"DONE","title":"Runtime legacy",
              "request":{"url":"https://example.com/runtime-legacy"},"outputs":[]}]}""".trimIndent(),
        )
        database.close()
        val runtime = StorageRuntime(context, scope)
        try {
            runtime.start()
            val status = runtime.awaitReady()
            assertEquals("Runtime failed: ${status.message}", StorageReadiness.READY, status.readiness)
            assertEquals(1, runtime.repository.cachedJobs().size)
            assertEquals("runtime-legacy-job", runtime.repository.cachedJobs().single().id)
        } finally {
            runtime.close()
            legacyFile.delete()
        }
    }

    @Test
    fun unknownPersistedPublicationEnumFailsClosedAndPreservesRawRow() = runBlocking {
        seedJob("output-enum", attempt = 1)
        val seeded = seedFinalized("output-enum", "tx-output-enum", "batch-output-enum")
        val output = database.publicationOutputDao().byId("tx-output-enum")!!
        database.publicationOutputDao().upsert(output.copy(phase = "UNKNOWN_PHASE"))

        val result = repository.publicationStore.loadWithStatus()
        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertTrue(database.migrationDao().records().any { it.sourceKey == "room_publication_outputs" })
        assertTrue(ops.fileExists(seeded.uri))
    }

    @Test
    fun unknownLegacyJournalEnumIsPreservedInsteadOfDefaulted() = runBlocking {
        val dir = File(testDir, "legacy-enum").apply { mkdirs() }
        File(dir, "download_jobs.json").writeText(
            """{"schemaVersion":2,"jobs":[{"id":"legacy-enum-job","state":"DONE","title":"Legacy",
              "request":{"url":"https://example.com/legacy"},"outputs":[]}]}""".trimIndent(),
        )
        File(dir, "publication_journal.json").writeText(
            """{"schemaVersion":1,"entries":[{"transactionId":"tx-legacy-enum","jobId":"legacy-enum-job",
              "outputIndex":0,"totalOutputs":1,"intendedFilename":"legacy.mp4",
              "pendingFilename":".vrka_pending_tx-legacy-enum_legacy.mp4",
              "stagingPath":"/staging/legacy-enum-job/legacy.mp4","expectedSizeBytes":10,
              "providerType":"MEDIA_STORE","destinationIdentity":"Downloads/VRKA",
              "publicationPhase":"UNKNOWN_PHASE","status":"COMPLETED",
              "providerUri":"content://media/external/downloads/1","copiedBytes":10,
              "createdAt":1,"updatedAt":1}]}""".trimIndent(),
        )
        val db = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomStorageRepository(db)
            LegacyStorageMigrator(dir, db, repo).migrateIfNeeded()
            assertEquals(0, db.publicationOutputDao().all().size)
            assertTrue(db.migrationDao().records().any { it.reason.contains("Unknown or missing legacy publication enum") })
        } finally {
            db.close()
        }
    }

    @Test
    fun unknownLegacyJobEnumIsSkippedAndOriginalFilePreserved() {
        val dir = File(testDir, "legacy-job-enum").apply { mkdirs() }
        val storeFile = File(dir, "download_jobs.json")
        val original = """{"schemaVersion":2,"jobs":[
            {"id":"bad-state","state":"NOT_A_STATE","title":"Bad",
             "request":{"url":"https://example.com/bad"}},
            {"id":"good-state","state":"DONE","title":"Good",
             "request":{"url":"https://example.com/good"},"outputs":[]}
        ]}""".trimIndent()
        storeFile.writeText(original)
        val before = sha256(storeFile.readBytes())

        val result = com.mvrk.vrka.JobStore(storeFile).loadWithStatus(recoverActive = true)

        assertEquals(1, result.jobs.size)
        assertEquals("good-state", result.jobs.single().id)
        assertTrue(result.skippedRecords > 0)
        assertEquals(before, sha256(storeFile.readBytes()))
    }

    @Test
    fun legacyJournalRowsWithoutAttemptAndBatchStayAmbiguous() = runBlocking {
        val dir = File(testDir, "legacy-ambiguous").apply { mkdirs() }
        File(dir, "download_jobs.json").writeText(
            """{"schemaVersion":2,"jobs":[{"id":"legacy-ambiguous-job","state":"POSTPROCESSING","title":"Legacy",
              "request":{"url":"https://example.com/legacy"},"outputs":[]}]}""".trimIndent(),
        )
        File(dir, "publication_journal.json").writeText(
            """{"schemaVersion":1,"entries":[{"transactionId":"tx-legacy-a","jobId":"legacy-ambiguous-job",
              "outputIndex":0,"totalOutputs":2,"intendedFilename":"a.mp4",
              "pendingFilename":".vrka_pending_tx-legacy-a_a.mp4",
              "stagingPath":"/staging/legacy-ambiguous-job/a.mp4","expectedSizeBytes":10,
              "providerType":"MEDIA_STORE","destinationIdentity":"Downloads/VRKA",
              "publicationPhase":"FINALIZED","status":"COMPLETED",
              "providerUri":"content://media/external/downloads/1","copiedBytes":10,
              "createdAt":1,"updatedAt":1},{"transactionId":"tx-legacy-b","jobId":"legacy-ambiguous-job",
              "outputIndex":1,"totalOutputs":2,"intendedFilename":"b.mp4",
              "pendingFilename":".vrka_pending_tx-legacy-b_b.mp4",
              "stagingPath":"/staging/legacy-ambiguous-job/b.mp4","expectedSizeBytes":10,
              "providerType":"MEDIA_STORE","destinationIdentity":"Downloads/VRKA",
              "publicationPhase":"FINALIZED","status":"COMPLETED",
              "providerUri":"content://media/external/downloads/2","copiedBytes":10,
              "createdAt":2,"updatedAt":2}]}""".trimIndent(),
        )
        val db = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomStorageRepository(db)
            LegacyStorageMigrator(dir, db, repo).migrateIfNeeded()
            val entries = repo.publicationStore.entriesForJob("legacy-ambiguous-job")
            assertEquals(2, entries.size)
            assertTrue("Legacy rows must never become a trusted synthetic batch", entries.all { it.legacyAmbiguous })
            assertTrue(entries.all { it.batchId.isNotBlank() })
            assertFalse(
                "An ambiguous legacy batch must not complete a job",
                repo.commitTerminalPublishedJob(
                    jobId = "legacy-ambiguous-job",
                    attemptNumber = 1,
                    batchId = entries.first().batchId,
                    outputUris = entries.sortedBy { it.outputIndex }.map { it.providerUri!! },
                    detail = "Should not commit",
                    downloadedBytes = 20L,
                    reportedTotalBytes = 20L,
                    reportedTotalIsExact = true,
                    actualFinalFileBytes = 20L,
                    subtitleStatus = null,
                ),
            )
        } finally {
            db.close()
        }
    }
}
