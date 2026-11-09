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
                database.publicationOutputDao().deleteForJob(jobId)
                database.publicationBatchDao().deleteForJob(jobId)
                database.cleanupTaskDao().deleteForJob(jobId)
            }
        }
    }

    override fun clearAll() {
        blocking {
            database.withTransaction {
                for (job in database.jobDao().all()) {
                    database.publicationOutputDao().deleteForJob(job.id)
                    database.publicationBatchDao().deleteForJob(job.id)
                }
            }
        }
    }

    override fun entriesForJob(jobId: String): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().forJob(jobId) }
        return entities.map { toEntry(it) }
    }

    override fun unreconciledEntries(): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().unreconciled() }
        return entities.map { toEntry(it) }
    }

    override fun allEntries(): List<PublicationEntry> {
        val entities = blocking { database.publicationOutputDao().all() }
        return entities.map { toEntry(it) }
    }

    override fun loadWithStatus(): PublicationJournalLoadResult {
        val result = runCatching { allEntries() }
            .fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        PublicationJournalLoadResult(emptyList(), PublicationJournalLoadState.EMPTY)
                    } else {
                        PublicationJournalLoadResult(entries, PublicationJournalLoadState.OK)
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
                val entry = toEntry(output)
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
                    legacyAmbiguous = false,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                ),
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

    private fun toEntry(output: PublicationOutputEntity): PublicationEntry {
        return PublicationEntry(
            transactionId = output.transactionId,
            jobId = output.jobId,
            outputIndex = output.outputIndex,
            totalOutputs = output.totalOutputs,
            intendedFilename = output.finalFilename,
            pendingFilename = output.pendingFilename,
            stagingPath = output.stagingKey.orEmpty(),
            expectedSizeBytes = output.expectedSizeBytes,
            providerType = runCatching { PublicationProviderType.valueOf(output.providerType) }
                .getOrDefault(PublicationProviderType.MEDIA_STORE),
            destinationIdentity = output.destinationIdentity,
            publicationPhase = runCatching { PublicationPhase.valueOf(output.phase) }
                .getOrDefault(PublicationPhase.INTENT_RECORDED),
            providerUri = output.providerUri,
            copiedBytes = output.copiedBytes,
            status = runCatching { PublicationTransactionStatus.valueOf(output.status) }
                .getOrDefault(PublicationTransactionStatus.FAILED),
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
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private inline fun <T> blocking(crossinline block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private companion object {
        const val PROVIDER_OBJECT_RESOURCE = "PROVIDER_OBJECT"
        const val MAX_PROVIDER_CLEANUP_RETRIES = 10
    }
}
