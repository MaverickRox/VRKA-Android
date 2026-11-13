package com.mvrk.vrka.storage

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

private const val TERMINAL_STATES = "('DONE','FAILED','CANCELLED')"

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY created_at DESC")
    fun all(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE state NOT IN $TERMINAL_STATES ORDER BY created_at DESC")
    fun active(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE state IN $TERMINAL_STATES ORDER BY updated_at DESC")
    fun terminal(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE state IN $TERMINAL_STATES ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    fun historyPage(limit: Int, offset: Int): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE state IN $TERMINAL_STATES ORDER BY updated_at DESC")
    fun historyPagingSource(): PagingSource<Int, JobEntity>

    @Query("SELECT COUNT(*) FROM jobs WHERE state IN $TERMINAL_STATES")
    fun terminalCount(): Int

    @Query("SELECT id FROM jobs WHERE state IN $TERMINAL_STATES")
    fun terminalIds(): List<String>

    @Query("SELECT COUNT(*) FROM jobs WHERE state NOT IN $TERMINAL_STATES")
    fun activeCount(): Int

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun byId(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE playlist_group_id = :groupId ORDER BY playlist_item_index, created_at")
    fun forPlaylistGroup(groupId: String): List<JobEntity>

    @Query("SELECT COALESCE(MAX(commit_sequence), 0) + 1 FROM jobs")
    fun nextCommitSequence(): Long

    @Upsert
    fun upsert(job: JobEntity)

    @Query("DELETE FROM jobs WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM jobs")
    fun clear()
}

@Dao
interface AttemptDao {
    @Query("SELECT * FROM attempts WHERE job_id = :jobId ORDER BY attempt_number DESC")
    fun forJob(jobId: String): List<AttemptEntity>

    @Query("SELECT * FROM attempts WHERE attempt_id = :attemptId")
    fun byId(attemptId: String): AttemptEntity?

    @Upsert
    fun upsert(attempt: AttemptEntity)

    @Query("DELETE FROM attempts WHERE job_id = :jobId")
    fun deleteForJob(jobId: String)
}

@Dao
interface PublicationBatchDao {
    @Query("SELECT * FROM publication_batches WHERE job_id = :jobId ORDER BY created_at")
    fun forJob(jobId: String): List<PublicationBatchEntity>

    @Query("SELECT * FROM publication_batches WHERE batch_id = :batchId")
    fun byId(batchId: String): PublicationBatchEntity?

    @Upsert
    fun upsert(batch: PublicationBatchEntity)

    @Query("DELETE FROM publication_batches WHERE job_id = :jobId")
    fun deleteForJob(jobId: String)
}

@Dao
interface PublicationOutputDao {
    @Query("SELECT * FROM publication_outputs ORDER BY created_at")
    fun all(): List<PublicationOutputEntity>

    @Query("SELECT * FROM publication_outputs WHERE job_id = :jobId ORDER BY output_index")
    fun forJob(jobId: String): List<PublicationOutputEntity>

    @Query("SELECT * FROM publication_outputs WHERE batch_id = :batchId ORDER BY output_index")
    fun forBatch(batchId: String): List<PublicationOutputEntity>

    @Query("SELECT * FROM publication_outputs WHERE transaction_id = :transactionId")
    fun byId(transactionId: String): PublicationOutputEntity?

    @Query("SELECT * FROM publication_outputs WHERE status != 'RECONCILED' ORDER BY created_at")
    fun unreconciled(): List<PublicationOutputEntity>

    @Query("SELECT * FROM publication_outputs WHERE job_id = :jobId AND status != 'RECONCILED' ORDER BY output_index")
    fun unreconciledForJob(jobId: String): List<PublicationOutputEntity>

    @Query("SELECT * FROM publication_outputs WHERE job_id = :jobId AND batch_id = :batchId AND status NOT IN ('RECONCILED','CLEANUP_PENDING') ORDER BY output_index")
    fun activeForBatch(jobId: String, batchId: String): List<PublicationOutputEntity>

    @Upsert
    fun upsert(output: PublicationOutputEntity)

    @Query("DELETE FROM publication_outputs WHERE job_id = :jobId")
    fun deleteForJob(jobId: String)
}

@Dao
interface CleanupTaskDao {
    @Query("SELECT * FROM cleanup_tasks ORDER BY next_retry_at, created_at")
    fun all(): List<CleanupTaskEntity>

    @Query("SELECT * FROM cleanup_tasks WHERE resource_type = :type AND resource_identity = :identity")
    fun byResource(type: String, identity: String): CleanupTaskEntity?

    @Query("SELECT * FROM cleanup_tasks WHERE id = :id")
    fun byId(id: String): CleanupTaskEntity?

    @Query("SELECT * FROM cleanup_tasks WHERE job_id = :jobId ORDER BY created_at")
    fun forJob(jobId: String): List<CleanupTaskEntity>

    @Query("SELECT * FROM cleanup_tasks WHERE dead_letter = 1 ORDER BY updated_at DESC")
    fun deadLetters(): List<CleanupTaskEntity>

    @Upsert
    fun upsert(task: CleanupTaskEntity)

    @Query("DELETE FROM cleanup_tasks WHERE resource_type = :type AND resource_identity = :identity")
    fun deleteByResource(type: String, identity: String)

    @Query("DELETE FROM cleanup_tasks WHERE job_id = :jobId")
    fun deleteForJob(jobId: String)
}

@Dao
interface MigrationDao {
    @Query("SELECT * FROM migration_ledger WHERE source_key = :sourceKey")
    fun ledger(sourceKey: String): MigrationLedgerEntity?

    @Query("SELECT * FROM migration_ledger ORDER BY source_key")
    fun allLedger(): List<MigrationLedgerEntity>

    @Upsert
    fun upsertLedger(entry: MigrationLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRecord(record: MigrationRecordEntity): Long

    @Query("DELETE FROM migration_records WHERE source_key = :sourceKey")
    fun deleteRecords(sourceKey: String)

    @Query("SELECT * FROM migration_records ORDER BY id")
    fun records(): List<MigrationRecordEntity>
}
