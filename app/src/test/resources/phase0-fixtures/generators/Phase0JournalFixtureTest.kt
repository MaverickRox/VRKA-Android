package com.mvrk.vrka

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 0 only. Generates v5.1.2 publication-journal fixtures by invoking the actual tagged
 * PublicationJournal mutation API. Derived/corrupt records are labeled as such.
 */
class Phase0JournalFixtureTest {
    private val fixturesRoot = File(System.getenv("VRKA_PHASE0_FIXTURES") ?: error("VRKA_PHASE0_FIXTURES missing"))
    private val tag = System.getenv("VRKA_PHASE0_TAG") ?: error("VRKA_PHASE0_TAG missing")
    private val commit = System.getenv("VRKA_PHASE0_COMMIT") ?: error("VRKA_PHASE0_COMMIT missing")
    private val work = File(fixturesRoot, "_work-$tag").apply { mkdirs() }
    private val out = File(fixturesRoot, tag).apply { mkdirs() }
    private val manifest = JSONArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stagingPath(jobId: String, finalName: String): String =
        File(work, "staging/$jobId/$finalName").absolutePath

    private fun entry(
        jobId: String = "job-a",
        txId: String = "tx-a",
        index: Int = 0,
        total: Int = 1,
        finalName: String = "video.mp4",
        providerType: PublicationProviderType = PublicationProviderType.MEDIA_STORE,
        providerUri: String? = null,
        copiedBytes: Long = 0L,
        createdAt: Long = 1_700_000_000_000L,
    ) = PublicationEntry(
        transactionId = txId,
        jobId = jobId,
        outputIndex = index,
        totalOutputs = total,
        intendedFilename = finalName,
        pendingFilename = ".vrka_pending_${txId}_$finalName",
        stagingPath = stagingPath(jobId, finalName),
        expectedSizeBytes = 10L,
        providerType = providerType,
        destinationIdentity = if (providerType == PublicationProviderType.SAF) {
            "content://saf/tree/root"
        } else {
            "Downloads/VRKA"
        },
        publicationPhase = PublicationPhase.INTENT_RECORDED,
        providerUri = providerUri,
        copiedBytes = copiedBytes,
        status = PublicationTransactionStatus.PENDING,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun newJournal(name: String): Pair<PublicationJournal, File> {
        val file = File(work, "$name.work.json")
        return PublicationJournal(file) to file
    }

    private fun record(
        caseName: String,
        file: File,
        description: String,
        derivation: String,
    ) {
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
                .put("sourceEncoder", "app/src/main/java/com/mvrk/vrka/PublicationJournal.kt recordIntent/update*/mark* + encode()")
                .put("description", description)
                .put("derivation", derivation),
        )
    }

    private fun mutateAndRecord(
        caseName: String,
        source: File,
        description: String,
        derivation: String,
        mutate: (JSONObject) -> Unit,
    ) {
        val json = JSONObject(source.readText(Charsets.UTF_8))
        mutate(json)
        val target = File(out, "$caseName.json")
        target.writeText(json.toString(), Charsets.UTF_8)
        manifest.put(
            JSONObject()
                .put("case", caseName)
                .put("file", "$tag/$caseName.json")
                .put("bytes", target.length())
                .put("sha256", sha256(target.readBytes()))
                .put("sourceTag", tag)
                .put("sourceCommit", commit)
                .put("sourceEncoder", "PublicationJournal.kt encoder output, then fixture mutation")
                .put("description", description)
                .put("derivation", derivation),
        )
    }

    @Test
    fun generateJournalCorpus() {
        // Intent only.
        newJournal("intent").let { (journal, file) ->
            journal.recordIntent(entry())
            record("journal-intent-pending", file, "Intent recorded, no provider object.", "direct API sequence")
        }

        // Provider-created.
        newJournal("created").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            record("journal-provider-created", file, "Provider object created and URI recorded.", "direct API sequence")
        }

