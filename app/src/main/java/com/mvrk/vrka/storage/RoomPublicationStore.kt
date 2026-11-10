package com.mvrk.vrka.storage

import androidx.room.withTransaction
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournalLoadResult
import com.mvrk.vrka.PublicationJournalLoadState
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationTransactionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

internal class RoomPublicationStore(
    private val database: VrkaDatabase,
    private val repository: RoomStorageRepository,
    private val clock: () -> Long,
) : PublicationStore {
    @Volatile
    override var lastLoadResult: PublicationJournalLoadResult? = null
        private set

    @Volatile
    private var decodeFailure = false

    override fun recordIntent(entry: PublicationEntry) = recordBatchIntents(listOf(entry))

    override fun recordBatchIntents(batch: List<PublicationEntry>) {
        blocking {
            database.withTransaction {
                for (entry in batch) {
                    val (attemptId, batchId) = ensureAttribution(entry)
                    database.publicationOutputDao().upsert(
                        toEntity(entry, attemptId, batchId, existing = database.publicationOutputDao().byId(entry.transactionId)),
                    )
                }
            }
        }
    }

    override fun updateProviderCreated(transactionId: String, providerUri: String) = mutate(transactionId) {
        it.copy(
            providerUri = providerUri,
            providerDocumentId = providerUri.substringAfterLast('/').takeIf { it.isNotBlank() },
            publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
            status = PublicationTransactionStatus.IN_PROGRESS,
        )
    }

    override fun updateProviderUri(transactionId: String, providerUri: String) = mutate(transactionId) {
        it.copy(
            providerUri = providerUri,
            providerDocumentId = providerUri.substringAfterLast('/').takeIf { id -> id.isNotBlank() },
        )
    }

    override fun updateDataCopied(transactionId: String, copiedBytes: Long) = mutate(transactionId) {
        it.copy(
            copiedBytes = copiedBytes,
            publicationPhase = PublicationPhase.DATA_COPIED,
            status = PublicationTransactionStatus.IN_PROGRESS,
        )
    }

    override fun updateValidated(transactionId: String) = mutate(transactionId) {
        it.copy(
            publicationPhase = PublicationPhase.OUTPUT_VALIDATED,
            verificationLevel = if (it.verificationLevel.isBlank()) "SIZE_VERIFIED" else it.verificationLevel,
        )
    }

    override fun updateVerification(
        transactionId: String,
        verificationLevel: String,
        observedSha256: String?,
    ) = mutate(transactionId) {
        it.copy(
            verificationLevel = verificationLevel,
            observedSha256 = observedSha256 ?: it.observedSha256,
            publicationPhase = PublicationPhase.OUTPUT_VALIDATED,
        )
    }

    override fun updateFinalized(transactionId: String, newUri: String?) = mutate(transactionId) {
        it.copy(
            publicationPhase = PublicationPhase.FINALIZED,
            status = PublicationTransactionStatus.COMPLETED,
            providerUri = newUri ?: it.providerUri,
            verificationLevel = if (it.verificationLevel.isBlank()) "SIZE_VERIFIED" else it.verificationLevel,
        )
    }

    override fun updateRecorded(transactionId: String) = mutate(transactionId) {
        it.copy(publicationPhase = PublicationPhase.RECORDED, status = PublicationTransactionStatus.COMPLETED)
    }

    override fun markInterrupted(transactionId: String) = mutate(transactionId) {
        it.copy(status = PublicationTransactionStatus.INTERRUPTED)
    }

    override fun markFailed(transactionId: String, error: String) = mutate(transactionId) {
        if (it.status == PublicationTransactionStatus.CLEANUP_PENDING) {
            it.copy(errorMessage = error)
        } else {
            it.copy(status = PublicationTransactionStatus.FAILED, errorMessage = error)
        }
    }

    override fun markCleanupPending(transactionId: String, error: String?) {
        mutate(transactionId) {
            it.copy(status = PublicationTransactionStatus.CLEANUP_PENDING, errorMessage = error ?: it.errorMessage)
        }
        upsertProviderCleanupTask(transactionId)
    }

    override fun updateDeleted(transactionId: String) {
        mutate(transactionId) {
            it.copy(status = PublicationTransactionStatus.INTERRUPTED, providerUri = null)
        }
        blocking {
            database.cleanupTaskDao().deleteByResource(PROVIDER_OBJECT_RESOURCE, transactionId)
        }
    }

    private fun upsertProviderCleanupTask(transactionId: String) {
        blocking {
            database.withTransaction {
                val output = database.publicationOutputDao().byId(transactionId) ?: return@withTransaction
                val existing = database.cleanupTaskDao().byResource(PROVIDER_OBJECT_RESOURCE, transactionId)
                val retries = (existing?.retryCount ?: 0) + 1
                val now = clock()
                database.cleanupTaskDao().upsert(
                    CleanupTaskEntity(
                        id = existing?.id ?: "provider:$transactionId",
                        jobId = output.jobId,
                        attemptId = output.attemptId,
                        transactionId = transactionId,
                        resourceType = PROVIDER_OBJECT_RESOURCE,
                        resourceIdentity = transactionId,
                        stage = "CLEANUP_PENDING",
                        retryCount = retries,
                        nextRetryAt = now + minOf(3600L, 1L shl retries.coerceAtMost(12)) * 1000L,
                        lastErrorClass = "PROVIDER_CLEANUP_FAILED",
                        lastError = output.errorMessage,
                        deadLetter = retries >= MAX_PROVIDER_CLEANUP_RETRIES,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override fun markReconciled(transactionIds: Collection<String>) {
        if (transactionIds.isEmpty()) return
        blocking {
            database.withTransaction {
                for (transactionId in transactionIds.toSet()) {
                    val output = database.publicationOutputDao().byId(transactionId) ?: continue
                    if (output.status == PublicationTransactionStatus.CLEANUP_PENDING.name) continue
                    val phase = if (output.phase == PublicationPhase.FINALIZED.name) {
                        PublicationPhase.RECORDED.name
                    } else {
                        output.phase
                    }
                    database.publicationOutputDao().upsert(
                        output.copy(
                            status = PublicationTransactionStatus.RECONCILED.name,
                            phase = phase,
                            updatedAt = clock(),
                            version = output.version + 1L,
                        ),
                    )
                    database.cleanupTaskDao().deleteByResource(PROVIDER_OBJECT_RESOURCE, transactionId)
                }
            }
        }
    }

    override fun clearJob(jobId: String) {
        blocking {
            database.withTransaction {
                val unresolved = database.publicationOutputDao().unreconciledForJob(jobId)
                if (unresolved.isNotEmpty()) return@withTransaction
                if (database.cleanupTaskDao().forJob(jobId).isNotEmpty()) return@withTransaction
                database.publicationOutputDao().deleteForJob(jobId)
                database.publicationBatchDao().deleteForJob(jobId)
            }
        }
    }

    override fun clearAll() {
        blocking {
            database.withTransaction {
                for (job in database.jobDao().all()) {
                    val unresolved = database.publicationOutputDao().unreconciledForJob(job.id)
                    if (unresolved.isNotEmpty()) continue
                    if (database.cleanupTaskDao().forJob(job.id).isNotEmpty()) continue
                    database.publicationOutputDao().deleteForJob(job.id)
                    database.publicationBatchDao().deleteForJob(job.id)
                }
            }
        }
    }

    override fun entriesForJob(jobId: String): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().forJob(jobId) }
        return entities.mapNotNull { decodeAndQuarantine(it) }
    }

    override fun unreconciledEntries(): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().unreconciled() }
        return entities.mapNotNull { decodeAndQuarantine(it) }
    }

    override fun allEntries(): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().all() }
        return entities.mapNotNull { decodeAndQuarantine(it) }
    }

    private fun decodeAndQuarantine(output: PublicationOutputEntity): PublicationEntry? {
        val entry = decodeEntry(output)
        if (entry == null) quarantineOutput(output)
        return entry
    }

    private fun decodeEntry(output: PublicationOutputEntity): PublicationEntry? =
        runCatching {
            toEntry(output, database.publicationBatchDao().byId(output.batchId))
        }.getOrElse {
            decodeFailure = true
            null
        }

    private fun quarantineOutput(output: PublicationOutputEntity) {
        runCatching {
            blocking {
                database.migrationDao().insertRecord(
                    MigrationRecordEntity(
                        sourceKey = "room_publication_outputs",
                        sourceRow = 0,
                        reason = "OUTPUT_DECODE_FAILURE:${output.transactionId}",
                        rawJson = org.json.JSONObject()
                            .put("transactionId", output.transactionId)
                            .put("jobId", output.jobId)
                            .put("attemptId", output.attemptId)
                            .put("attemptNumber", output.attemptNumber)
                            .put("batchId", output.batchId)
                            .put("outputIndex", output.outputIndex)
                            .put("totalOutputs", output.totalOutputs)
                            .put("providerType", output.providerType)
                            .put("destinationIdentity", output.destinationIdentity)
                            .put("pendingFilename", output.pendingFilename)
                            .put("finalFilename", output.finalFilename)
                            .put("providerUri", output.providerUri)
                            .put("providerDocumentId", output.providerDocumentId)
                            .put("stagingKey", output.stagingKey)
                            .put("expectedSizeBytes", output.expectedSizeBytes)
                            .put("expectedSha256", output.expectedSha256)
                            .put("observedSha256", output.observedSha256)
                            .put("copiedBytes", output.copiedBytes)
                            .put("verificationLevel", output.verificationLevel)
                            .put("providerCapabilitiesJson", output.providerCapabilitiesJson)
                            .put("phase", output.phase)
                            .put("status", output.status)
                            .put("cleanupState", output.cleanupState)
                            .put("errorMessage", output.errorMessage)
                            .put("createdAt", output.createdAt)
                            .put("updatedAt", output.updatedAt)
                            .put("version", output.version)
                            .toString(),
                        createdAt = clock(),
                    ),
                )
            }
        }
    }

    override fun loadWithStatus(): PublicationJournalLoadResult {
        decodeFailure = false
        val result = runCatching { allEntries() }
            .fold(
                onSuccess = { entries ->
                    when {
                        decodeFailure -> PublicationJournalLoadResult(
                            entries = emptyList(),
                            state = PublicationJournalLoadState.CORRUPT,
                            detail = "One or more publication rows contain unknown persisted values; raw rows were preserved for recovery",
                        )
                        entries.isEmpty() -> PublicationJournalLoadResult(emptyList(), PublicationJournalLoadState.EMPTY)
                        else -> PublicationJournalLoadResult(entries, PublicationJournalLoadState.OK)
                    }
                },
                onFailure = { error ->
                    PublicationJournalLoadResult(
                        emptyList(),
                        PublicationJournalLoadState.READ_FAILED,
                        detail = error.message.orEmpty().take(300),
                    )
                },
            )
        lastLoadResult = result
        return result
    }

    override fun updatePhase(transactionId: String, phase: PublicationPhase) = mutate(transactionId) {
        it.copy(publicationPhase = phase)
    }

    private fun mutate(transactionId: String, transform: (PublicationEntry) -> PublicationEntry) {
        blocking {
            database.withTransaction {
                val output = database.publicationOutputDao().byId(transactionId) ?: return@withTransaction
                val entry = decodeEntry(output) ?: return@withTransaction
                val updated = transform(entry)
                val (attemptId, batchId) = ensureAttribution(updated)
                database.publicationOutputDao().upsert(toEntity(updated, attemptId, batchId, output))
            }
        }
    }

    private fun ensureAttribution(entry: PublicationEntry): Pair<String, String> {
        val job = database.jobDao().byId(entry.jobId)
            ?: error("Publication entry references unknown job ${entry.jobId}")
        val attemptId = entry.attemptId.ifBlank {
            JobJsonCodec.attemptIdFor(entry.jobId, entry.attemptNumber)
        }
        val attempt = database.attemptDao().byId(attemptId)
        if (attempt == null) {
            database.attemptDao().upsert(
                AttemptEntity(
                    attemptId = attemptId,
                    jobId = entry.jobId,
                    attemptNumber = entry.attemptNumber,
                    requestJson = job.requestJson,
                    destinationTreeUri = null,
                    status = "IN_PROGRESS",
                    startedAt = entry.createdAt,
                    endedAt = null,
                    legacyTrust = entry.verificationLevel,
                    version = 1L,
                ),
            )
        }
        val batchId = entry.batchId.ifBlank {
            "batch:${entry.jobId}:${entry.attemptNumber}:${sha256(entry.transactionId).take(24)}"
        }
        val batch = database.publicationBatchDao().byId(batchId)
        if (batch == null) {
            database.publicationBatchDao().upsert(
                PublicationBatchEntity(
                    batchId = batchId,
                    jobId = entry.jobId,
                    attemptId = attemptId,
                    totalOutputs = entry.totalOutputs,
                    providerType = entry.providerType.name,
                    destinationIdentity = entry.destinationIdentity,
                    status = "IN_PROGRESS",
                    legacyAmbiguous = entry.legacyAmbiguous ||
                        entry.batchId.isBlank() ||
                        entry.attemptId.isBlank(),
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                ),
            )
        } else if ((entry.legacyAmbiguous || entry.batchId.isBlank() || entry.attemptId.isBlank()) &&
            !batch.legacyAmbiguous
        ) {
            database.publicationBatchDao().upsert(
                batch.copy(legacyAmbiguous = true, updatedAt = clock()),
            )
        }
        return attemptId to batchId
    }

    private fun toEntity(
        entry: PublicationEntry,
        attemptId: String,
        batchId: String,
        existing: PublicationOutputEntity?,
    ): PublicationOutputEntity = PublicationOutputEntity(
        transactionId = entry.transactionId,
        jobId = entry.jobId,
        attemptId = attemptId,
        attemptNumber = entry.attemptNumber,
        batchId = batchId,
        outputIndex = entry.outputIndex,
        totalOutputs = entry.totalOutputs,
        providerType = entry.providerType.name,
        destinationIdentity = entry.destinationIdentity,
        pendingFilename = entry.pendingFilename,
        finalFilename = entry.intendedFilename,
        providerUri = entry.providerUri,
        providerDocumentId = entry.providerDocumentId,
        stagingKey = entry.stagingPath.takeIf { it.isNotBlank() },
        expectedSizeBytes = entry.expectedSizeBytes,
        expectedSha256 = entry.expectedSha256,
        observedSha256 = entry.observedSha256 ?: existing?.observedSha256,
        copiedBytes = entry.copiedBytes,
        verificationLevel = entry.verificationLevel.ifBlank { existing?.verificationLevel.orEmpty() },
        providerCapabilitiesJson = entry.providerCapabilitiesJson ?: existing?.providerCapabilitiesJson,
        phase = entry.publicationPhase.name,
        status = entry.status.name,
        cleanupState = existing?.cleanupState.orEmpty(),
        errorMessage = entry.errorMessage,
        createdAt = existing?.createdAt ?: entry.createdAt,
        updatedAt = clock(),
        version = (existing?.version ?: 0L) + 1L,
    )

    private fun toEntry(
        output: PublicationOutputEntity,
        batch: PublicationBatchEntity?,
    ): PublicationEntry {
        return PublicationEntry(
            transactionId = output.transactionId,
            jobId = output.jobId,
            outputIndex = output.outputIndex,
            totalOutputs = output.totalOutputs,
            intendedFilename = output.finalFilename,
            pendingFilename = output.pendingFilename,
            stagingPath = output.stagingKey.orEmpty(),
            expectedSizeBytes = output.expectedSizeBytes,
            providerType = strictEnum(output.providerType, "providerType"),
            destinationIdentity = output.destinationIdentity,
            publicationPhase = strictEnum(output.phase, "phase"),
            providerUri = output.providerUri,
            copiedBytes = output.copiedBytes,
            status = strictEnum(output.status, "status"),
            createdAt = output.createdAt,
            updatedAt = output.updatedAt,
            errorMessage = output.errorMessage,
            attemptNumber = output.attemptNumber,
            attemptId = output.attemptId,
            batchId = output.batchId,
            expectedSha256 = output.expectedSha256,
            observedSha256 = output.observedSha256,
            verificationLevel = output.verificationLevel,
            providerDocumentId = output.providerDocumentId,
            providerCapabilitiesJson = output.providerCapabilitiesJson,
            legacyAmbiguous = batch?.legacyAmbiguous ?: true,
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private inline fun <reified T : Enum<T>> strictEnum(value: String?, field: String): T {
        val raw = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing $field")
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown persisted $field")
    }

    private inline fun <T> blocking(crossinline block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private companion object {
        const val PROVIDER_OBJECT_RESOURCE = "PROVIDER_OBJECT"
        const val MAX_PROVIDER_CLEANUP_RETRIES = 10
    }
}
