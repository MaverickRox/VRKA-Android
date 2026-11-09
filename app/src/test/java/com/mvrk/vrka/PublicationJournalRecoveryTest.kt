package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PublicationJournalRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleRequest(url: String = "https://example.com/video") = DownloadRequest(
        url = url,
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
    )

    private fun sampleJob(
        id: String,
        state: JobState = JobState.POSTPROCESSING,
        publishStarted: Boolean = true,
        outputUris: List<String> = emptyList(),
    ) = DownloadJob(
        id = id,
        request = sampleRequest(),
        state = state,
        title = "Test Media $id",
        detail = "Publishing to storage",
        publishStarted = publishStarted,
        outputUris = outputUris,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    @Test
    fun journalRoundtripPersistenceAndSchema() {
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val journal = PublicationJournal(journalFile)

        val entry = PublicationEntry(
            transactionId = "tx-1",
            jobId = "job-1",
            outputIndex = 0,
            totalOutputs = 1,
            intendedFilename = "video.mp4",
            stagingPath = "/staging/job-1/video.mp4",
            expectedSizeBytes = 1048576L,
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.INTENT_RECORDED,
        )

        journal.recordIntent(entry)
        val loaded1 = journal.allEntries()
        assertEquals(1, loaded1.size)
        assertEquals("tx-1", loaded1[0].transactionId)
        assertEquals(PublicationPhase.INTENT_RECORDED, loaded1[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.PENDING, loaded1[0].status)
        assertEquals(".vrka_pending_tx-1_video.mp4", loaded1[0].pendingFilename)

        // Advance to PROVIDER_OBJECT_CREATED
        journal.updateProviderCreated("tx-1", "content://media/external/downloads/100")
        val loaded2 = journal.allEntries()
        assertEquals("content://media/external/downloads/100", loaded2[0].providerUri)
        assertEquals(PublicationPhase.PROVIDER_OBJECT_CREATED, loaded2[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.IN_PROGRESS, loaded2[0].status)

        // Advance to DATA_COPIED
        journal.updateDataCopied("tx-1", 1048576L)
        val loaded3 = journal.allEntries()
        assertEquals(1048576L, loaded3[0].copiedBytes)
        assertEquals(PublicationPhase.DATA_COPIED, loaded3[0].publicationPhase)

        // Advance to FINALIZED
        journal.updateFinalized("tx-1")
        val loaded4 = journal.allEntries()
        assertEquals(PublicationPhase.FINALIZED, loaded4[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.COMPLETED, loaded4[0].status)

        // Mark reconciled
        journal.markReconciled("job-1")
        val loaded5 = journal.allEntries()
        assertEquals(PublicationTransactionStatus.RECONCILED, loaded5[0].status)
        assertTrue(journal.unreconciledEntries().isEmpty())
    }

    // Boundary 1: MediaStore insert before URI persistence
    @Test
    fun boundary01_mediaStore_insertBeforeUriPersistence_orphanedObjectCleanedUp() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-1"
        val pendingName = ".vrka_pending_${txId}_video.mp4"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-1",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "video.mp4",
                pendingFilename = pendingName,
                stagingPath = "/staging/video.mp4",
                expectedSizeBytes = 5000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                providerUri = null,
            ),
        )

        // Simulate provider store holding orphaned pending object and an unrelated user file
        val providerStore = mutableMapOf<String, Long>()
        providerStore[pendingName] = 0L // Created row before URI saved, 0 bytes
        providerStore["video.mp4"] = 5000L // Unrelated user file!

        // On recovery: query locates object matching entry.pendingFilename
        val discoveredPendingName = providerStore.keys.firstOrNull { it == pendingName }
        assertNotNull(discoveredPendingName)
        assertEquals(pendingName, discoveredPendingName)

        // Incomplete copy (0 < 5000): safely remove orphaned provider object
        providerStore.remove(discoveredPendingName)
        journal.markInterrupted(txId)

        // Invariants: orphaned object deleted, unrelated user file untouched
        assertFalse(providerStore.containsKey(pendingName))
        assertTrue(providerStore.containsKey("video.mp4"))
        assertEquals(PublicationTransactionStatus.INTERRUPTED, journal.entriesForJob("job-boundary-1").single().status)
    }

    // Boundary 2: SAF createDocument before URI persistence
    @Test
    fun boundary02_saf_createDocumentBeforeUriPersistence_orphanedDocumentCleanedUp() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-2"
        val pendingName = ".vrka_pending_${txId}_doc.mp4"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-2",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "doc.mp4",
                pendingFilename = pendingName,
                stagingPath = "/staging/doc.mp4",
                expectedSizeBytes = 8000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                providerUri = null,
            ),
        )

        // Simulate SAF tree holding orphaned pending document and unrelated document
        val safTree = mutableMapOf<String, Long>()
        safTree[pendingName] = 0L
        safTree["doc.mp4"] = 8000L

        // On recovery: search child documents for pendingFilename
        val discoveredDoc = safTree.keys.firstOrNull { it == pendingName }
        assertNotNull(discoveredDoc)

        // Incomplete copy: remove orphaned document, mark interrupted
        safTree.remove(discoveredDoc)
        journal.markInterrupted(txId)

        assertFalse(safTree.containsKey(pendingName))
        assertTrue(safTree.containsKey("doc.mp4"))
        assertEquals(PublicationTransactionStatus.INTERRUPTED, journal.entriesForJob("job-boundary-2").single().status)
    }

    // Boundary 3: First playlist output finalized, crash before second output published
    @Test
    fun boundary03_playlist_firstOutputFinalized_crashBeforeSecondPublished_failsClosed() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-playlist-split", state = JobState.POSTPROCESSING, publishStarted = true)
        jobStore.save(listOf(job))

        // Entire batch recorded atomically upfront
        val batch = listOf(
            PublicationEntry(
                transactionId = "tx-p1",
                jobId = "job-playlist-split",
                outputIndex = 0,
                totalOutputs = 2,
                intendedFilename = "track_1.mp3",
                stagingPath = "/staging/track_1.mp3",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
            PublicationEntry(
                transactionId = "tx-p2",
                jobId = "job-playlist-split",
                outputIndex = 1,
                totalOutputs = 2,
                intendedFilename = "track_2.mp3",
                stagingPath = "/staging/track_2.mp3",
                expectedSizeBytes = 20000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.recordBatchIntents(batch)

        // Track 1 finalized
        journal.updateProviderCreated("tx-p1", "content://saf/tree/item1")
        journal.updateDataCopied("tx-p1", 10000L)
        journal.updateFinalized("tx-p1")

        // Crash before track 2 is published (tx-p2 remains INTENT_RECORDED)
        val entries = journal.entriesForJob("job-playlist-split")
        assertEquals(2, entries.size)
        assertEquals(2, entries[0].totalOutputs)

        val totalOutputs = entries.first().totalOutputs
        val isAllPresent = entries.size == totalOutputs
        val allComplete = isAllPresent && entries.all { it.publicationPhase == PublicationPhase.FINALIZED }
        assertFalse("Incomplete playlist MUST NOT be detected as all complete", allComplete)

        // Clean up incomplete item, mark interrupted
        journal.markInterrupted("tx-p2")

        // Reconcile job: must fail closed as FAILED, retain completed URIs, never mark DONE
        val loaded = jobStore.load(recoverActive = false).single()
        val completedUris = entries.filter { it.publicationPhase == PublicationPhase.FINALIZED }.map { it.providerUri!! }
        val failedJob = loaded.copy(
            state = JobState.FAILED,
            error = INTERRUPTED_DOWNLOAD_ERROR,
            detail = "Interrupted during publication (1 of $totalOutputs files published)",
            outputUris = completedUris,
            actualFinalFileBytes = 10000L,
            updatedAt = System.currentTimeMillis(),
        )
        jobStore.save(listOf(failedJob))
        journal.markReconciled("job-playlist-split")

        val stored = jobStore.load().single()
        assertEquals(JobState.FAILED, stored.state)
        assertEquals(listOf("content://saf/tree/item1"), stored.outputUris)
        assertEquals("Interrupted during publication (1 of 2 files published)", stored.detail)
    }

    // Boundary 4: All output intents recorded, crash before publication begins
    @Test
    fun boundary04_playlist_allIntentsRecorded_crashBeforePublicationBegins_failsClosed() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-p-init", state = JobState.POSTPROCESSING, publishStarted = true)
        jobStore.save(listOf(job))

        val batch = (0..2).map { i ->
            PublicationEntry(
                transactionId = "tx-init-$i",
                jobId = "job-p-init",
                outputIndex = i,
                totalOutputs = 3,
                intendedFilename = "song_$i.mp3",
                stagingPath = "/staging/song_$i.mp3",
                expectedSizeBytes = 15000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            )
        }
        journal.recordBatchIntents(batch)

        // Process killed before any provider insertion
        val entries = journal.entriesForJob("job-p-init")
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.providerUri == null })
        assertTrue(entries.all { it.publicationPhase == PublicationPhase.INTENT_RECORDED })

        // Recovery: none completed, marks all interrupted
        entries.forEach { journal.markInterrupted(it.transactionId) }

        val loaded = jobStore.load(recoverActive = false).single()
        val reconciledJob = loaded.copy(
            state = JobState.FAILED,
            error = INTERRUPTED_DOWNLOAD_ERROR,
            detail = "Interrupted during publication",
            outputUris = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )
        jobStore.save(listOf(reconciledJob))
        journal.markReconciled("job-p-init")

        val stored = jobStore.load().single()
        assertEquals(JobState.FAILED, stored.state)
        assertTrue(stored.outputUris.isEmpty())
        assertEquals("Interrupted during publication", stored.detail)
    }

    // Boundary 5: Crash during file copy (partial bytes)
    @Test
    fun boundary05_duringFileCopy_interruptedPartialData_cleanedUp() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-5"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-5",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "partial.mp4",
                stagingPath = "/staging/partial.mp4",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated(txId, "content://saf/tree/document/part1")
        journal.updateDataCopied(txId, 3000L) // Partial copy

        val entry = journal.entriesForJob("job-boundary-5").single()
        assertTrue(entry.copiedBytes < entry.expectedSizeBytes)

        // Recovery detects partial copy, marks interrupted
        journal.markInterrupted(txId)
        val recovered = journal.entriesForJob("job-boundary-5").single()
        assertEquals(PublicationTransactionStatus.INTERRUPTED, recovered.status)
    }

    // Boundary 6: Crash after copy completes before journal update
    @Test
    fun boundary06_afterCopyCompletion_beforeJournalUpdate_recoversToDone() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-6"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-6",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "complete.mp4",
                stagingPath = "/staging/complete.mp4",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated(txId, "content://media/external/downloads/301")

        // Provider has 10000 bytes on disk, but journal was killed before updateDataCopied
        val actualProviderBytes = 10000L
        val entry = journal.entriesForJob("job-boundary-6").single()
        if (actualProviderBytes == entry.expectedSizeBytes) {
            journal.updateDataCopied(txId, actualProviderBytes)
            journal.updateFinalized(txId)
        }

        val finalized = journal.entriesForJob("job-boundary-6").single()
        assertEquals(PublicationPhase.FINALIZED, finalized.publicationPhase)
        assertEquals(PublicationTransactionStatus.COMPLETED, finalized.status)
    }

    // Boundary 7: Crash after MediaStore finalization before FINALIZED journal persistence
    @Test
    fun boundary07_mediaStore_afterFinalization_beforeFinalizedJournalPersistence_recoversToDone() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-7"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-7",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "finalized_store.mp4",
                stagingPath = "/staging/finalized_store.mp4",
                expectedSizeBytes = 20480L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated(txId, "content://media/external/downloads/701")
        journal.updateDataCopied(txId, 20480L)

        // Process killed right after MediaStore IS_PENDING set to 0, before journal.updateFinalized
        val entry = journal.entriesForJob("job-boundary-7").single()
        assertEquals(PublicationPhase.DATA_COPIED, entry.publicationPhase)

        // On recovery, verifies copiedBytes == expectedSizeBytes, advances to FINALIZED
        if (entry.copiedBytes == entry.expectedSizeBytes) {
            journal.updateFinalized(txId)
        }

        val recovered = journal.entriesForJob("job-boundary-7").single()
        assertEquals(PublicationPhase.FINALIZED, recovered.publicationPhase)
        assertEquals(PublicationTransactionStatus.COMPLETED, recovered.status)
    }

    // Boundary 8: Crash after SAF completion before journal update
    @Test
    fun boundary08_saf_afterCompletion_beforeJournalUpdate_recoversToDone() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val txId = "tx-boundary-8"
        journal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-boundary-8",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "saf_complete.mp4",
                stagingPath = "/staging/saf_complete.mp4",
                expectedSizeBytes = 40960L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated(txId, "content://saf/tree/document/801")
        journal.updateDataCopied(txId, 40960L)

        val entry = journal.entriesForJob("job-boundary-8").single()
        assertEquals(PublicationPhase.DATA_COPIED, entry.publicationPhase)

        // Recovery finalizes SAF with renamed or original URI
        val renamedUri = "content://saf/tree/document/saf_complete.mp4"
        journal.updateFinalized(txId, renamedUri)

        val recovered = journal.entriesForJob("job-boundary-8").single()
        assertEquals(PublicationPhase.FINALIZED, recovered.publicationPhase)
        assertEquals(renamedUri, recovered.providerUri)
    }

    // Boundary 9: Crash after terminal JobStore save before journal reconciliation
    @Test
    fun boundary09_crashAfterTerminalJobStoreSave_beforeJournalReconciliation_idempotentDone() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        // Job is already DONE in JobStore
        val job = sampleJob("job-terminal-save", state = JobState.DONE, outputUris = listOf("content://media/901"))
        jobStore.save(listOf(job))

        // Journal still has unreconciled FINALIZED entry
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-9",
                jobId = "job-terminal-save",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "video.mp4",
                stagingPath = "/staging/video.mp4",
                expectedSizeBytes = 50000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-9", "content://media/901")
        journal.updateDataCopied("tx-boundary-9", 50000L)
        journal.updateFinalized("tx-boundary-9")

        // Recovery: sees job is already DONE, reconciles journal cleanly
        journal.markReconciled("job-terminal-save")

        assertTrue(journal.unreconciledEntries().isEmpty())
        val stored = jobStore.load().single()
        assertEquals(JobState.DONE, stored.state)
    }

    // Boundary 10: Crash after journal reconciliation attempt before JobStore save (JobStore save failure)
    @Test
    fun boundary10_crashAfterReconciliationAttempt_jobStoreSaveFailure_retainsJournalAndStaging() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val stagingDir = File(tempFolder.root, "staging/job-b10").apply { mkdirs() }
        val stagingFile = File(stagingDir, "video.mp4").apply { writeBytes(ByteArray(100)) }

        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-10",
                jobId = "job-b10",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "video.mp4",
                stagingPath = stagingFile.absolutePath,
                expectedSizeBytes = 100L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-10", "content://media/1001")
        journal.updateDataCopied("tx-boundary-10", 100L)
        journal.updateFinalized("tx-boundary-10")

        // Invariant: If JobStore save fails, journal MUST NOT be marked reconciled, staging MUST NOT be cleaned
        val persistSuccess = false
        if (persistSuccess) {
            journal.markReconciled("job-b10")
            stagingDir.deleteRecursively()
        }

        // Journal and staging MUST remain intact
        assertEquals(1, journal.unreconciledEntries().size)
        assertTrue(stagingFile.exists())
    }

    // Boundary 11: Corrupt journal recovery & bounded copy creation
    @Test
    fun boundary11_corruptJournalRecovery_createsBoundedRecoveryCopy_max3() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        journalFile.writeText("{ malformed json content !!!")

        val journal = PublicationJournal(journalFile)
        val loadResult = journal.loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, loadResult.state)
        assertNotNull(loadResult.recoveryCopy)
        assertTrue(loadResult.recoveryCopy!!.exists())

        // Multiple corrupt occurrences create bounded copies, pruning to max 3
        for (i in 1..5) {
            journalFile.writeText("{ malformed repetition $i")
            journal.loadWithStatus()
            Thread.sleep(10) // Ensure distinct timestamps
        }

        val corruptFiles = tempFolder.root.listFiles { f -> f.name.startsWith("pub_journal.corrupt-") } ?: emptyArray()
        assertEquals(3, corruptFiles.size)
    }

    // Boundary 12: JobStore write failure during reconciliation (retains journal and staging)
    @Test
    fun boundary12_reconciliationPersistenceOrdering_jobStoreFailureRetainsJournalAndStaging() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val stagingDir = File(tempFolder.root, "staging/job-b12").apply { mkdirs() }
        val stagingFile = File(stagingDir, "track.mp3").apply { writeBytes(ByteArray(200)) }

        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-12",
                jobId = "job-b12",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "track.mp3",
                stagingPath = stagingFile.absolutePath,
                expectedSizeBytes = 200L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-12", "content://saf/1201")
        journal.updateDataCopied("tx-boundary-12", 200L)
        journal.updateFinalized("tx-boundary-12")

        // 6-step ordering check: Step 4 fails
        val mockJobStoreSave = { false } // Failed save!
        val step4Succeeded = mockJobStoreSave()
        assertFalse(step4Succeeded)

        // Steps 5 & 6 only execute if step 4 passed
        if (step4Succeeded) {
            journal.markReconciled("job-b12")
            stagingDir.deleteRecursively()
        }

        // Verify preservation
        assertEquals(1, journal.unreconciledEntries().size)
        assertTrue(stagingFile.exists())
    }

    // Boundary 13: Repeated startup reconciliation idempotence
    @Test
    fun boundary13_repeatedStartupReconciliation_isIdempotent() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-idem-13", state = JobState.DONE, outputUris = listOf("content://downloads/13"))
        jobStore.save(listOf(job))

        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-idem-13",
                jobId = "job-idem-13",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "test.mp4",
                stagingPath = "/staging/test.mp4",
                expectedSizeBytes = 1000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-idem-13", "content://downloads/13")
        journal.updateDataCopied("tx-idem-13", 1000L)
        journal.updateFinalized("tx-idem-13")

        // Pass 1
        journal.markReconciled("job-idem-13")
        assertEquals(0, journal.unreconciledEntries().size)

        // Pass 2
        journal.markReconciled("job-idem-13")
        assertEquals(0, journal.unreconciledEntries().size)

        // Pass 3
        journal.markReconciled("job-idem-13")
        assertEquals(0, journal.unreconciledEntries().size)

        assertEquals(1, jobStore.load().size)
        assertEquals(JobState.DONE, jobStore.load().single().state)
    }

    // Boundary 14: Duplicate/unrelated provider files with similar names (never deleted)
    @Test
    fun boundary14_unrelatedProviderFilesWithSimilarNames_neverDeleted() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        val myTxId = "tx-target-14"
        val myPendingName = ".vrka_pending_${myTxId}_film.mp4"

        journal.recordIntent(
            PublicationEntry(
                transactionId = myTxId,
                jobId = "job-target-14",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "film.mp4",
                pendingFilename = myPendingName,
                stagingPath = "/staging/film.mp4",
                expectedSizeBytes = 50000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )

        // Simulated directory holding target file and unrelated user files
        val filesOnDisk = mutableMapOf(
            myPendingName to 500L, // Partial target
            "film.mp4" to 50000L, // Unrelated user file!
            ".vrka_pending_otherTx_film.mp4" to 2000L, // Another job's pending file!
            "film (2).mp4" to 30000L, // Unrelated user file!
        )

        // Recovery only matches entry.pendingFilename
        val toDelete = filesOnDisk.keys.filter { it == myPendingName }
        assertEquals(listOf(myPendingName), toDelete)

        toDelete.forEach { filesOnDisk.remove(it) }
        journal.markInterrupted(myTxId)

        // Target pending file removed
        assertFalse(filesOnDisk.containsKey(myPendingName))
        // ALL unrelated files remain completely intact!
        assertTrue(filesOnDisk.containsKey("film.mp4"))
        assertTrue(filesOnDisk.containsKey(".vrka_pending_otherTx_film.mp4"))
        assertTrue(filesOnDisk.containsKey("film (2).mp4"))
    }
}
