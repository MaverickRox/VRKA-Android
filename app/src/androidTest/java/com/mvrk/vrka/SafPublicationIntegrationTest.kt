package com.mvrk.vrka

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvrk.vrka.storage.PublicationStore
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.VrkaDatabase
import com.mvrk.vrka.storage.JobJsonCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real SAF publication protocol coverage against [TestDocumentsProvider]. This exercises the
 * actual ContentResolver/DocumentsContract path, including providers that replace the document
 * URI on rename, cannot rename, modify the marker, duplicate names, fail child queries, fail
 * readback, fail deletion, or revoke permission.
 */
@RunWith(AndroidJUnit4::class)
class SafPublicationIntegrationTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File
    private lateinit var databaseName: String
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestDocumentsProvider.reset(context)
        val stamp = System.nanoTime()
        databaseName = "saf-publication-$stamp.db"
        database = Room.databaseBuilder(context, VrkaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        repository = RoomStorageRepository(database)
        stagingRoot = File(context.cacheDir, "saf_staging_$stamp").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.permissionRevoked = false
        TestDocumentsProvider.failDelete = false
        TestDocumentsProvider.failReadback = false
        TestDocumentsProvider.failChildQuery = false
        TestDocumentsProvider.clear()
        database.close()
        val dbFile = context.getDatabasePath(databaseName)
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        stagingRoot.deleteRecursively()
        scope.cancel()
    }

    private val publicationStore: PublicationStore by lazy {
        val roomStore = repository.publicationStore
        object : PublicationStore by roomStore {
            override fun recordIntent(entry: PublicationEntry) = recordBatchIntents(listOf(entry))

            override fun recordBatchIntents(batch: List<PublicationEntry>) {
                val missing = batch.map { it.jobId }.distinct().filter { id ->
                    repository.cachedJobs().none { it.id == id }
                }
                if (missing.isNotEmpty()) {
                    val seeded = runBlocking {
                        repository.saveJobs(missing.map(::job), missing.toSet())
                    }
                    check(seeded.success) { "Could not seed Room publication jobs: ${seeded.error}" }
                    runBlocking { repository.loadCache() }
                }
                roomStore.recordBatchIntents(batch)
            }
        }
    }

    private fun newPublisher(): OutputPublisher = OutputPublisher(
        context = context,
        settingsRepository = SettingsRepository(context, scope),
        journal = publicationStore,
        sdkInt = Build.VERSION.SDK_INT,
    )

    private fun stage(jobId: String, name: String, bytes: ByteArray): File {
        val directory = File(stagingRoot, jobId).apply { mkdirs() }
        return File(directory, name).apply { writeBytes(bytes) }
    }

    private fun job(jobId: String) = DownloadJob(
        id = jobId,
        request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
        state = JobState.FAILED,
        publishStarted = true,
        error = INTERRUPTED_DOWNLOAD_ERROR,
    )

    private fun managerFor(job: DownloadJob): VrkaDownloadManager {
        val saved = runBlocking { repository.saveJobs(listOf(job), setOf(job.id)) }
        assertTrue("Seed Room job save must succeed: ${saved.error}", saved.success)
        runBlocking { repository.loadCache() }
        return VrkaDownloadManager(
            context = context,
            settingsRepository = SettingsRepository(context, scope),
            scope = scope,
            customRepository = repository,
            customPublisher = newPublisher(),
            customStagingRoot = stagingRoot,
            autoRecoverOnInit = false,
        )
    }

    private fun durableJob(jobId: String): DownloadJob =
        repository.cachedJobs().single { it.id == jobId }

    @Test
    fun safRenameInPlacePublishesVerifiedOutput() {
        val jobId = "saf-in-place-${System.nanoTime()}"
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        val staging = stage(jobId, "video.mp4", bytes)
        val publisher = newPublisher()

        val published = publisher.publishAll(
            files = listOf(staging to "video.mp4"),
            destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
            jobId = jobId,
        ).single()

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertEquals("HASH_VERIFIED", entry.verificationLevel)
        assertTrue(entry.intendedFilename.contains("[vrka-"))
        assertEquals(entry.intendedFilename, TestDocumentsProvider.findByName(entry.intendedFilename)?.name)
        assertTrue(publisher.safFinalIdentityIsUnique(entry, published.uri))
        assertTrue(TestDocumentsProvider.findByName(entry.intendedFilename)!!.file.readBytes().contentEquals(bytes))
    }

    @Test
    fun safRenameReturningNullIsAcceptedOnlyAfterProviderRequery() {
        TestDocumentsProvider.renameMode = TestSafRenameMode.NULL_RETURN
        val jobId = "saf-null-${System.nanoTime()}"
        val staging = stage(jobId, "null.mp4", ByteArray(1024))
        val publisher = newPublisher()

        val published = publisher.publishAll(
            files = listOf(staging to "null.mp4"),
            destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
            jobId = jobId,
        ).single()

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertEquals(entry.intendedFilename, TestDocumentsProvider.findByName(entry.intendedFilename)?.name)
        assertEquals(entry.providerUri, published.uri.toString())
    }

    @Test
    fun unsupportedRenameDoesNotProducePublishedOutput() {
        TestDocumentsProvider.renameMode = TestSafRenameMode.UNSUPPORTED
        val jobId = "saf-unsupported-${System.nanoTime()}"
        val staging = stage(jobId, "unsupported.mp4", ByteArray(1024))
        val publisher = newPublisher()

        runCatching {
            publisher.publishAll(
                files = listOf(staging to "unsupported.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.onSuccess { error("Publication must fail when the provider cannot rename") }

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertNotEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertFalse(entry.intendedFilename.contains("[vrka-") && TestDocumentsProvider.findByName(entry.intendedFilename) != null)
        assertTrue("The unfinished pending document is cleaned up exactly", TestDocumentsProvider.docsSnapshot().isEmpty())
    }

    @Test
    fun providerModifiedMarkerIsNeverTreatedAsPublished() {
        TestDocumentsProvider.renameMode = TestSafRenameMode.MODIFY_NAME
        val jobId = "saf-modified-${System.nanoTime()}"
        val staging = stage(jobId, "modified.mp4", ByteArray(1024))
        val publisher = newPublisher()

        runCatching {
            publisher.publishAll(
                files = listOf(staging to "modified.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.onSuccess { error("Publication must fail when the provider rewrites the transaction marker") }

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertNotEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertFalse(TestDocumentsProvider.docsSnapshot().any { it.name == entry.intendedFilename })
    }

    @Test
    fun duplicateFinalNameCandidatesArePreservedForRecovery() {
        TestDocumentsProvider.renameMode = TestSafRenameMode.DUPLICATE
        val jobId = "saf-duplicate-${System.nanoTime()}"
        val bytes = ByteArray(1024)
        val staging = stage(jobId, "duplicate.mp4", bytes)
        val publisher = newPublisher()

        runCatching {
            publisher.publishAll(
                files = listOf(staging to "duplicate.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.onSuccess { error("Publication must fail when two documents carry the transaction name") }

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertEquals(PublicationTransactionStatus.CLEANUP_PENDING, entry.status)
        assertEquals(2, TestDocumentsProvider.docsSnapshot().count { it.name == entry.intendedFilename })

        val manager = managerFor(job(jobId))
        manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.FAILED, durableJob(jobId).state)
        assertEquals(
            "Ambiguous duplicate candidates must never be deleted",
            2,
            TestDocumentsProvider.docsSnapshot().count { it.name == entry.intendedFilename },
        )
    }

    @Test
    fun destinationWithoutChildEnumerationIsRejectedBeforeCreation() {
        TestDocumentsProvider.failChildQuery = true
        val jobId = "saf-no-enumeration-${System.nanoTime()}"
        val staging = stage(jobId, "blocked.mp4", ByteArray(1024))
        val publisher = newPublisher()

        val failure = runCatching {
            publisher.publishAll(
                files = listOf(staging to "blocked.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException || failure is SecurityException)
        assertTrue(TestDocumentsProvider.docsSnapshot().isEmpty())
    }

    @Test
    fun providerReadbackFailurePreventsFinalization() {
        TestDocumentsProvider.failReadback = true
        val jobId = "saf-readback-${System.nanoTime()}"
        val staging = stage(jobId, "readback.mp4", ByteArray(2048))
        val publisher = newPublisher()

        runCatching {
            publisher.publishAll(
                files = listOf(staging to "readback.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.onSuccess { error("Publication must fail when stored bytes cannot be read back") }

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertNotEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertFalse(TestDocumentsProvider.docsSnapshot().any { it.name == entry.intendedFilename })
    }

    @Test
    fun failedDeletionLeavesDurableCleanupThatASubsequentStartupCompletes() {
        TestDocumentsProvider.failReadback = true
        TestDocumentsProvider.failDelete = true
        val jobId = "saf-cleanup-${System.nanoTime()}"
        val staging = stage(jobId, "cleanup.mp4", ByteArray(1024))
        val publisher = newPublisher()

        runCatching {
            publisher.publishAll(
                files = listOf(staging to "cleanup.mp4"),
                destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
                jobId = jobId,
            )
        }.onSuccess { error("Publication must fail when readback fails") }

        val entry = publisher.activeJournal.entriesForJob(jobId).single()
        assertEquals(PublicationTransactionStatus.CLEANUP_PENDING, entry.status)
        assertEquals(1, TestDocumentsProvider.docsSnapshot().size)

        TestDocumentsProvider.failReadback = false
        TestDocumentsProvider.failDelete = false
        // Model a genuinely incomplete provider object; a complete copy would be recovered
        // as published instead of being deleted.
        TestDocumentsProvider.docsSnapshot().single().file.writeBytes(ByteArray(512))
        val manager = managerFor(job(jobId))
        manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.FAILED, durableJob(jobId).state)
        assertTrue(TestDocumentsProvider.docsSnapshot().isEmpty())
        assertTrue("Cleanup retry must reconcile the transaction", publisher.activeJournal.unreconciledEntries().isEmpty())
    }

    @Test
    fun renameReplacementUriIsRediscoveredAfterInterruptionBeforeUriPersistence() {
        TestDocumentsProvider.renameMode = TestSafRenameMode.NEW_ID
        val jobId = "saf-new-id-${System.nanoTime()}"
        val bytes = ByteArray(4096) { (it % 127).toByte() }
        val staging = stage(jobId, "renamed.mp4", bytes)
        val stagingRootForJob = staging.parentFile!!
        val transactionId = UUID.randomUUID().toString()
        val finalName = "renamed [vrka-${transactionId.replace("-", "").take(10)}].mp4"
        val pendingName = ".vrka_pending_${transactionId}_$finalName"

        val ops = DefaultPublicationStorageOps(context)
        val oldUri = ops.createSafDocument(
            TestDocumentsProvider.TREE_URI,
            "video/mp4",
            pendingName,
        ) ?: error("Test provider createDocument failed")
        ops.openOutputStream(oldUri, "w")!!.use { it.write(bytes) }

        val journal = publicationStore
        journal.recordIntent(
            PublicationEntry(
                transactionId = transactionId,
                jobId = jobId,
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = finalName,
                pendingFilename = pendingName,
                stagingPath = staging.absolutePath,
                expectedSizeBytes = bytes.size.toLong(),
                providerType = PublicationProviderType.SAF,
                destinationIdentity = TestDocumentsProvider.TREE_URI.toString(),
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                attemptNumber = 1,
                attemptId = JobJsonCodec.attemptIdFor(jobId, 1),
                batchId = "batch-$transactionId",
            ),
        )
        journal.updateProviderCreated(transactionId, oldUri.toString())
        journal.updateDataCopied(transactionId, bytes.size.toLong())

        // Simulate process death immediately after renameDocument() replaced the URI.
        val publisher = newPublisher()
        val replacementUri = publisher.finalizeSaf(oldUri, finalName)
        assertNotEquals("The provider must return a replacement URI", oldUri, replacementUri)
        assertTrue(TestDocumentsProvider.findByName(finalName) != null)

        val manager = managerFor(job(jobId))
        manager.reconcilePublicationJournalOnStartup()

        val durable = durableJob(jobId)
        assertEquals(JobState.DONE, durable.state)
        assertEquals(listOf(replacementUri.toString()), durable.outputUris)
        assertTrue(publisher.activeJournal.unreconciledEntries().isEmpty())
        val loadState = publisher.activeJournal.loadWithStatus()
        assertEquals(
            "Journal must remain decodable: ${loadState.detail}",
            PublicationJournalLoadState.OK,
            loadState.state,
        )
        assertTrue(TestDocumentsProvider.findByName(finalName)!!.file.readBytes().contentEquals(bytes))
        assertFalse(stagingRootForJob.exists())
    }

    @Test
    fun revokedPermissionPreservesTheTransactionUntilAccessReturns() {
        val jobId = "saf-permission-${System.nanoTime()}"
        val bytes = ByteArray(3072)
        val staging = stage(jobId, "permission.mp4", bytes)
        val publisher = newPublisher()
        publisher.publishAll(
            files = listOf(staging to "permission.mp4"),
            destinationTreeUri = TestDocumentsProvider.TREE_URI.toString(),
            jobId = jobId,
        ).single()
        val entry = publisher.activeJournal.entriesForJob(jobId).single()

        TestDocumentsProvider.permissionRevoked = true
        val manager = managerFor(job(jobId))
        manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.FAILED, durableJob(jobId).state)
        assertFalse(publisher.activeJournal.unreconciledEntries().isEmpty())
        assertTrue(TestDocumentsProvider.findByName(entry.intendedFilename) != null)

        TestDocumentsProvider.permissionRevoked = false
        val secondManager = managerFor(job(jobId))
        secondManager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, durableJob(jobId).state)
        assertTrue(publisher.activeJournal.unreconciledEntries().isEmpty())
    }
}