        // Data copied.
        newJournal("copied").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-a", 10L)
            record("journal-data-copied", file, "Bytes copied, expected size recorded.", "direct API sequence")
        }

        // Output validated.
        newJournal("validated").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-a", 10L)
            journal.updateValidated("tx-a")
            record("journal-output-validated", file, "Output validated before finalization.", "direct API sequence")
        }

        // Finalized/completed.
        val finalizedFile = newJournal("finalized").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-a", 10L)
            journal.updateValidated("tx-a")
            journal.updateFinalized("tx-a")
            file
        }
        record("journal-finalized-completed", finalizedFile, "Finalized/completed with provider URI.", "direct API sequence")
        record("journal-missing-staging-finalized", finalizedFile, "Finalized record whose staging path does not exist.", "direct API sequence (staging file intentionally absent)")

        // Recorded/completed (API reachable, not production orchestration).
        newJournal("recorded").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-a", 10L)
            journal.updateValidated("tx-a")
            journal.updateFinalized("tx-a")
            journal.updateRecorded("tx-a")
            record("journal-recorded-completed", file, "Recorded/completed via updateRecorded().", "direct API sequence (method not called by production orchestration)")
        }

        // SAF shape.
        newJournal("saf-finalized").let { (journal, file) ->
            journal.recordIntent(entry(providerType = PublicationProviderType.SAF))
            journal.updateProviderCreated("tx-a", "content://saf/tree/root/document/1")
            journal.updateDataCopied("tx-a", 10L)
            journal.updateValidated("tx-a")
            journal.updateFinalized("tx-a")
            record("journal-saf-finalized", file, "SAF finalized record with a plain final name (no transaction token).", "direct API sequence")
        }

        // Failed and interrupted at each reached phase.
        for ((label, phaseSetter) in listOf<Pair<String, (PublicationJournal) -> Unit>>(
            "intent" to { },
            "provider-created" to { j -> j.updateProviderCreated("tx-a", "content://media/external/downloads/100") },
            "data-copied" to { j ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
            },
            "output-validated" to { j ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
                j.updateValidated("tx-a")
            },
            "finalized" to { j ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
                j.updateValidated("tx-a")
                j.updateFinalized("tx-a")
            },
        )) {
            newJournal("failed-$label").let { (journal, file) ->
                journal.recordIntent(entry())
                phaseSetter(journal)
                journal.markFailed("tx-a", "fixture failure")
                record("journal-failed-$label", file, "FAILED status after $label.", "direct API sequence: markFailed()")
            }
            newJournal("interrupted-$label").let { (journal, file) ->
                journal.recordIntent(entry())
                phaseSetter(journal)
                journal.markInterrupted("tx-a")
                record("journal-interrupted-$label", file, "INTERRUPTED status after $label.", "direct API sequence: markInterrupted()")
            }
        }

        // Reconciled intents and earlier phases (the legacy job-scoped behavior).
        val reconciledCases = listOf(
            "intent" to { _: PublicationJournal -> },
            "provider-created" to { j: PublicationJournal -> j.updateProviderCreated("tx-a", "content://media/external/downloads/100") },
            "data-copied" to { j: PublicationJournal ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
            },
            "output-validated" to { j: PublicationJournal ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
                j.updateValidated("tx-a")
            },
            "finalized" to { j: PublicationJournal ->
                j.updateProviderCreated("tx-a", "content://media/external/downloads/100")
                j.updateDataCopied("tx-a", 10L)
                j.updateValidated("tx-a")
                j.updateFinalized("tx-a")
            },
        )
        for ((label, setter) in reconciledCases) {
            newJournal("reconciled-$label").let { (journal, file) ->
                journal.recordIntent(entry())
                setter(journal)
                journal.markReconciled("job-a")
                record("journal-reconciled-from-$label", file, "RECONCILED from $label via job-scoped markReconciled().", "direct API sequence: markReconciled(jobId)")
            }
        }

        // Reconciled after FAILED/INTERRUPTED (legacy job-scoped reconciliation can do this).
        newJournal("reconciled-failed").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-a", 10L)
            journal.markFailed("tx-a", "fixture failure before reconciliation")
            journal.markReconciled("job-a")
            record("journal-reconciled-failed-after-copy", file, "RECONCILED after FAILED at data-copied.", "direct API sequence: markFailed() then markReconciled(jobId)")
        }
        newJournal("reconciled-interrupted").let { (journal, file) ->
            journal.recordIntent(entry())
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.markInterrupted("tx-a")
            journal.markReconciled("job-a")
            record("journal-reconciled-interrupted-after-create", file, "RECONCILED after INTERRUPTED at provider-created.", "direct API sequence: markInterrupted() then markReconciled(jobId)")
        }

        // Two records for one job reconciled by job ID.
        newJournal("multi-reconciled").let { (journal, file) ->
            journal.recordBatchIntents(
                listOf(
                    entry(txId = "tx-a", index = 0, total = 2, finalName = "a.mp4", createdAt = 1_700_000_000_000L),
                    entry(txId = "tx-b", index = 1, total = 2, finalName = "b.mp4", createdAt = 1_700_000_000_001L),
                ),
            )
            journal.updateProviderCreated("tx-a", "content://media/external/downloads/100")
            journal.markFailed("tx-b", "fixture failure")
            journal.markReconciled("job-a")
            record("journal-multi-entry-job-reconciled", file, "Two entries for one job retired by job-scoped reconciliation.", "direct API sequence")
        }

        // Multiple retry attempts with changed output count.
        newJournal("retry-changed-count").let { (journal, file) ->
            journal.recordBatchIntents(
                listOf(
                    entry(txId = "tx-old-1", index = 0, total = 2, finalName = "old-a.mp4"),
                    entry(txId = "tx-old-2", index = 1, total = 2, finalName = "old-b.mp4"),
                ),
            )
            journal.updateProviderCreated("tx-old-1", "content://media/external/downloads/100")
            journal.updateDataCopied("tx-old-1", 10L)
            journal.updateValidated("tx-old-1")
            journal.updateFinalized("tx-old-1")
            journal.recordBatchIntents(
                listOf(
                    entry(txId = "tx-new-1", index = 0, total = 3, finalName = "new-a.mp4", createdAt = 1_700_000_001_000L),
                    entry(txId = "tx-new-2", index = 1, total = 3, finalName = "new-b.mp4", createdAt = 1_700_000_001_001L),
                    entry(txId = "tx-new-3", index = 2, total = 3, finalName = "new-c.mp4", createdAt = 1_700_000_001_002L),
                ),
            )
            record("journal-retry-changed-output-count", file, "Same job ID with a completed 2-output batch and a newer 3-output batch.", "direct API sequence")
        }

        // Multiple retry attempts with the same output count (genuinely ambiguous identity).
        newJournal("retry-same-count").let { (journal, file) ->
            journal.recordBatchIntents(
                listOf(
                    entry(txId = "tx-old-1", index = 0, total = 2, finalName = "old-a.mp4"),
                    entry(txId = "tx-old-2", index = 1, total = 2, finalName = "old-b.mp4"),
                ),
            )
            journal.recordBatchIntents(
                listOf(
                    entry(txId = "tx-new-1", index = 0, total = 2, finalName = "new-a.mp4", createdAt = 1_700_000_001_000L),
                    entry(txId = "tx-new-2", index = 1, total = 2, finalName = "new-b.mp4", createdAt = 1_700_000_001_001L),
                ),
            )
            record("journal-retry-same-output-count", file, "Same job ID with two indistinguishable 2-output attempts.", "direct API sequence")
        }

        // Derived records: duplicate transaction ID, ignored/missing schema version, synthesized defaults, corruption.
        val validSource = finalizedFile
        mutateAndRecord(
            "journal-duplicate-transaction-ids",
            validSource,
            "Two journal entries with the same transaction ID.",
            "derived: first entry duplicated in the encoder-produced JSON",
        ) { json ->
            val entries = json.getJSONArray("entries")
            entries.put(entries.getJSONObject(0))
        }
        mutateAndRecord(
            "journal-ignored-schema-version",
            validSource,
            "schemaVersion 99 with otherwise valid entries; the v5.1.2 decoder ignores the version.",
            "derived: schemaVersion changed to 99",
        ) { json -> json.put("schemaVersion", 99) }
        mutateAndRecord(
            "journal-missing-schema-version",
            validSource,
            "Valid entries without a schemaVersion field; the v5.1.2 decoder accepts it.",
            "derived: schemaVersion removed",
        ) { json -> json.remove("schemaVersion") }
        mutateAndRecord(
            "journal-synthesized-defaults",
            validSource,
            "Corrupted entry missing jobId, pendingFilename, phase, providerType, copiedBytes, status, and timestamps; the v5.1.2 decoder synthesizes defaults.",
            "derived: fields removed from encoder output",
        ) { json ->
            val entry = json.getJSONArray("entries").getJSONObject(0)
            listOf(
                "jobId", "pendingFilename", "publicationPhase", "providerType",
                "copiedBytes", "status", "createdAt", "updatedAt",
            ).forEach(entry::remove)
        }
        record(
            "journal-truncated",
            File(work, "truncated.work.json").apply {
                writeText(validSource.readText(Charsets.UTF_8).take((validSource.length() * 0.6).toInt()))
            },
            "Partially written journal (truncated at 60%).",
            "derived: encoder output truncated",
        )
        newJournal("empty").let { (_, file) ->
            file.writeText("")
            record("journal-empty", file, "Empty journal file.", "derived: zero bytes")
        }

        manifest.put(
            JSONObject()
                .put("case", "missing-journal")
                .put("file", JSONObject.NULL)
                .put("sourceTag", tag)
                .put("sourceCommit", commit)
                .put("description", "No publication journal exists (v5.1.0/v5.1.1 or first run).")
                .put("derivation", "absence"),
        )

        File(out, "manifest-journal.json").writeText(manifest.toString(2), Charsets.UTF_8)
        assertTrue(manifest.length() >= 20)
    }
}
