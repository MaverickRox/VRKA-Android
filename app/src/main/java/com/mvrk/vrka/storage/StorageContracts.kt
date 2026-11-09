package com.mvrk.vrka.storage

import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.JobStoreLoadResult
import com.mvrk.vrka.JobStoreSaveResult
import com.mvrk.vrka.PendingStagingCleanup
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournalLoadResult
import com.mvrk.vrka.PublicationPhase

internal interface JobPersistence {
    val lastLoadResult: JobStoreLoadResult?

    fun load(recoverActive: Boolean): List<DownloadJob>

    fun cachedJobs(): List<DownloadJob>? = null

    fun save(jobs: List<DownloadJob>, pinnedJobIds: Set<String> = emptySet()): JobStoreSaveResult

    fun deleteJobs(jobIds: Collection<String>): Boolean

    fun clearTerminal(): Boolean

    fun clearAll(): Boolean
}

internal interface PublicationStore {
    val lastLoadResult: PublicationJournalLoadResult?

    fun recordIntent(entry: PublicationEntry)

    fun recordBatchIntents(batch: List<PublicationEntry>)

    fun updateProviderCreated(transactionId: String, providerUri: String)

    /** Replaces a provider URI without regressing phases that already recorded copied data. */
    fun updateProviderUri(transactionId: String, providerUri: String) = Unit

    fun updateDataCopied(transactionId: String, copiedBytes: Long)

    fun updateValidated(transactionId: String)

    /** Records provider readback evidence; no-op for the legacy JSON journal. */
    fun updateVerification(transactionId: String, verificationLevel: String, observedSha256: String?) = Unit

    fun updateFinalized(transactionId: String, newUri: String? = null)

    fun updateRecorded(transactionId: String)

    fun markInterrupted(transactionId: String)

    fun markFailed(transactionId: String, error: String)

    fun markCleanupPending(transactionId: String, error: String? = null)

    fun updateDeleted(transactionId: String)

    fun markReconciled(transactionIds: Collection<String>)

    fun clearJob(jobId: String)

    fun clearAll()

    fun entriesForJob(jobId: String): List<PublicationEntry>

    fun unreconciledEntries(): List<PublicationEntry>

    fun allEntries(): List<PublicationEntry>

    fun loadWithStatus(): PublicationJournalLoadResult

    /** Optional state-machine hook; legacy JSON journals do not persist these transitional phases. */
    fun updatePhase(transactionId: String, phase: PublicationPhase) = Unit
}

internal interface CleanupStore {
    fun pending(): List<PendingStagingCleanup>

    fun markPending(jobId: String)

    fun clear(jobId: String)
}
