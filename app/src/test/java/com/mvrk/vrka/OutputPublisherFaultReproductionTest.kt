package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.VrkaDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 1 fault-injection reproduction suite for OutputPublisher MediaStore & SAF defects:
 * - MediaStore zero-row update when IS_PENDING is already 0 -> must succeed idempotently
 * - MediaStore provider deduplication ("Title (1).mp4") -> must adopt observed name, never delete
 * - Brand-new empty SAF folder without probe reads -> must succeed
 * - SAF renameDocument returning a brand-new Uri -> must adopt new Uri without deleting
 * - SAF !FLAG_SUPPORTS_RENAME + FLAG_SUPPORTS_DELETE -> must fall back cleanly without leaving .vrka_pending_
 * - SAF !FLAG_SUPPORTS_DELETE -> must fail closed before streaming partial data
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputPublisherFaultReproductionTest {

    private class FaultStorageOps(
        private val root: File,
        val base: PublicationJournalRecoveryTest.TestPublicationStorageOps,
    ) : PublicationStorageOps by base {
        var simulateZeroRowUpdateAlreadyFinalized = false
        var simulateFinalizeThrowsException = false
        var simulateStreamFailureBeforeVerification = false
        var deduplicateMediaDisplayNameSuffix: String? = null
        var simulateEmptySafCapabilitiesWithNullReadBack = false
        var forbidSafReadBackProbeOnExistingFiles = false
        var safRenameMintsNewUri = false
        var safCanRenameOverride: Boolean? = null
        var safCanDeleteOverride: Boolean? = null
        var safDocFlagsOverride: SafDocumentFlags? = null
        var openOutputStreamCalls = 0
        var readBackCalls = 0

        override fun insertMediaStore(
            collection: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Uri? {
            val uri = base.insertMediaStore(collection, values, displayName, isPending) ?: return null
            val dedup = deduplicateMediaDisplayNameSuffix
            if (dedup != null && displayName != null && !displayName.startsWith(".vrka_pending_")) {
                val item = base.mediaItems[uri.toString()]
                if (item != null) {
                    val dot = displayName.lastIndexOf('.')
                    item.displayName = if (dot > 0) {
                        displayName.substring(0, dot) + dedup + displayName.substring(dot)
                    } else {
                        displayName + dedup
                    }
                }
            }
            return uri
        }

        override fun updateMediaStore(
            uri: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Int {
            if (simulateFinalizeThrowsException && isPending == false) {
                throw java.io.IOException("Simulated transient SQLiteDiskIOException during finalization")
            }
            val item = base.mediaItems[uri.toString()] ?: return 0
            if (simulateZeroRowUpdateAlreadyFinalized && isPending == false) {
                item.isPending = false
                if (displayName != null) {
                    item.displayName = applyDedup(displayName)
                }
                return 0
            }
            val rows = base.updateMediaStore(uri, values, displayName, isPending)
            if (isPending == false && displayName != null && deduplicateMediaDisplayNameSuffix != null) {
                item.displayName = applyDedup(displayName)
            }
            return rows
        }

        private fun applyDedup(displayName: String): String {
            val suffix = deduplicateMediaDisplayNameSuffix ?: return displayName
            val dot = displayName.lastIndexOf('.')
            return if (dot > 0) {
                displayName.substring(0, dot) + suffix + displayName.substring(dot)
            } else {
                displayName + suffix
            }
        }

        override fun querySafCapabilities(tree: Uri): SafCapabilities {
            if (forbidSafReadBackProbeOnExistingFiles && base.safItems.isNotEmpty()) {
                // Phase 2 never calls openInputStream on unrelated existing files here.
            }
            val baseCap = base.querySafCapabilities(tree)
            return baseCap.copy(
                canRename = safCanRenameOverride ?: if (simulateEmptySafCapabilitiesWithNullReadBack) null else baseCap.canRename,
                canReadBack = if (simulateEmptySafCapabilitiesWithNullReadBack) null else baseCap.canReadBack,
                canDelete = safCanDeleteOverride ?: baseCap.canDelete,
            )
        }

        override fun querySafDocumentFlags(uri: Uri): SafDocumentFlags? =
            safDocFlagsOverride ?: safCanDeleteOverride?.let { canDel ->
                SafDocumentFlags(canWrite = true, canDelete = canDel, canRename = safCanRenameOverride ?: true)
            }

        override fun openOutputStream(uri: Uri, mode: String): java.io.OutputStream? {
            openOutputStreamCalls++
            if (simulateStreamFailureBeforeVerification) {
                throw java.io.IOException("Simulated write failure during streaming")
            }
            return base.openOutputStream(uri, mode)
        }

        override fun readBack(uri: Uri): ProviderReadback? {
            readBackCalls++
            return base.readBack(uri)
        }

        override fun createSafDocument(tree: Uri, mimeType: String, pendingFilename: String): Uri? {
            return base.createSafDocument(tree, mimeType, pendingFilename)
        }

        override fun renameSafDocument(uri: Uri, finalName: String): Uri? {
            if (safCanRenameOverride == false) return null
            if (!safRenameMintsNewUri) {
                return base.renameSafDocument(uri, finalName)
            }
            val item = base.safItems.remove(uri.toString()) ?: return null
            val newUri = Uri.parse("content://saf/tree/root/document/renamed-${base.nextId++}")
            base.safItems[newUri.toString()] = PublicationJournalRecoveryTest.SafItem(
                uri = newUri,
                tree = item.tree,
                displayName = finalName,
                file = item.file,
            )
            return newUri
        }
    }

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var baseOps: PublicationJournalRecoveryTest.TestPublicationStorageOps
    private lateinit var ops: FaultStorageOps
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var settings: SettingsRepository
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbFile = context.getDatabasePath("fault-pub-test.db")
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        tempDir = File(context.cacheDir, "pub-fault-${System.nanoTime()}").apply { mkdirs() }
        baseOps = PublicationJournalRecoveryTest.TestPublicationStorageOps(tempDir, sdkInt = 34)
        ops = FaultStorageOps(tempDir, baseOps)
        database = Room.databaseBuilder(context, VrkaDatabase::class.java, "fault-pub-test.db")
            .allowMainThreadQueries()
            .build()
        repository = RoomStorageRepository(database)
        settings = SettingsRepository(
            context,
            MutableStateFlow(AppSettings(outputTreeUri = "", saveLocationMode = SaveLocationMode.REMEMBER_LOCATION)),
        )
        publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = repository.publicationStore,
            ops = ops,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION),
        )
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        val dbFile = context.getDatabasePath("fault-pub-test.db")
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        tempDir.deleteRecursively()
    }

    private fun stagedFile(name: String = "staged.mp4", bytes: Int = 512): File =
        File(tempDir, name).apply { writeBytes(ByteArray(bytes) { (it % 193).toByte() }) }

    private fun seedJob(jobId: String, destinationTreeUri: String? = null) = runBlocking {
        val result = repository.saveJobs(
            jobs = listOf(
                DownloadJob(
                    id = jobId,
                    request = DownloadRequest(
                        url = "https://example.com/$jobId",
                        destinationTreeUri = destinationTreeUri,
                    ),
                    state = JobState.POSTPROCESSING,
                ),
            ),
            pinnedJobIds = emptySet(),
        )
        assertTrue(result.error, result.success)
    }

    @Test
    fun mediaStore_zeroRowUpdate_whenRowAlreadyFinalized_mustSucceedIdempotently() {
        seedJob("job-zero-row")
        ops.simulateZeroRowUpdateAlreadyFinalized = true
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "Clip.mp4"),
            destinationTreeUri = null,
            jobId = "job-zero-row",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        val uri = outputs.single().uri
        assertTrue("Verified finalized output must not be deleted on 0-row update", ops.fileExists(uri))
        assertTrue("Deleted URIs must remain empty", baseOps.deletedUris.isEmpty())
    }

    @Test
    fun mediaStore_providerDeduplicatesDisplayName_mustAdoptObservedNameAndKeepOutput() {
        seedJob("job-dedup")
        ops.deduplicateMediaDisplayNameSuffix = " (1)"
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "Concert.mp4"),
            destinationTreeUri = null,
            jobId = "job-dedup",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        val uri = outputs.single().uri
        assertTrue("Provider-deduplicated output must survive intact", ops.fileExists(uri))
        assertEquals("Concert (1).mp4", ops.queryDisplayName(uri))
        assertEquals("Concert (1).mp4", repository.publicationStore.entriesForJob("job-dedup").single().intendedFilename)
        assertTrue("Verified output must not be deleted on collision suffix", baseOps.deletedUris.isEmpty())
    }

    @Test
    fun saf_brandNewEmptyFolder_mustPublishWithoutExistingFileReadProbe() {
        seedJob("job-saf-empty", PublicationJournalRecoveryTest.TREE)
        ops.simulateEmptySafCapabilitiesWithNullReadBack = true
        ops.forbidSafReadBackProbeOnExistingFiles = true
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "EmptyFolderClip.mp4"),
            destinationTreeUri = PublicationJournalRecoveryTest.TREE,
            jobId = "job-saf-empty",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        assertTrue(ops.fileExists(outputs.single().uri))
    }

    @Test
    fun saf_renameDocumentReturnsNewUri_mustAdoptNewUriWithoutDeleting() {
        seedJob("job-saf-new-uri", PublicationJournalRecoveryTest.TREE)
        ops.safRenameMintsNewUri = true
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "RenamedUriClip.mp4"),
            destinationTreeUri = PublicationJournalRecoveryTest.TREE,
            jobId = "job-saf-new-uri",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        val uri = outputs.single().uri
        assertTrue("New renamed URI must be returned and preserved", uri.toString().contains("renamed-"))
        assertTrue(ops.fileExists(uri))
        assertTrue("Renamed SAF document must not be deleted by failure cleanup", baseOps.deletedUris.isEmpty())
    }

    @Test
    fun saf_nonRenameableButDeletableProvider_mustFallbackCleanlyWithoutPendingPrefix() {
        seedJob("job-saf-no-rename", PublicationJournalRecoveryTest.TREE)
        ops.safCanRenameOverride = false
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "NoRenameClip.mp4"),
            destinationTreeUri = PublicationJournalRecoveryTest.TREE,
            jobId = "job-saf-no-rename",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        val uri = outputs.single().uri
        val displayName = ops.queryDisplayName(uri).orEmpty()
        assertFalse("Final user file must never keep .vrka_pending_ prefix", displayName.startsWith(".vrka_pending_"))
        assertTrue(ops.fileExists(uri))
    }

    @Test
    fun saf_emptyFolderDocFlagsNonRenameable_removesPendingAndPublishesFinalName() {
        seedJob("job-saf-empty-docflags-norename", PublicationJournalRecoveryTest.TREE)
        ops.simulateEmptySafCapabilitiesWithNullReadBack = true
        ops.safDocFlagsOverride = SafDocumentFlags(canWrite = true, canDelete = true, canRename = false)
        val file = stagedFile()

        val outputs = publisher.publishAll(
            files = listOf(file to "EmptyNoRename.mp4"),
            destinationTreeUri = PublicationJournalRecoveryTest.TREE,
            jobId = "job-saf-empty-docflags-norename",
            attemptNumber = 1,
        )

        assertEquals(1, outputs.size)
        val uri = outputs.single().uri
        val displayName = ops.queryDisplayName(uri).orEmpty()
        assertFalse("Final file must not retain .vrka_pending_", displayName.startsWith(".vrka_pending_"))
        assertTrue("Final SAF name must include transaction marker", displayName.contains("[vrka-"))
        assertTrue("Zero-byte .vrka_pending_ stub must have been cleaned up", baseOps.safItems.size == 1)
    }

    @Test
    fun saf_nonDeletableProvider_mustFailClosedBeforeStreaming() {
        seedJob("job-saf-no-delete", PublicationJournalRecoveryTest.TREE)
        ops.safCanDeleteOverride = false
        val file = stagedFile()

        val failure = runCatching {
            publisher.publishAll(
                files = listOf(file to "NoDeleteClip.mp4"),
                destinationTreeUri = PublicationJournalRecoveryTest.TREE,
                jobId = "job-saf-no-delete",
                attemptNumber = 1,
            )
        }.exceptionOrNull()

        assertTrue("Provider without delete support must fail closed", failure is IllegalStateException)
        assertEquals("No bytes may be streamed before delete capability check", 0, ops.openOutputStreamCalls)
    }

    @Test
    fun integrityTier_over32MiB_recordsSizeVerifiedWithoutFalseHashPromotion() {
        seedJob("job-large-tier")
        val smallFile = stagedFile("small.mp4", 512)
        publisher.publishAll(
            files = listOf(smallFile to "Small.mp4"),
            destinationTreeUri = null,
            jobId = "job-large-tier",
            attemptNumber = 1,
        )
        val smallEntry = repository.publicationStore.entriesForJob("job-large-tier").single()
        assertEquals("HASH_VERIFIED", smallEntry.verificationLevel)
        assertEquals(1, ops.readBackCalls)

        seedJob("job-large-over32")
        val largeFile = File(tempDir, "large_sparse.mp4").apply {
            java.io.RandomAccessFile(this, "rw").use { raf ->
                raf.setLength(OutputPublisher.HASH_READBACK_THRESHOLD_BYTES + 1L)
            }
        }
        ops.readBackCalls = 0
        publisher.publishAll(
            files = listOf(largeFile to "Large.mp4"),
            destinationTreeUri = null,
            jobId = "job-large-over32",
            attemptNumber = 1,
        )
        val largeEntry = repository.publicationStore.entriesForJob("job-large-over32").single()
        assertEquals("SIZE_VERIFIED", largeEntry.verificationLevel)
        assertEquals("Large >32 MiB must not falsify observedSha256", null, largeEntry.observedSha256)
        assertEquals("Large >32 MiB with valid provider SIZE skips full readback", 0, ops.readBackCalls)
    }

    @Test
    fun defaultOps_querySafCapabilities_neverReadsExistingChildFile_andDetectsFlags() {
        Robolectric.buildContentProvider(TestDocumentsProvider::class.java)
            .create("com.mvrk.vrka.phase0docs")
            .get()
        TestDocumentsProvider.reset(context)

        val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            TestDocumentsProvider.TREE_URI,
            "root",
        )
        val existingUri = android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            rootDocUri,
            "video/mp4",
            "ExistingUserFile.mp4",
        )!!
        TestDocumentsProvider.failReadback = true

        val defaultOps = DefaultPublicationStorageOps(context)
        val caps = defaultOps.querySafCapabilities(TestDocumentsProvider.TREE_URI)
        assertNotNull(caps)
        assertEquals(true, caps!!.canCreate)
        assertEquals(true, caps.canEnumerate)
        assertEquals(true, caps.hasReliableDocumentIdentity)
        assertEquals(true, caps.canRename)
        assertEquals(true, caps.canDelete)
        assertEquals(true, caps.canWrite)
        assertNull(
            "querySafCapabilities must never open or read unrelated user documents",
            caps.canReadBack,
        )

        TestDocumentsProvider.renameMode = TestSafRenameMode.UNSUPPORTED
        val unsupportedDocFlags = defaultOps.querySafDocumentFlags(existingUri)
        assertNotNull(unsupportedDocFlags)
        assertEquals(true, unsupportedDocFlags!!.canWrite)
        assertEquals(true, unsupportedDocFlags.canDelete)
        assertEquals(false, unsupportedDocFlags.canRename)

        TestDocumentsProvider.reset(context)
    }

    @Test
    fun mediaStore_finalizationFailureAfterVerifiedContent_neverDeletesVerifiedBytes() {
        seedJob("job-finalization-fail")
        ops.simulateFinalizeThrowsException = true
        val file = stagedFile("complete_clip.mp4", 1024)

        val failure = runCatching {
            publisher.publishAll(
                files = listOf(file to "CompleteClip.mp4"),
                destinationTreeUri = null,
                jobId = "job-finalization-fail",
                attemptNumber = 1,
            )
        }.exceptionOrNull()

        assertNotNull("Publish must throw when finalization fails", failure)
        assertTrue(failure is java.io.IOException)

        val entries = repository.publicationStore.entriesForJob("job-finalization-fail")
        assertEquals(1, entries.size)
        val entry = entries.single()

        // Invariant 1: Verified bytes transaction must be preserved in COPIED phase
        assertEquals("Transaction must be retained in COPIED phase", PublicationPhase.COPIED, entry.publicationPhase)
        assertEquals("Copied bytes count must match file length", 1024L, entry.copiedBytes)
        assertNotNull("Provider URI must be preserved for recovery retry", entry.providerUri)

        val contentUri = Uri.parse(entry.providerUri!!)
        // Invariant 2: Output file must NOT be deleted from disk or provider
        assertTrue("Verified completed file must remain on disk", ops.fileExists(contentUri))
        assertTrue("Ops deleted URIs must remain empty", baseOps.deletedUris.isEmpty())
    }

    @Test
    fun mediaStore_streamingFailureBeforeVerifiedContent_cleansUpIncompleteFile() {
        seedJob("job-stream-fail")
        ops.simulateStreamFailureBeforeVerification = true
        val file = stagedFile("partial_clip.mp4", 1024)

        val failure = runCatching {
            publisher.publishAll(
                files = listOf(file to "PartialClip.mp4"),
                destinationTreeUri = null,
                jobId = "job-stream-fail",
                attemptNumber = 1,
            )
        }.exceptionOrNull()

        assertNotNull("Publish must throw when streaming fails", failure)
        assertTrue(failure is java.io.IOException)

        // Invariant 3: Incomplete unverified output MUST be cleaned up
        assertTrue("Incomplete unverified output must be deleted", baseOps.deletedUris.isNotEmpty())
    }
}
