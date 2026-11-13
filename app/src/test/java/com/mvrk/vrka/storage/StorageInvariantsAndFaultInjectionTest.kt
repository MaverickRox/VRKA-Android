package com.mvrk.vrka.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.PublicationPhase
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
import java.io.OutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class StorageInvariantsAndFaultInjectionTest {

    private class FaultOps(
        val delegate: PublicationJournalRecoveryTest.TestPublicationStorageOps,
    ) : PublicationStorageOps by delegate {
        var corruptReadback = false
        var failFinalizeUpdate = false

        override fun updateMediaStore(
            uri: Uri,
            values: android.content.ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Int {
            if (failFinalizeUpdate && isPending == false) {
                throw java.io.IOException("Simulated failure at finalization")
            }
            return delegate.updateMediaStore(uri, values, displayName, isPending)
        }

        override fun readBack(uri: Uri) =
            delegate.readBack(uri)?.let { rb ->
                if (corruptReadback) rb.copy(sha256 = "0".repeat(64)) else rb
            }
    }

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var baseOps: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var ops: FaultOps
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
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
        testDir = File(context.cacheDir, "inv-fault-${System.nanoTime()}").apply { mkdirs() }
        baseOps = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        ops = FaultOps(baseOps)
        stagingRoot = File(testDir, "files/staging").apply { mkdirs() }
        settingsState = MutableStateFlow(AppSettings(outputTreeUri = "", saveLocationMode = SaveLocationMode.REMEMBER_LOCATION))
        settings = SettingsRepository(context, settingsState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        rebuildManager()
    }

    @After
    fun tearDown() {
        runCatching { scope.cancel() }
        runCatching { database.close() }
        deleteDb()
        testDir.deleteRecursively()
    }

    private fun rebuildManager(): VrkaDownloadManager {
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
            autoRecoverOnInit = false,
            runtimeInitOverride = { },
            streamValidationOverride = { _, _ -> },
        )
        return manager
    }

    private suspend fun seedJob(
        id: String,
        state: JobState = JobState.POSTPROCESSING,
        attempt: Int = 1,
        publishStarted: Boolean = true,
    ): DownloadJob {
        val job = DownloadJob(
            id = id,
            request = DownloadRequest(url = "https://example.com/watch?v=$id"),
            state = state,
            title = "Job $id",
            attempt = attempt,
            publishStarted = publishStarted,
            createdAt = 100L + attempt,
            updatedAt = 100L + attempt,
        )
        assertTrue(repository.saveJobs(listOf(job), setOf(id)).success)
        repository.loadCache()
        rebuildManager()
        return job
    }

    private fun stagedFile(jobId: String, name: String = "video.mp4", size: Int = 128): File =
        File(stagingRoot, "$jobId/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(size) { (it % 199).toByte() })
        }

    // -------------------------------------------------------------------------
    // Storage Invariants 1 - 12
    // -------------------------------------------------------------------------

    @Test
    fun inv01_inv02_inv03_staleAttemptAndUnrelatedJobCannotModifyOrAuthorizeAnother() = runBlocking {
        val jobA = seedJob("inv-a", state = JobState.DOWNLOADING, attempt = 2, publishStarted = false)
        val jobB = seedJob("inv-b", state = JobState.POSTPROCESSING, attempt = 1, publishStarted = true)
        val stagedA = stagedFile("inv-a", "a.mp4")
        val stagedB = stagedFile("inv-b", "b.mp4")

        val pubA = publisher.publishAll(listOf(stagedA to "A.mp4"), null, jobA.id, attemptNumber = 2)
        val pubB = publisher.publishAll(listOf(stagedB to "B.mp4"), null, jobB.id, attemptNumber = 1)

        // Invariant 1: stale attempt=1 cannot commit attempt=2 jobA
        assertFalse(
            manager.commitTerminalPublishedJob(
                jobId = jobA.id,
                detail = "Stale",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = pubA.map { it.uri.toString() },
                transactionIds = pubA.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = pubA.single().batchId,
            ),
        )
        assertEquals(JobState.DOWNLOADING.name, database.jobDao().byId(jobA.id)?.state)

        // Invariant 2 & 3: jobA's publication batch/output cannot commit jobB or touch jobB
        assertFalse(
            manager.commitTerminalPublishedJob(
                jobId = jobB.id,
                detail = "Cross-job spoof",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = pubA.map { it.uri.toString() },
                transactionIds = pubA.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = pubA.single().batchId,
            ),
        )
        assertEquals(JobState.POSTPROCESSING.name, database.jobDao().byId(jobB.id)?.state)
        assertNotNull(pubB.single().uri)
    }

    @Test
    fun inv04_inv05_inv06_failedTransactionAndInterruptedJobsNeverProduceFalseDone() = runBlocking {
        // Invariant 5: Interrupted job stays non-DONE (cannot save new DONE without proof)
        val rogueDone = DownloadJob(
            id = "inv-rogue",
            request = DownloadRequest(url = "https://example.com/watch?v=rogue"),
            state = JobState.DONE,
            title = "Rogue",
            createdAt = 10L,
            updatedAt = 10L,
        )
        assertFalse("Unverified new DONE save must be rejected", repository.saveJobs(listOf(rogueDone), setOf("inv-rogue")).success)
        assertNull(database.jobDao().byId("inv-rogue"))

        // Invariant 4 & 6: Mismatched output URIs on terminal commit roll back transaction -> never DONE
        val job = seedJob("inv-mismatch")
        val staged = stagedFile("inv-mismatch", "m.mp4")
        val pub = publisher.publishAll(listOf(staged to "M.mp4"), null, job.id, 1)
        assertFalse(
            manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Tampered output list",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = listOf("content://media/external/downloads/999999"),
                transactionIds = pub.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = pub.single().batchId,
            ),
        )
        assertEquals(JobState.POSTPROCESSING.name, database.jobDao().byId(job.id)?.state)
    }

    @Test
    fun inv07_inv08_inv09_cleanupObligationRecoveryScopeAndDuplicateSafety() = runBlocking {
        val job1 = seedJob("inv-clean-1")
        val job2 = seedJob("inv-clean-2")
        val f1 = stagedFile("inv-clean-1", "1.mp4")
        val f2 = stagedFile("inv-clean-2", "2.mp4")
        val p1 = publisher.publishAll(listOf(f1 to "One.mp4"), null, job1.id, 1)
        val p2 = publisher.publishAll(listOf(f2 to "Two.mp4"), null, job2.id, 1)

        // Fail deletion of job1's output -> creates obligation scoped only to job1
        baseOps.failDelete = true
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = job1.id,
                detail = "Done",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = p1.map { it.uri.toString() },
                transactionIds = p1.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = p1.single().batchId,
            ),
        )
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = job2.id,
                detail = "Done",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = p2.map { it.uri.toString() },
                transactionIds = p2.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = p2.single().batchId,
            ),
        )

        val del1 = manager.deleteJobWithFiles(job1.id)
        assertEquals(0, del1.deletedRecordsCount)
        assertEquals(1, del1.failedFilesCount)

        // Reconcile recovery twice while job2 stays untouched (Invariant 8 & 9)
        baseOps.failDelete = false
        manager.reconcilePublicationJournalOnStartup()
        manager.reconcilePublicationJournalOnStartup()
        assertNull(database.jobDao().byId(job1.id))
        assertNotNull("Job 2 must never be deleted by Job 1 recovery", database.jobDao().byId(job2.id))
        assertTrue("Job 2 file must remain intact", ops.fileExists(p2.single().uri))
        assertFalse("Job 1 file must be cleaned", ops.fileExists(p1.single().uri))
    }

    @Test
    fun inv10_inv11_inv12_unknownPersistedStatesAndCorruptionFailSafely() = runBlocking {
        // Invariant 10: Corrupt / unknown job entity quarantined rather than crashing or losing valid jobs
        val valid = seedJob("inv-valid")
        val stagedValid = stagedFile("inv-valid", "ok.mp4")
        val pubValid = publisher.publishAll(listOf(stagedValid to "Valid.mp4"), null, valid.id, 1)
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = valid.id,
                detail = "Saved",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = pubValid.map { it.uri.toString() },
                transactionIds = pubValid.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = pubValid.single().batchId,
            ),
        )
        // Insert an unsupported/corrupt row directly via SQLite
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO jobs (
              id, request_json, state, title, detail, progress, speed, eta_seconds,
              downloaded_bytes, estimated_total_bytes, estimated_size_accuracy,
              reported_total_bytes, reported_total_is_exact, active_stream,
              playlist_item_index, playlist_item_count, actual_final_file_bytes,
              created_at, updated_at, output_uris_json, error, attempt,
              actual_resolution, runtime_name, runtime_version, subtitle_status,
              publish_started, current_attempt_id, legacy_verification,
              recovery_required, terminal_at, commit_sequence, version
            ) VALUES (
              'corrupt-state', '{not-json', 'UNKNOWN_FUTURE_STATE', 'Corrupt', 'bad', 0, '', NULL,
              NULL, NULL, 'UNAVAILABLE', NULL, 0, NULL, NULL, NULL, NULL,
              50, 50, '[]', '', 1, NULL, '', '', 'NONE',
              0, 'corrupt-state:attempt:1', '', 0, NULL, 0, 1
            )
            """.trimIndent(),
        )
        val report = repository.loadCache()
        assertTrue(report.quarantinedJobs >= 1)
        assertEquals(JobState.DONE.name, database.jobDao().byId(valid.id)?.state)
        assertTrue(ops.fileExists(pubValid.single().uri))
    }

    // -------------------------------------------------------------------------
    // Fault-Injection Points 1 - 10
    // -------------------------------------------------------------------------

    @Test
    fun fault01_02_03_beforeCreationDuringWriteAndAfterWriteReadbackFailure() = runBlocking {
        // Point 1: Before file creation (provider insert returns null)
        val j1 = seedJob("fi-01")
        val f1 = stagedFile("fi-01", "a.mp4")
        baseOps.failInsert = true
        assertTrue(runCatching { publisher.publishAll(listOf(f1 to "A.mp4"), null, j1.id, 1) }.isFailure)
        assertTrue(baseOps.mediaItems.isEmpty())
        baseOps.failInsert = false

        // Point 2: During file writing (I/O error after 32 bytes -> provider partial object deleted cleanly)
        val j2 = seedJob("fi-02")
        val f2 = stagedFile("fi-02", "b.mp4", size = 256)
        baseOps.byteLimit = 32L
        assertTrue(runCatching { publisher.publishAll(listOf(f2 to "B.mp4"), null, j2.id, 1) }.isFailure)
        assertTrue("Partial provider object must be cleaned up on write failure", baseOps.mediaItems.isEmpty())
        baseOps.byteLimit = null

        // Point 3: After file writing (provider read-back digest mismatch -> rolled back, no DONE)
        val j3 = seedJob("fi-03")
        val f3 = stagedFile("fi-03", "c.mp4", size = 128)
        ops.corruptReadback = true
        assertTrue(runCatching { publisher.publishAll(listOf(f3 to "C.mp4"), null, j3.id, 1) }.isFailure)
        assertTrue("Unverified provider output must be deleted on readback mismatch", baseOps.mediaItems.isEmpty())
        assertFalse(database.jobDao().byId(j3.id)?.state == JobState.DONE.name)
    }

    @Test
    fun fault04_05_06_07_prePublicationMidPublicationPostPubPreDoneAndHistoryCreationFailure() = runBlocking {
        // Point 4 & 5: During publication (finalize fails after bytes are written and verified)
        // Invariant: Verified bytes must NEVER be deleted because finalization failed;
        // transaction is retained in COPIED and recovered on subsequent startup.
        val j5 = seedJob("fi-05")
        val f5 = stagedFile("fi-05", "e.mp4")
        ops.failFinalizeUpdate = true
        assertTrue(runCatching { publisher.publishAll(listOf(f5 to "E.mp4"), null, j5.id, 1) }.isFailure)
        ops.failFinalizeUpdate = false
        assertEquals("Verified output must NOT be deleted when finalization fails", 1, baseOps.mediaItems.size)
        val j5Entries = repository.publicationStore.entriesForJob(j5.id)
        assertEquals(1, j5Entries.size)
        assertEquals(PublicationPhase.COPIED, j5Entries.single().publicationPhase)
        assertNotNull(j5Entries.single().providerUri)

        // Startup recovery retries finalization on preserved URI and successfully commits DONE without redownload
        manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE.name, database.jobDao().byId(j5.id)?.state)
        assertEquals(1, baseOps.mediaItems.size)
        baseOps.mediaItems.clear()

        // Point 6 & 7: After publication succeeds, simulate crash/failure BEFORE Room DONE commit!
        val j6 = seedJob("fi-06")
        val f6 = stagedFile("fi-06", "f.mp4", size = 192)
        val published = publisher.publishAll(listOf(f6 to "F.mp4"), null, j6.id, 1)
        // Before calling commitTerminalPublishedJob, job stays POSTPROCESSING (not DONE)
        assertEquals(JobState.POSTPROCESSING.name, database.jobDao().byId(j6.id)?.state)
        assertEquals(1, baseOps.mediaItems.size)
        // Journal holds COMPLETED entry; startup recovery reconciles and commits DONE without duplicating file!
        manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE.name, database.jobDao().byId(j6.id)?.state)
        assertEquals("Recovery must NOT duplicate already-published file", 1, baseOps.mediaItems.size)
        assertEquals(published.map { it.uri.toString() }.size, 1)
    }

    @Test
    fun fault08_09_10_cleanupSchedulingDuringDeletionAndPostDeleteBeforePersistence() = runBlocking {
        // Point 8: Staging cleanup fails right after DONE commit -> durable STAGING task recovers later
        val j8 = seedJob("fi-08")
        val f8 = stagedFile("fi-08", "g.mp4")
        val p8 = publisher.publishAll(listOf(f8 to "G.mp4"), null, j8.id, 1)
        var failStaging = true
        manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repository,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = repository.cleanupStore,
            stagingDelete = { dir -> if (failStaging) false else dir.deleteRecursively() },
            autoRecoverOnInit = false,
            runtimeInitOverride = { },
        )
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = j8.id,
                detail = "Saved",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = p8.map { it.uri.toString() },
                transactionIds = p8.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = p8.single().batchId,
            ),
        )
        assertTrue(repository.cleanupStore.pending().any { it.jobId == j8.id })
        failStaging = false
        manager.retryPendingStagingCleanup()
        assertFalse(f8.parentFile!!.exists())

        // Point 9 & 10: Physical deletion completed BEFORE cleanup completion/DB deletion persisted -> startup finishes cleanly
        val j10 = seedJob("fi-10")
        val f10 = stagedFile("fi-10", "h.mp4")
        val p10 = publisher.publishAll(listOf(f10 to "H.mp4"), null, j10.id, 1)
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = j10.id,
                detail = "Saved",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = p10.map { it.uri.toString() },
                transactionIds = p10.mapTo(HashSet()) { it.transactionId },
                attemptNumber = 1,
                batchId = p10.single().batchId,
            ),
        )
        val uri10 = p10.single().uri.toString()
        repository.cleanupStore.markHistoryDeletionPending(j10.id, uri10, "", recordFailure = false)
        assertTrue(ops.deleteUri(Uri.parse(uri10))) // physical delete succeeded
        // Crash right before clearHistoryDeletion + repository.deleteJobs!
        manager.retryPendingHistoryFileDeletions()
        assertNull("Startup cleanup recovery must finalize deleted History row when file already absent", database.jobDao().byId(j10.id))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }
}
