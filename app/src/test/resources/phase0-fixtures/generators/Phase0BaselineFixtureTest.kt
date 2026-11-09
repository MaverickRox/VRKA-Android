package com.mvrk.vrka

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 0 only. Generates unreleased-baseline fixtures using the current Journal and
 * StagingCleanupStore implementations. These formats are not present in v5.1.2.
 */
class Phase0BaselineFixtureTest {
    private val fixturesRoot = File(System.getenv("VRKA_PHASE0_FIXTURES") ?: error("VRKA_PHASE0_FIXTURES missing"))
    private val tag = System.getenv("VRKA_PHASE0_TAG") ?: error("VRKA_PHASE0_TAG missing")
    private val commit = System.getenv("VRKA_PHASE0_COMMIT") ?: error("VRKA_PHASE0_COMMIT missing")
    private val work = File(fixturesRoot, "_work-$tag").apply { mkdirs() }
    private val out = File(fixturesRoot, tag).apply { mkdirs() }
    private val manifest = JSONArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(
        jobId: String = "job-a",
        txId: String = "tx-a",
        providerType: PublicationProviderType = PublicationProviderType.MEDIA_STORE,
        providerUri: String? = null,
    ) = PublicationEntry(
        transactionId = txId,
        jobId = jobId,
        outputIndex = 0,
        totalOutputs = 1,
        intendedFilename = "video.mp4",
        pendingFilename = PublicationUriRules.pendingNameFor(txId, "video.mp4"),
        stagingPath = File(work, "staging/$jobId/video.mp4").absolutePath,
        expectedSizeBytes = 10L,
        providerType = providerType,
        destinationIdentity = if (providerType == PublicationProviderType.SAF) {
            "content://saf/tree/root"
        } else {
            PublicationUriRules.MEDIA_STORE_DESTINATION
        },
        publicationPhase = PublicationPhase.INTENT_RECORDED,
    )

    private fun record(caseName: String, file: File, description: String, derivation: String) {
        val target = File(out, "$caseName.json")
        target.writeText(file.readText(Charsets.UTF_8), Charsets.UTF_8)
        manifest.put(
            JSONObject()
                .put("case", caseName)
                .put("file", "$tag/$caseName.json")
                .put("bytes", target.length())
                .put("sha256", sha256(target.readBytes()))
                .put("sourceTag", tag)
                .put("sourceCommit", commit)
                .put("sourceEncoder", "current baseline PublicationJournal/StagingCleanupStore/JobStore")
                .put("description", description)
                .put("derivation", derivation),
        )
    }

    @Test
    fun generateBaselineCorpus() {
        // Current JobStore schema 2 with retention-compatible records.
        val jobs = (1..3).map { index ->
            DownloadJob(
                id = "baseline-job-$index",
                request = DownloadRequest(url = "https://example.com/baseline/$index"),
                state = if (index == 3) JobState.POSTPROCESSING else JobState.DONE,
                title = "Baseline $index",
                publishStarted = index == 3,
                createdAt = 1_700_000_000_000L + index,
            )
        }
        val jobFile = File(work, "baseline-jobs.work.json")
        assertTrue(JobStore(jobFile).save(jobs, jobs.mapTo(HashSet()) { it.id }).success)
        record("baseline-jobstore-schema2", jobFile, "Current baseline JobStore schema 2 with terminal and publishing records.", "direct current encoder output")

        // Current journal with CLEANUP_PENDING.
        val cleanupJournalFile = File(work, "cleanup-pending.work.json")
        PublicationJournal(cleanupJournalFile).apply {
            recordIntent(entry())
            updateProviderCreated("tx-a", "content://media/external/downloads/100")
            markCleanupPending("tx-a", "Fixture cleanup failure")
        }
        record("baseline-journal-cleanup-pending", cleanupJournalFile, "Current baseline CLEANUP_PENDING journal entry.", "direct current API sequence")

        // Current journal with transaction-scoped reconciliation of an intent.
        val reconciledFile = File(work, "reconciled.work.json")
        PublicationJournal(reconciledFile).apply {
            recordIntent(entry(txId = "tx-intent"))
            markReconciled(setOf("tx-intent"))
        }
        record("baseline-journal-reconciled-intent", reconciledFile, "Current baseline transaction-scoped recon of an intent record.", "direct current API sequence")

        // Unsupported schema version.
        val unsupported = File(work, "unsupported.work.json")
        unsupported.writeText(JSONObject().put("schemaVersion", 99).put("entries", JSONArray()).toString())
        record("baseline-journal-unsupported-schema", unsupported, "Current baseline journal with an unsupported schema version.", "derived: version-only wrapper")

        // StagingCleanupStore schema 1.
        val cleanupStoreFile = File(work, "cleanup-store.work.json")
        StagingCleanupStore(cleanupStoreFile).apply {
            markPending("job-a")
            markPending("job-b")
        }
        record("baseline-staging-cleanup-store", cleanupStoreFile, "Current baseline StagingCleanupStore schema 1 with two jobs.", "direct current API sequence")

        File(out, "manifest-baseline.json").writeText(manifest.toString(2), Charsets.UTF_8)
        assertTrue(manifest.length() >= 5)
    }
}
