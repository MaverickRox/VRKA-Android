package com.mvrk.vrka.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.mvrk.vrka.ProviderObjectPresence
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.PublicationStorageOps
import com.mvrk.vrka.PublicationUriRules
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.VrkaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class HistoryDeletionExhaustiveTest {

    private class ConfigurableStorageOps(
        val delegate: PublicationJournalRecoveryTest.TestPublicationStorageOps,
    ) : PublicationStorageOps by delegate {
        var throwSecurityOnDelete = false
        var refuseDeleteForUris = mutableSetOf<String>()

        override fun deleteUri(uri: Uri): Boolean {
            if (throwSecurityOnDelete) throw SecurityException("Permission revoked for $uri")
            if (uri.toString() in refuseDeleteForUris) return false
            return delegate.deleteUri(uri)
        }

        override fun deleteSafDocument(uri: Uri): Boolean {
            if (throwSecurityOnDelete) throw SecurityException("SAF permission revoked for $uri")
            if (uri.toString() in refuseDeleteForUris) return false
            return delegate.deleteSafDocument(uri)
        }

        override fun probePresence(uri: Uri): ProviderObjectPresence {
            if (throwSecurityOnDelete || delegate.throwOnDelete) return ProviderObjectPresence.UNKNOWN
            return if (delegate.fileExists(uri)) ProviderObjectPresence.EXISTS else ProviderObjectPresence.ABSENT
        }
    }

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var baseOps: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var ops: ConfigurableStorageOps
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
        testDir = File(context.cacheDir, "hist-del-${System.nanoTime()}").apply { mkdirs() }
        baseOps = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir)
        ops = ConfigurableStorageOps(baseOps)
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

    private fun rebuildManager(
        autoRecoverOnInit: Boolean = false,
        customExecutor: (suspend (DownloadJob, File) -> List<File>?)? = null,
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
            autoRecoverOnInit = autoRecoverOnInit,
            runtimeInitOverride = { },
            customDownloadExecutor = customExecutor,
            streamValidationOverride = { _, _ -> },
        )
        return manager
    }

    private fun grantSafPermission(treeUri: String) {
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private suspend fun publishDoneJob(
        id: String,
        title: String = "Video $id",
        treeUri: String? = null,
        fileCount: Int = 1,
        createdAt: Long = 100L,
        playlistGroupId: String? = null,
    ): List<String> {
        val job = DownloadJob(
            id = id,
            request = DownloadRequest(
                url = "https://example.com/watch?v=$id",
                mode = MediaMode.VIDEO,
                quality = VideoQuality.P1080,
                destinationTreeUri = treeUri,
                playlistGroupId = playlistGroupId,
            ),
            state = JobState.POSTPROCESSING,
            title = title,
            attempt = 1,
            publishStarted = true,
            createdAt = createdAt,
            updatedAt = createdAt,
            playlistGroupId = playlistGroupId,
        )
        assertTrue(repository.saveJobs(listOf(job), setOf(id)).success)
        repository.loadCache()
        rebuildManager()

        val files = (1..fileCount).map { idx ->
            val f = File(stagingRoot, "$id/part_$idx.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(128 + idx) { (it + idx).toByte() })
            }
            f to "$title-$idx.mp4"
        }
        val published = publisher.publishAll(files, treeUri, job.id, job.attempt)
        assertTrue(
            manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved",
                downloadedBytes = files.sumOf { it.first.length() },
                reportedTotalBytes = files.sumOf { it.first.length() },
                reportedTotalIsExact = true,
                actualFinalFileBytes = published.sumOf { it.bytes },
                outputUris = published.map { it.uri.toString() },
                transactionIds = published.mapTo(HashSet()) { it.transactionId },
                attemptNumber = job.attempt,
                batchId = published.first().batchId,
            ),
        )
        return published.map { it.uri.toString() }
    }

    @Test
    fun tc01_deleteOneHistoryItem_recordOnlyPreservesFile_fileAndRecordRemovesBoth() = runBlocking {
        val urisKeep = publishDoneJob("single-keep", createdAt = 100L)
        val urisDrop = publishDoneJob("single-drop", createdAt = 200L)

        // Record-only deletion keeps underlying MediaStore file intact
        val recordOnly = manager.deleteHistoryRecordWithResult("single-keep")
        assertEquals(1, recordOnly.deletedRecordsCount)
        assertEquals(0, recordOnly.deletedFilesCount)
        assertEquals(0, recordOnly.failedFilesCount)
        assertNull(database.jobDao().byId("single-keep"))
        assertTrue("Record-only deletion must keep published file", ops.fileExists(Uri.parse(urisKeep.single())))

        // File + record deletion removes both DB row and physical file
        val withFile = manager.deleteJobWithFiles("single-drop")
        assertEquals(1, withFile.deletedRecordsCount)
        assertEquals(1, withFile.deletedFilesCount)
        assertEquals(0, withFile.failedFilesCount)
        assertNull(database.jobDao().byId("single-drop"))
        assertFalse("File+record deletion must remove physical file", ops.fileExists(Uri.parse(urisDrop.single())))
    }

    @Test
    fun tc02_deleteMultipleSelectedHistoryItems() = runBlocking {
        val u1 = publishDoneJob("multi-1", createdAt = 100L)
        val u2 = publishDoneJob("multi-2", createdAt = 200L)
        val u3 = publishDoneJob("multi-3", createdAt = 300L)

        val result = manager.deleteJobsWithFiles(listOf("multi-1", "multi-3"))
        assertEquals(2, result.deletedRecordsCount)
        assertEquals(2, result.deletedFilesCount)
        assertEquals(0, result.failedFilesCount)
        assertNull(database.jobDao().byId("multi-1"))
        assertNull(database.jobDao().byId("multi-3"))
        assertNotNull("Unselected item must remain", database.jobDao().byId("multi-2"))
        assertFalse(ops.fileExists(Uri.parse(u1.single())))
        assertTrue(ops.fileExists(Uri.parse(u2.single())))
        assertFalse(ops.fileExists(Uri.parse(u3.single())))
    }

    @Test
    fun tc03_deleteAllEligibleHistoryItemsPreservingActiveJobs() = runBlocking {
        val u1 = publishDoneJob("elig-1", createdAt = 100L)
        val u2 = publishDoneJob("elig-2", createdAt = 200L)
        val activeJob = DownloadJob(
            id = "active-keep",
            request = DownloadRequest(url = "https://example.com/watch?v=active"),
            state = JobState.DOWNLOADING,
            title = "Active",
            createdAt = 300L,
            updatedAt = 300L,
        )
        assertTrue(repository.saveJobs(listOf(activeJob), setOf(activeJob.id)).success)
        repository.loadCache()
        rebuildManager()

        val cleared = manager.clearAllHistory(deleteFiles = true)
        assertEquals(2, cleared.deletedRecordsCount)
        assertEquals(2, cleared.deletedFilesCount)
        assertEquals(0, cleared.failedFilesCount)
        assertNull(database.jobDao().byId("elig-1"))
        assertNull(database.jobDao().byId("elig-2"))
        assertNotNull("Active job must never be deleted by Clear All History", database.jobDao().byId("active-keep"))
        assertFalse(ops.fileExists(Uri.parse(u1.single())))
        assertFalse(ops.fileExists(Uri.parse(u2.single())))
    }

    @Test
    fun tc04_deleteMediaStoreBackedItem() = runBlocking {
        val uris = publishDoneJob("ms-del", createdAt = 100L)
        val uriText = uris.single()
        assertTrue(PublicationUriRules.isMediaStoreUri(Uri.parse(uriText)))
        assertTrue(baseOps.mediaItems.containsKey(uriText))

        val res = manager.deleteJobWithFiles("ms-del")
        assertEquals(1, res.deletedRecordsCount)
        assertEquals(1, res.deletedFilesCount)
        assertFalse(baseOps.mediaItems.containsKey(uriText))
        assertTrue(baseOps.deletedUris.contains(Uri.parse(uriText)))
    }

    @Test
    fun tc05_deleteSafBackedItem() = runBlocking {
        val tree = "content://saf/tree/root"
        grantSafPermission(tree)
        val uris = publishDoneJob("saf-del", treeUri = tree, createdAt = 100L)
        val uriText = uris.single()
        assertTrue(baseOps.safItems.containsKey(uriText))

        val res = manager.deleteJobWithFiles("saf-del")
        assertEquals(1, res.deletedRecordsCount)
        assertEquals(1, res.deletedFilesCount)
        assertFalse(baseOps.safItems.containsKey(uriText))
        assertNull(database.jobDao().byId("saf-del"))
    }

    @Test
    fun tc06_deleteItemWhosePhysicalFileIsAlreadyMissing() = runBlocking {
        val uris = publishDoneJob("missing-del", createdAt = 100L)
        baseOps.mediaItems.remove(uris.single()) // User removed file externally

        val res = manager.deleteJobWithFiles("missing-del")
        assertEquals(1, res.deletedRecordsCount)
        assertEquals(0, res.deletedFilesCount)
        assertEquals(0, res.failedFilesCount)
        assertNull(database.jobDao().byId("missing-del"))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }

    @Test
    fun tc07_deleteItemWhenStoragePermissionsHaveChangedRetainsRecordAndObligation() = runBlocking {
        val tree = "content://saf/tree/root"
        grantSafPermission(tree)
        val uris = publishDoneJob("perm-revoked", treeUri = tree, createdAt = 100L)
        ops.throwSecurityOnDelete = true

        val res = manager.deleteJobWithFiles("perm-revoked")
        assertEquals(0, res.deletedRecordsCount)
        assertEquals(1, res.failedFilesCount)
        assertNotNull("Record must stay in DB when SecurityException prevents file deletion", database.jobDao().byId("perm-revoked"))
        assertTrue(baseOps.safItems.containsKey(uris.single()))
        assertEquals(1, repository.cleanupStore.pendingHistoryDeletions().size)
    }

    @Test
    fun tc08_deleteItemWhenProviderReturnsError_retriesAndSupportsDeadLetterRecovery() = runBlocking {
        val uris = publishDoneJob("provider-err", createdAt = 100L)
        baseOps.failDelete = true

        // Repeated failures escalate up to MAX_RETRIES -> deadLetter
        repeat(10) {
            val res = manager.deleteJobWithFiles("provider-err")
            assertEquals(0, res.deletedRecordsCount)
            assertEquals(1, res.failedFilesCount)
        }
        val deadLetters = repository.cleanupStore.deadLetters().filter { it.resourceType == "HISTORY_DELETE" }
        assertEquals(1, deadLetters.size)
        assertEquals(10, deadLetters.single().retryCount)

        // Provider recovers -> manual retry completes physical deletion + deletes DB row
        baseOps.failDelete = false
        assertTrue(manager.retryDeadLetterCleanup(deadLetters.single().taskId))
        assertNull(database.jobDao().byId("provider-err"))
        assertFalse(ops.fileExists(Uri.parse(uris.single())))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
        assertTrue(repository.cleanupStore.deadLetters().isEmpty())
    }

    @Test
    fun tc09_deleteHistoryItemWhileAnotherDownloadIsActive() = runBlocking {
        val oldUris = publishDoneJob("hist-old", createdAt = 100L)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rebuildManager(
            customExecutor = { job, dir ->
                started.countDown()
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
                val out = File(dir, "active.mp4").apply { writeBytes(ByteArray(256) { 9 }) }
                listOf(out)
            },
        )
        val enq = manager.enqueue(DownloadRequest(url = "https://example.com/watch?v=concurrent"))
        assertTrue(enq is EnqueueResult.Accepted)
        val activeId = (enq as EnqueueResult.Accepted).jobId
        assertTrue(started.await(5, TimeUnit.SECONDS))

        // Delete finished History item while active download is running
        val delRes = manager.deleteJobWithFiles("hist-old")
        assertEquals(1, delRes.deletedRecordsCount)
        assertNull(database.jobDao().byId("hist-old"))
        assertFalse(ops.fileExists(Uri.parse(oldUris.single())))

        proceed.countDown()
        withTimeout(10_000L) {
            while (database.jobDao().byId(activeId)?.state != JobState.DONE.name) {
                delay(25L)
            }
        }
        assertEquals(JobState.DONE.name, database.jobDao().byId(activeId)?.state)
    }

    @Test
    fun tc10_restartApplicationDuringPendingCleanupTaskCompletesDeletion() = runBlocking {
        val uris = publishDoneJob("restart-cleanup", createdAt = 100L)
        baseOps.throwOnDelete = true
        val initial = manager.deleteJobWithFiles("restart-cleanup")
        assertEquals(0, initial.deletedRecordsCount)
        assertEquals(1, initial.failedFilesCount)

        // Simulate app restart with provider back online
        scope.cancel()
        database.close()
        baseOps.throwOnDelete = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        rebuildManager(autoRecoverOnInit = false)
        manager.reconcilePublicationJournalOnStartup()

        assertNull("Pending cleanup task must delete job row on startup recovery", database.jobDao().byId("restart-cleanup"))
        assertFalse("Pending cleanup task must delete physical file on startup recovery", ops.fileExists(Uri.parse(uris.single())))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }

    @Test
    fun tc11_killProcessMidMultiFileDeletionRecoversRemainingFileAndClearsRow() = runBlocking {
        val uris = publishDoneJob("mid-kill", fileCount = 2, createdAt = 100L)
        val uri1 = uris[0]
        val uri2 = uris[1]

        // Simulate write-ahead cleanup intent + partial physical delete of uri1 + process kill before uri2/DB delete
        repository.cleanupStore.markHistoryDeletionPending("mid-kill", uri1, "", recordFailure = false)
        repository.cleanupStore.markHistoryDeletionPending("mid-kill", uri2, "", recordFailure = false)
        assertTrue(ops.deleteUri(Uri.parse(uri1)))
        repository.cleanupStore.clearHistoryDeletion("mid-kill", uri1)
        // Process dies here while uri2 still exists and mid-kill row is still in Room

        scope.cancel()
        database.close()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        rebuildManager(autoRecoverOnInit = false)
        manager.reconcilePublicationJournalOnStartup()

        assertNull(database.jobDao().byId("mid-kill"))
        assertFalse(ops.fileExists(Uri.parse(uri1)))
        assertFalse(ops.fileExists(Uri.parse(uri2)))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }

    @Test
    fun tc12_databaseRestartPreservesDeletionStateAndPagingSourceConsistency() = runBlocking {
        publishDoneJob("db-reopen-1", createdAt = 100L)
        publishDoneJob("db-reopen-2", createdAt = 200L)
        val paging = repository.historyPagingSource()
        val invalidated = AtomicBoolean(false)
        paging.registerInvalidatedCallback { invalidated.set(true) }
        val firstLoad = paging.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        assertEquals(2, firstLoad.data.size)

        assertEquals(1, manager.deleteJobWithFiles("db-reopen-1").deletedRecordsCount)
        withTimeout(2_000L) {
            while (!invalidated.get()) delay(15L)
        }

        // Restart DB and confirm deletion persisted cleanly
        scope.cancel()
        database.close()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = buildDb()
        repository = RoomStorageRepository(database)
        rebuildManager()
        assertNull(database.jobDao().byId("db-reopen-1"))
        assertNotNull(database.jobDao().byId("db-reopen-2"))
        val afterRestart = repository.historyPagingSource().load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("db-reopen-2"), afterRestart.data.map { it.id })
    }

    @Test
    fun tc13_successfulDeletionLeavesZeroOrphanedFilesAndZeroStagingOrCleanupResidue() = runBlocking {
        val uris = publishDoneJob("no-orphans", fileCount = 3, createdAt = 100L)
        val jobStaging = File(stagingRoot, "no-orphans")
        jobStaging.deleteRecursively()

        val result = manager.deleteJobWithFiles("no-orphans")
        assertEquals(1, result.deletedRecordsCount)
        assertEquals(3, result.deletedFilesCount)
        assertEquals(0, result.failedFilesCount)
        assertTrue("No MediaStore files may remain", baseOps.mediaItems.isEmpty())
        assertTrue("No SAF files may remain", baseOps.safItems.isEmpty())
        assertTrue("No publication outputs may remain", database.publicationOutputDao().all().isEmpty())
        assertTrue("No batches may remain", database.publicationBatchDao().forJob("no-orphans").isEmpty())
        assertTrue("No attempts may remain", database.attemptDao().forJob("no-orphans").isEmpty())
        assertTrue("No cleanup tasks may remain", database.cleanupTaskDao().all().none { it.jobId == "no-orphans" && it.resourceType == "HISTORY_DELETE" })
        assertTrue("All 3 URIs must still exist until deleted", uris.all { !ops.fileExists(Uri.parse(it)) })
    }

    @Test
    fun tc14_failedDeletionPreservesRecoveryInfoAndRetriesAreIdempotent() = runBlocking {
        val uris = publishDoneJob("idemp-del", createdAt = 100L)
        ops.refuseDeleteForUris += uris.single()

        val r1 = manager.deleteJobWithFiles("idemp-del")
        val r2 = manager.deleteJobWithFiles("idemp-del")
        assertEquals(0, r1.deletedRecordsCount)
        assertEquals(0, r2.deletedRecordsCount)
        assertNotNull(database.jobDao().byId("idemp-del"))
        val pending = repository.cleanupStore.pendingHistoryDeletions()
        assertEquals("Idempotent duplicate delete must reuse single HISTORY_DELETE row", 1, pending.size)
        assertEquals(2, pending.single().retryCount)

        // Running retryPendingHistoryFileDeletions when still failing preserves obligation
        manager.retryPendingHistoryFileDeletions()
        assertNotNull(database.jobDao().byId("idemp-del"))
        assertEquals(1, repository.cleanupStore.pendingHistoryDeletions().size)

        // Allow deletion, run retryPendingHistoryFileDeletions twice (idempotent!)
        ops.refuseDeleteForUris.clear()
        manager.retryPendingHistoryFileDeletions()
        manager.retryPendingHistoryFileDeletions()
        assertNull(database.jobDao().byId("idemp-del"))
        assertFalse(ops.fileExists(Uri.parse(uris.single())))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }

    @Test
    fun tc15_playlistGroupDeletion_recordOnly_clearsAllRecordsInGroupPreservingFiles() = runBlocking {
        val group = "group-record-only"
        val uris1 = publishDoneJob("pl-rec-1", playlistGroupId = group, createdAt = 100L)
        val uris2 = publishDoneJob("pl-rec-2", playlistGroupId = group, createdAt = 200L)
        val uris3 = publishDoneJob("pl-rec-3", playlistGroupId = group, createdAt = 300L)

        val res = manager.deletePlaylistGroup(group, deleteFiles = false)
        assertEquals(3, res.deletedRecordsCount)
        assertEquals(0, res.deletedFilesCount)
        assertEquals(0, res.failedFilesCount)

        assertNull(database.jobDao().byId("pl-rec-1"))
        assertNull(database.jobDao().byId("pl-rec-2"))
        assertNull(database.jobDao().byId("pl-rec-3"))

        assertTrue(ops.fileExists(Uri.parse(uris1.single())))
        assertTrue(ops.fileExists(Uri.parse(uris2.single())))
        assertTrue(ops.fileExists(Uri.parse(uris3.single())))
    }

    @Test
    fun tc16_playlistGroupDeletion_withFiles_deletesAllRecordsAndFiles() = runBlocking {
        val group = "group-with-files"
        val uris1 = publishDoneJob("pl-file-1", playlistGroupId = group, createdAt = 100L)
        val uris2 = publishDoneJob("pl-file-2", playlistGroupId = group, createdAt = 200L)
        val uris3 = publishDoneJob("pl-file-3", playlistGroupId = group, createdAt = 300L)

        val res = manager.deletePlaylistGroup(group, deleteFiles = true)
        assertEquals(3, res.deletedRecordsCount)
        assertEquals(3, res.deletedFilesCount)
        assertEquals(0, res.failedFilesCount)

        assertNull(database.jobDao().byId("pl-file-1"))
        assertNull(database.jobDao().byId("pl-file-2"))
        assertNull(database.jobDao().byId("pl-file-3"))

        assertFalse(ops.fileExists(Uri.parse(uris1.single())))
        assertFalse(ops.fileExists(Uri.parse(uris2.single())))
        assertFalse(ops.fileExists(Uri.parse(uris3.single())))
    }

    @Test
    fun tc17_safPermissionRevocationAndExternalAbsence_classifiesCleanlyAndRecoversOnRetry() = runBlocking {
        val safTree = "content://saf/tree/root"
        grantSafPermission(safTree)
        val uris = publishDoneJob("saf-sec-drop", treeUri = safTree, createdAt = 100L)
        val uri = Uri.parse(uris.single())

        // Revoke SAF permission simulation
        ops.throwSecurityOnDelete = true
        val failedResult = manager.deleteJobWithFiles("saf-sec-drop")
        assertEquals(0, failedResult.deletedRecordsCount)
        assertEquals(1, failedResult.failedFilesCount)

        // Job retained in DB and task recorded in cleanup store
        assertNotNull(database.jobDao().byId("saf-sec-drop"))
        val pending = repository.cleanupStore.pendingHistoryDeletions()
        assertEquals(1, pending.size)

        // Permission restored, file removed externally (ALREADY_ABSENT)
        ops.throwSecurityOnDelete = false
        ops.deleteSafDocument(uri) // File removed externally
        assertFalse(ops.fileExists(uri))

        // Retry must treat ALREADY_ABSENT as success and clean up record
        manager.retryPendingHistoryFileDeletions()
        assertNull(database.jobDao().byId("saf-sec-drop"))
        assertTrue(repository.cleanupStore.pendingHistoryDeletions().isEmpty())
    }

    @Test
    fun tc18_canonicalPublicationPhases_nonBlockingStatusAllowsDeletion() = runBlocking {
        // Publish job 1 with FINALIZED status
        val uris = publishDoneJob("canonical-phases", createdAt = 100L)
        assertNotNull(database.jobDao().byId("canonical-phases"))

        // Update its publication output to canonical CLEANUP_PENDING
        val outputs = database.publicationOutputDao().forJob("canonical-phases")
        database.publicationOutputDao().upsert(outputs.single().copy(status = "CLEANUP_PENDING"))

        // Delete record only should succeed because CLEANUP_PENDING is in NON_BLOCKING_PUBLICATION_STATUSES
        val res = manager.deleteHistoryRecordWithResult("canonical-phases")
        assertEquals(1, res.deletedRecordsCount)
        assertNull(database.jobDao().byId("canonical-phases"))
    }
}
