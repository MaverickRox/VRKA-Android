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

    private fun backoffMillis(retries: Int): Long =
        min(3600L, 2.0.pow(retries.coerceAtMost(12)).toLong()) * 1000L

    private companion object {
        const val RESOURCE_TYPE = "STAGING"
        const val MAX_RETRIES = 10
    }
}
