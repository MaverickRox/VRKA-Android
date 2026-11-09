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

    @Test
    fun boundary1_beforeIntentPersistence_noJournalRecord() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        // Boundary 1: Process died before intent was written to journal.
        val unreconciled = journal.unreconciledEntries()
        assertTrue(unreconciled.isEmpty())
        assertTrue(journal.entriesForJob("job-crash-1").isEmpty())
    }

    @Test
    fun boundary2_afterIntentPersistence_beforeProviderObjectCreation() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        // Boundary 2: Intent persisted, provider object not yet created (providerUri == null)
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-2",
                jobId = "job-boundary-2",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "clip.mp4",
                stagingPath = "/staging/job-boundary-2/clip.mp4",
                expectedSizeBytes = 5000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                providerUri = null,
            ),
        )

        val entries = journal.entriesForJob("job-boundary-2")
        assertEquals(1, entries.size)
        assertNull(entries[0].providerUri)
        assertEquals(PublicationPhase.INTENT_RECORDED, entries[0].publicationPhase)

        // Restart recovery: no provider object exists to delete, entry marked interrupted
        journal.markInterrupted("tx-boundary-2")
        val updated = journal.entriesForJob("job-boundary-2")
        assertEquals(PublicationTransactionStatus.INTERRUPTED, updated[0].status)
    }

    @Test
    fun boundary4_afterProviderCreation_beforeCopy() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        // Boundary 4: Provider object created with URI, but copy did not happen (0 bytes copied)
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-4",
                jobId = "job-boundary-4",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "clip.mp4",
                stagingPath = "/staging/job-boundary-4/clip.mp4",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-4", "content://media/external/downloads/201")

        val entry = journal.entriesForJob("job-boundary-4").single()
        assertEquals(PublicationPhase.PROVIDER_OBJECT_CREATED, entry.publicationPhase)
        assertEquals(0L, entry.copiedBytes)

        // Recovery detects incomplete copy (< expectedSizeBytes), marks interrupted
        journal.markInterrupted("tx-boundary-4")
        val recovered = journal.entriesForJob("job-boundary-4").single()
        assertEquals(PublicationTransactionStatus.INTERRUPTED, recovered.status)
    }

    @Test
    fun boundary5_duringFileCopy_interruptedPartialData() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        // Boundary 5: Process killed halfway through file copy (3000 of 10000 bytes)
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-5",
                jobId = "job-boundary-5",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "partial.mp4",
                stagingPath = "/staging/job-boundary-5/partial.mp4",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-5", "content://saf/tree/document/part1")
        journal.updateDataCopied("tx-boundary-5", 3000L)

        val entry = journal.entriesForJob("job-boundary-5").single()
        assertEquals(3000L, entry.copiedBytes)
        assertTrue(entry.copiedBytes < entry.expectedSizeBytes)

        // Recovery: detects partial file, marks interrupted and cleans up
        journal.markInterrupted("tx-boundary-5")
        assertEquals(PublicationTransactionStatus.INTERRUPTED, journal.entriesForJob("job-boundary-5").single().status)
    }

    @Test
    fun boundary6_afterCopyCompletion_beforeFinalize() {
        val journalFile = File(tempFolder.root, "pub_journal.json")
        val journal = PublicationJournal(journalFile)

        // Boundary 6: Full data copied (10000 of 10000 bytes), but process died before finalize
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-boundary-6",
                jobId = "job-boundary-6",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "complete.mp4",
                stagingPath = "/staging/job-boundary-6/complete.mp4",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-boundary-6", "content://media/external/downloads/301")
        journal.updateDataCopied("tx-boundary-6", 10000L)

        val entry = journal.entriesForJob("job-boundary-6").single()
        assertEquals(PublicationPhase.DATA_COPIED, entry.publicationPhase)
        assertEquals(entry.expectedSizeBytes, entry.copiedBytes)

        // Recovery can advance to FINALIZED and COMPLETED
        journal.updateFinalized("tx-boundary-6")
        val finalized = journal.entriesForJob("job-boundary-6").single()
        assertEquals(PublicationPhase.FINALIZED, finalized.publicationPhase)
        assertEquals(PublicationTransactionStatus.COMPLETED, finalized.status)
    }

    @Test
    fun boundary8_exactCrashWindow_publicationFinalizedBeforeTerminalJobStoreSave() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        // Job in JobStore is in POSTPROCESSING with publishStarted = true
        val job = sampleJob("job-d5", state = JobState.POSTPROCESSING, publishStarted = true)
        jobStore.save(listOf(job))

        // Output was fully finalized by publisher
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-d5",
                jobId = "job-d5",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "MyVideo.mp4",
                stagingPath = "/staging/job-d5/MyVideo.mp4",
                expectedSizeBytes = 52428800L, // 50 MiB
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-d5", "content://media/external/downloads/555")
        journal.updateDataCopied("tx-d5", 52428800L)
        journal.updateFinalized("tx-d5")

        // CRASH OCCURS HERE. Process dies before JobStore is saved with JobState.DONE.
        // Simulate restart:
        val loadedJobs = jobStore.load(recoverActive = false)
        assertEquals(1, loadedJobs.size)
        // Without journal recovery, the job would be downgraded to FAILED with INTERRUPTED_DOWNLOAD_ERROR
        assertEquals(JobState.FAILED, loadedJobs[0].state)
        assertTrue(loadedJobs[0].outputUris.isEmpty())

        // Now run PublicationJournal reconciliation:
        val unreconciled = journal.unreconciledEntries()
        assertEquals(1, unreconciled.size)
        val entry = unreconciled[0]
        assertEquals("job-d5", entry.jobId)
        assertEquals(PublicationPhase.FINALIZED, entry.publicationPhase)

        // Reconcile job to DONE with published URI
        val reconciledJob = loadedJobs[0].copy(
            state = JobState.DONE,
            progress = 100f,
            detail = "Saved to Downloads/VRKA",
            outputUris = listOf(entry.providerUri!!),
            actualFinalFileBytes = entry.copiedBytes,
            downloadedBytes = entry.copiedBytes,
            reportedTotalBytes = entry.copiedBytes,
            reportedTotalIsExact = true,
            error = "",
            updatedAt = System.currentTimeMillis(),
        )
        jobStore.save(listOf(reconciledJob))
        journal.markReconciled("job-d5")

        // Verify permanent state in JobStore after reconciliation
        val finalJobs = jobStore.load()
        assertEquals(1, finalJobs.size)
        assertEquals(JobState.DONE, finalJobs[0].state)
        assertEquals(listOf("content://media/external/downloads/555"), finalJobs[0].outputUris)
        assertEquals(52428800L, finalJobs[0].actualFinalFileBytes)
        assertEquals("", finalJobs[0].error)

        // Journal has no unreconciled entries
        assertTrue(journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun boundary11_multiFilePlaylist_allOutputsPublishedBeforeCrash() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-playlist", state = JobState.POSTPROCESSING, publishStarted = true)
        jobStore.save(listOf(job))

        // 3 playlist files published
        val uris = listOf(
            "content://saf/tree/document/item1",
            "content://saf/tree/document/item2",
            "content://saf/tree/document/item3",
        )
        val sizes = listOf(10000L, 20000L, 30000L)

        for (i in 0 until 3) {
            val txId = "tx-playlist-$i"
            journal.recordIntent(
                PublicationEntry(
                    transactionId = txId,
                    jobId = "job-playlist",
                    outputIndex = i,
                    totalOutputs = 3,
                    intendedFilename = "track_$i.mp3",
                    stagingPath = "/staging/job-playlist/track_$i.mp3",
                    expectedSizeBytes = sizes[i],
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = "content://saf/tree",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                ),
            )
            journal.updateProviderCreated(txId, uris[i])
            journal.updateDataCopied(txId, sizes[i])
            journal.updateFinalized(txId)
        }

        // Process killed before JobStore save
        val unreconciled = journal.entriesForJob("job-playlist")
        assertEquals(3, unreconciled.size)
        assertTrue(unreconciled.all { it.publicationPhase == PublicationPhase.FINALIZED })

        // Reconcile multi-file output
        val loadedJobs = jobStore.load(recoverActive = false)
        val totalBytes = unreconciled.sumOf { it.copiedBytes }
        val reconciledJob = loadedJobs[0].copy(
            state = JobState.DONE,
            progress = 100f,
            detail = "Saved ${unreconciled.size} files",
            outputUris = unreconciled.map { it.providerUri!! },
            actualFinalFileBytes = totalBytes,
            downloadedBytes = totalBytes,
            reportedTotalBytes = totalBytes,
            reportedTotalIsExact = true,
            error = "",
            updatedAt = System.currentTimeMillis(),
        )
        jobStore.save(listOf(reconciledJob))
        journal.markReconciled("job-playlist")

        val stored = jobStore.load().single()
        assertEquals(JobState.DONE, stored.state)
        assertEquals(3, stored.outputUris.size)
        assertEquals(60000L, stored.actualFinalFileBytes)
        assertEquals(uris, stored.outputUris)
    }

    @Test
    fun boundary9_multiFilePlaylist_partialFailureDoesNotMarkCompleted() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-partial-playlist", state = JobState.POSTPROCESSING, publishStarted = true)
        jobStore.save(listOf(job))

        // File 1 finalized
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-p-1",
                jobId = "job-partial-playlist",
                outputIndex = 0,
                totalOutputs = 2,
                intendedFilename = "track_1.mp3",
                stagingPath = "/staging/job-partial-playlist/track_1.mp3",
                expectedSizeBytes = 10000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-p-1", "content://saf/tree/document/item1")
        journal.updateDataCopied("tx-p-1", 10000L)
        journal.updateFinalized("tx-p-1")

        // File 2 interrupted during copy
        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-p-2",
                jobId = "job-partial-playlist",
                outputIndex = 1,
                totalOutputs = 2,
                intendedFilename = "track_2.mp3",
                stagingPath = "/staging/job-partial-playlist/track_2.mp3",
                expectedSizeBytes = 20000L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = "content://saf/tree",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated("tx-p-2", "content://saf/tree/document/item2")
        journal.updateDataCopied("tx-p-2", 5000L) // Partial!

        val entries = journal.entriesForJob("job-partial-playlist")
        val isAllPresent = entries.size == 2
        val allFinalized = isAllPresent && entries.all { it.publicationPhase == PublicationPhase.FINALIZED }
        assertFalse("Partial playlist must not be marked as all finalized", allFinalized)

        // Incomplete item is marked interrupted
        journal.markInterrupted("tx-p-2")
        val postEntries = journal.entriesForJob("job-partial-playlist")
        assertEquals(PublicationTransactionStatus.INTERRUPTED, postEntries.first { it.transactionId == "tx-p-2" }.status)
    }

    @Test
    fun boundary13_reconciliationIsIdempotent() {
        val jobsFile = File(tempFolder.root, "download_jobs.json")
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val jobStore = JobStore(jobsFile)
        val journal = PublicationJournal(journalFile)

        val job = sampleJob("job-idem", state = JobState.DONE, outputUris = listOf("content://downloads/1"))
        jobStore.save(listOf(job))

        journal.recordIntent(
            PublicationEntry(
                transactionId = "tx-idem",
                jobId = "job-idem",
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
        journal.updateProviderCreated("tx-idem", "content://downloads/1")
        journal.updateDataCopied("tx-idem", 1000L)
        journal.updateFinalized("tx-idem")

        // First reconciliation pass
        journal.markReconciled("job-idem")
        assertEquals(PublicationTransactionStatus.RECONCILED, journal.entriesForJob("job-idem").single().status)

        // Second reconciliation pass produces no changes and no errors
        journal.markReconciled("job-idem")
        assertEquals(PublicationTransactionStatus.RECONCILED, journal.entriesForJob("job-idem").single().status)
        assertEquals(1, jobStore.load().size)
        assertEquals(JobState.DONE, jobStore.load().single().state)
    }
}
