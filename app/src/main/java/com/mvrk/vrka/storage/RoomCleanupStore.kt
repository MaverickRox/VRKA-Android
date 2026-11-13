package com.mvrk.vrka.storage

import androidx.room.withTransaction
import com.mvrk.vrka.PendingStagingCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

internal class RoomCleanupStore(
    private val database: VrkaDatabase,
    private val clock: () -> Long,
) : CleanupStore {
    override fun pending(): List<PendingStagingCleanup> {
        val tasks = runBlocking(Dispatchers.IO) { database.cleanupTaskDao().all() }
        return tasks.filter { it.resourceType == RESOURCE_TYPE && it.jobId != null && !it.deadLetter }.map { task ->
            PendingStagingCleanup(
                jobId = task.jobId!!,
                attempts = task.retryCount,
                firstSeenAt = task.createdAt,
                updatedAt = task.updatedAt,
                deadLetter = task.deadLetter,
            )
        }
    }

    override fun markPending(jobId: String) {
        if (jobId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val existing = database.cleanupTaskDao().byResource(RESOURCE_TYPE, jobId)
                val retries = (existing?.retryCount ?: 0) + 1
                val now = clock()
                database.cleanupTaskDao().upsert(
                    CleanupTaskEntity(
                        id = existing?.id ?: "staging:$jobId:${UUID.randomUUID()}",
                        jobId = jobId,
                        attemptId = existing?.attemptId,
                        transactionId = null,
                        resourceType = RESOURCE_TYPE,
                        resourceIdentity = jobId,
                        stage = "STAGING_CLEANUP_PENDING",
                        retryCount = retries,
                        nextRetryAt = now + backoffMillis(retries),
                        lastErrorClass = "STAGING_DELETE_FAILED",
                        lastError = existing?.lastError,
                        deadLetter = retries >= MAX_RETRIES,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override fun clear(jobId: String) {
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.cleanupTaskDao().deleteByResource(RESOURCE_TYPE, jobId)
            }
        }
    }

    override fun deadLetters(): List<CleanupTaskSummary> =
        runBlocking(Dispatchers.IO) {
            database.cleanupTaskDao().deadLetters().map { task ->
                CleanupTaskSummary(
                    taskId = task.id,
                    jobId = task.jobId,
                    attemptId = task.attemptId,
                    transactionId = task.transactionId,
                    resourceType = task.resourceType,
                    resourceIdentity = task.resourceIdentity,
                    stage = task.stage,
                    retryCount = task.retryCount,
                    nextRetryAt = task.nextRetryAt,
                    lastErrorClass = task.lastErrorClass,
                    lastError = task.lastError?.let { com.mvrk.vrka.DiagnosticStore.sanitizeText(it) },
                    deadLetter = task.deadLetter,
                )
            }
        }

    override fun resetDeadLetter(taskId: String): Boolean = runBlocking(Dispatchers.IO) {
        database.withTransaction {
            val task = database.cleanupTaskDao().byId(taskId) ?: return@withTransaction false
            if (!task.deadLetter) return@withTransaction true
            val now = clock()
            database.cleanupTaskDao().upsert(
                task.copy(
                    deadLetter = false,
                    stage = "MANUAL_RETRY_REQUESTED",
                    nextRetryAt = now,
                    updatedAt = now,
                ),
            )
            true
        }
    }

    override fun isProviderCleanupDeadLetter(transactionId: String): Boolean =
        runBlocking(Dispatchers.IO) {
            database.cleanupTaskDao().byResource(PROVIDER_RESOURCE_TYPE, transactionId)?.deadLetter == true
        }

    override fun pendingHistoryDeletions(): List<CleanupTaskSummary> =
        runBlocking(Dispatchers.IO) {
            database.cleanupTaskDao().all()
                .filter { it.resourceType == HISTORY_DELETE_RESOURCE_TYPE && !it.deadLetter }
                .map { task ->
                    CleanupTaskSummary(
                        taskId = task.id,
                        jobId = task.jobId,
                        attemptId = task.attemptId,
                        transactionId = task.transactionId,
                        resourceType = task.resourceType,
                        resourceIdentity = task.resourceIdentity,
                        stage = task.stage,
                        retryCount = task.retryCount,
                        nextRetryAt = task.nextRetryAt,
                        lastErrorClass = task.lastErrorClass,
                        lastError = task.lastError?.let { com.mvrk.vrka.DiagnosticStore.sanitizeText(it) },
                        deadLetter = task.deadLetter,
                    )
                }
        }

    override fun markHistoryDeletionPending(
        jobId: String,
        uriText: String,
        error: String,
        recordFailure: Boolean,
    ) {
        if (jobId.isBlank() || uriText.isBlank()) return
        val identity = historyDeleteIdentity(jobId, uriText)
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val existing = database.cleanupTaskDao().byResource(HISTORY_DELETE_RESOURCE_TYPE, identity)
                val now = clock()
                val retries = if (recordFailure) {
                    (existing?.retryCount ?: 0) + 1
                } else {
                    existing?.retryCount ?: 0
                }
                val deadLetter = retries >= MAX_RETRIES
                database.cleanupTaskDao().upsert(
                    CleanupTaskEntity(
                        id = existing?.id ?: "history-del:${UUID.randomUUID()}",
                        jobId = jobId,
                        attemptId = existing?.attemptId,
                        transactionId = null,
                        resourceType = HISTORY_DELETE_RESOURCE_TYPE,
                        resourceIdentity = identity,
                        stage = if (recordFailure) "HISTORY_DELETE_PENDING" else "HISTORY_DELETE_INTENT",
                        retryCount = retries,
                        nextRetryAt = if (recordFailure) now + backoffMillis(retries) else now,
                        lastErrorClass = if (recordFailure) "HISTORY_FILE_DELETE_FAILED" else existing?.lastErrorClass,
                        lastError = if (recordFailure) {
                            com.mvrk.vrka.DiagnosticStore.sanitizeText(error).take(300)
                        } else {
                            existing?.lastError
                        },
                        deadLetter = deadLetter,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override fun clearHistoryDeletion(jobId: String, uriText: String) {
        if (jobId.isBlank() || uriText.isBlank()) return
        val identity = historyDeleteIdentity(jobId, uriText)
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.cleanupTaskDao().deleteByResource(HISTORY_DELETE_RESOURCE_TYPE, identity)
            }
        }
    }

    override fun clearHistoryDeletionsForJob(jobId: String) {
        if (jobId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.cleanupTaskDao().forJob(jobId)
                    .filter { it.resourceType == HISTORY_DELETE_RESOURCE_TYPE }
                    .forEach { database.cleanupTaskDao().deleteByResource(it.resourceType, it.resourceIdentity) }
            }
        }
    }

    private fun backoffMillis(retries: Int): Long =
        min(3600L, 2.0.pow(retries.coerceAtMost(12)).toLong()) * 1000L

    private companion object {
        const val RESOURCE_TYPE = "STAGING"
        const val PROVIDER_RESOURCE_TYPE = "PROVIDER_OBJECT"
        const val HISTORY_DELETE_RESOURCE_TYPE = "HISTORY_DELETE"
        const val MAX_RETRIES = 10

        fun historyDeleteIdentity(jobId: String, uriText: String): String = "$jobId|$uriText"
    }
}
