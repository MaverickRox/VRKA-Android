package com.mvrk.vrka.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jobs",
    indices = [
        Index("state"),
        Index("updated_at"),
        Index("recovery_required"),
    ],
)
data class JobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_json") val requestJson: String,
    val state: String,
    val title: String,
    val detail: String,
    val progress: Float,
    val speed: String,
    @ColumnInfo(name = "eta_seconds") val etaSeconds: Long?,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long?,
    @ColumnInfo(name = "estimated_total_bytes") val estimatedTotalBytes: Long?,
    @ColumnInfo(name = "estimated_size_accuracy") val estimatedSizeAccuracy: String,
    @ColumnInfo(name = "reported_total_bytes") val reportedTotalBytes: Long?,
    @ColumnInfo(name = "reported_total_is_exact") val reportedTotalIsExact: Boolean,
    @ColumnInfo(name = "active_stream") val activeStream: String?,
    @ColumnInfo(name = "playlist_item_index") val playlistItemIndex: Int?,
    @ColumnInfo(name = "playlist_item_count") val playlistItemCount: Int?,
    @ColumnInfo(name = "actual_final_file_bytes") val actualFinalFileBytes: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "output_uris_json") val outputUrisJson: String,
    val error: String,
    val attempt: Int,
    @ColumnInfo(name = "actual_resolution") val actualResolution: String?,
    @ColumnInfo(name = "runtime_name") val runtimeName: String,
    @ColumnInfo(name = "runtime_version") val runtimeVersion: String,
    @ColumnInfo(name = "subtitle_status") val subtitleStatus: String,
    @ColumnInfo(name = "publish_started") val publishStarted: Boolean,
    @ColumnInfo(name = "current_attempt_id") val currentAttemptId: String,
    @ColumnInfo(name = "legacy_verification") val legacyVerification: String,
    @ColumnInfo(name = "recovery_required") val recoveryRequired: Boolean,
    @ColumnInfo(name = "terminal_at") val terminalAt: Long?,
    @ColumnInfo(name = "commit_sequence") val commitSequence: Long,
    val version: Long,
)

@Entity(
    tableName = "attempts",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("job_id"),
        Index(value = ["job_id", "attempt_number"], unique = true),
    ],
)
data class AttemptEntity(
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "request_json") val requestJson: String,
    @ColumnInfo(name = "destination_tree_uri") val destinationTreeUri: String?,
    val status: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "legacy_trust") val legacyTrust: String,
    val version: Long,
)

@Entity(
    tableName = "publication_batches",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("job_id"), Index("attempt_id")],
)
data class PublicationBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "total_outputs") val totalOutputs: Int,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "destination_identity") val destinationIdentity: String,
    val status: String,
    @ColumnInfo(name = "legacy_ambiguous") val legacyAmbiguous: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "publication_outputs",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PublicationBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("job_id"),
        Index("attempt_id"),
        Index("batch_id"),
        Index(value = ["batch_id", "output_index"], unique = true),
        Index("provider_uri"),
        Index("status"),
    ],
)
data class PublicationOutputEntity(
    @PrimaryKey @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "output_index") val outputIndex: Int,
    @ColumnInfo(name = "total_outputs") val totalOutputs: Int,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "destination_identity") val destinationIdentity: String,
    @ColumnInfo(name = "pending_filename") val pendingFilename: String,
    @ColumnInfo(name = "final_filename") val finalFilename: String,
    @ColumnInfo(name = "provider_uri") val providerUri: String?,
    @ColumnInfo(name = "provider_document_id") val providerDocumentId: String?,
    @ColumnInfo(name = "staging_key") val stagingKey: String?,
    @ColumnInfo(name = "expected_size_bytes") val expectedSizeBytes: Long,
    @ColumnInfo(name = "expected_sha256") val expectedSha256: String?,
    @ColumnInfo(name = "observed_sha256") val observedSha256: String?,
    @ColumnInfo(name = "copied_bytes") val copiedBytes: Long,
    @ColumnInfo(name = "verification_level") val verificationLevel: String,
    @ColumnInfo(name = "provider_capabilities_json") val providerCapabilitiesJson: String?,
    val phase: String,
    val status: String,
    @ColumnInfo(name = "cleanup_state") val cleanupState: String,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Long,
)

@Entity(
    tableName = "cleanup_tasks",
    indices = [
        Index("job_id"),
        Index("next_retry_at"),
        Index("dead_letter"),
        Index(value = ["resource_type", "resource_identity"], unique = true),
    ],
)
data class CleanupTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "job_id") val jobId: String?,
    @ColumnInfo(name = "attempt_id") val attemptId: String?,
    @ColumnInfo(name = "transaction_id") val transactionId: String?,
    @ColumnInfo(name = "resource_type") val resourceType: String,
    @ColumnInfo(name = "resource_identity") val resourceIdentity: String,
    val stage: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long,
    @ColumnInfo(name = "last_error_class") val lastErrorClass: String?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "dead_letter") val deadLetter: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "migration_ledger")
data class MigrationLedgerEntity(
    @PrimaryKey @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_tag") val sourceTag: String,
    val sha256: String,
    val status: String,
    @ColumnInfo(name = "total_records") val totalRecords: Int,
    @ColumnInfo(name = "imported_records") val importedRecords: Int,
    val detail: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "migration_records",
    indices = [
        Index("source_key"),
        Index(value = ["source_key", "source_row", "reason"], unique = true),
    ],
)
data class MigrationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_row") val sourceRow: Int,
    val reason: String,
    @ColumnInfo(name = "raw_json") val rawJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
