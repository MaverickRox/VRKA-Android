package com.mvrk.vrka

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class PublicationProviderType {
    MEDIA_STORE,
    SAF,
}

internal enum class PublicationPhase {
    INTENT_RECORDED,
    PROVIDER_OBJECT_CREATED,
    DATA_COPIED,
    OUTPUT_VALIDATED,
    FINALIZED,
    RECORDED,
}

internal enum class PublicationTransactionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    RECONCILED,
}

internal data class PublicationEntry(
    val transactionId: String,
    val jobId: String,
    val outputIndex: Int,
    val totalOutputs: Int,
    val intendedFilename: String,
    val stagingPath: String,
    val expectedSizeBytes: Long,
    val providerType: PublicationProviderType,
    val destinationIdentity: String,
    val publicationPhase: PublicationPhase,
    val providerUri: String? = null,
    val copiedBytes: Long = 0L,
    val status: PublicationTransactionStatus = PublicationTransactionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
)

/**
 * Durable per-output publication journal.
 *
 * Records the intent, pending provider object, byte copy progress, validation, and final
 * publication state before and during storage export. If the process dies between ContentResolver
 * insertion/copy and terminal JobStore update, this journal allows the restart reconciliation
 * logic to recover completed outputs into History or clean up partial/interrupted files safely.
 */
internal class PublicationJournal(
    private val target: File,
    private val ops: JobStoreFileOps = DefaultJobStoreFileOps,
) {
    private val lock = Any()

    fun recordIntent(entry: PublicationEntry): Unit = synchronized(lock) {
        val entries = loadEntries().toMutableList()
        val index = entries.indexOfFirst { it.transactionId == entry.transactionId }
        val updated = entry.copy(
            publicationPhase = PublicationPhase.INTENT_RECORDED,
            status = PublicationTransactionStatus.PENDING,
            updatedAt = System.currentTimeMillis(),
        )
        if (index >= 0) {
            entries[index] = updated
        } else {
            entries.add(updated)
        }
        saveEntries(entries)
    }

    fun updateProviderCreated(transactionId: String, providerUri: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                providerUri = providerUri,
                publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                status = PublicationTransactionStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateDataCopied(transactionId: String, copiedBytes: Long): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                copiedBytes = copiedBytes,
                publicationPhase = PublicationPhase.DATA_COPIED,
                status = PublicationTransactionStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateValidated(transactionId: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                publicationPhase = PublicationPhase.OUTPUT_VALIDATED,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateFinalized(transactionId: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                publicationPhase = PublicationPhase.FINALIZED,
                status = PublicationTransactionStatus.COMPLETED,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateRecorded(transactionId: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                publicationPhase = PublicationPhase.RECORDED,
                status = PublicationTransactionStatus.COMPLETED,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun markInterrupted(transactionId: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                status = PublicationTransactionStatus.INTERRUPTED,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun markFailed(transactionId: String, error: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                status = PublicationTransactionStatus.FAILED,
                errorMessage = error,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun markReconciled(jobId: String): Unit = synchronized(lock) {
        val entries = loadEntries().toMutableList()
        var changed = false
        for (i in entries.indices) {
            if (entries[i].jobId == jobId &&
                (entries[i].status == PublicationTransactionStatus.COMPLETED ||
                    entries[i].status == PublicationTransactionStatus.RECONCILED ||
                    entries[i].publicationPhase == PublicationPhase.FINALIZED ||
                    entries[i].publicationPhase == PublicationPhase.RECORDED)
            ) {
                entries[i] = entries[i].copy(
                    status = PublicationTransactionStatus.RECONCILED,
                    publicationPhase = PublicationPhase.RECORDED,
                    updatedAt = System.currentTimeMillis(),
                )
                changed = true
            }
        }
        if (changed) saveEntries(entries)
    }

    fun clearJob(jobId: String): Unit = synchronized(lock) {
        val entries = loadEntries().filterNot { it.jobId == jobId }
        saveEntries(entries)
    }

    fun clearAll(): Unit = synchronized(lock) {
        saveEntries(emptyList())
    }

    fun entriesForJob(jobId: String): List<PublicationEntry> = synchronized(lock) {
        loadEntries().filter { it.jobId == jobId }
    }

    fun unreconciledEntries(): List<PublicationEntry> = synchronized(lock) {
        loadEntries().filter { it.status != PublicationTransactionStatus.RECONCILED }
    }

    fun allEntries(): List<PublicationEntry> = synchronized(lock) {
        loadEntries()
    }

    private fun mutateEntry(transactionId: String, transform: (PublicationEntry) -> PublicationEntry) {
        val entries = loadEntries().toMutableList()
        val index = entries.indexOfFirst { it.transactionId == transactionId }
        if (index >= 0) {
            entries[index] = transform(entries[index])
            saveEntries(entries)
        }
    }

    private fun loadEntries(): List<PublicationEntry> {
        if (!target.isFile) return emptyList()
        val text = runCatching { ops.readText(target) }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text.trim().removePrefix("\uFEFF")) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("entries") ?: return emptyList()
        val result = mutableListOf<PublicationEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            decode(obj)?.let { result.add(it) }
        }
        return result
    }

    private fun saveEntries(entries: List<PublicationEntry>) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val bounded = if (entries.size > MAX_JOURNAL_ENTRIES) {
            val unreconciled = entries.filter { it.status != PublicationTransactionStatus.RECONCILED }
            val reconciled = entries.filter { it.status == PublicationTransactionStatus.RECONCILED }
                .sortedByDescending { it.updatedAt }
                .take(MAX_JOURNAL_ENTRIES - unreconciled.size.coerceAtMost(MAX_JOURNAL_ENTRIES))
            (unreconciled + reconciled).sortedBy { it.createdAt }
        } else {
            entries
        }
        val array = JSONArray()
        bounded.forEach { array.put(encode(it)) }
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("entries", array)
            .toString()

        val temporary = ops.createTempFile(parent ?: File("."), "pub_journal")
        try {
            ops.writeText(temporary, payload)
            if (!ops.atomicMove(temporary, target)) {
                ops.replaceMove(temporary, target)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun encode(entry: PublicationEntry): JSONObject = JSONObject()
        .put("transactionId", entry.transactionId)
        .put("jobId", entry.jobId)
        .put("outputIndex", entry.outputIndex)
        .put("totalOutputs", entry.totalOutputs)
        .put("intendedFilename", entry.intendedFilename)
        .put("stagingPath", entry.stagingPath)
        .put("expectedSizeBytes", entry.expectedSizeBytes)
        .put("providerType", entry.providerType.name)
        .put("destinationIdentity", entry.destinationIdentity)
        .put("publicationPhase", entry.publicationPhase.name)
        .put("providerUri", entry.providerUri)
        .put("copiedBytes", entry.copiedBytes)
        .put("status", entry.status.name)
        .put("createdAt", entry.createdAt)
        .put("updatedAt", entry.updatedAt)
        .put("errorMessage", entry.errorMessage)

    private fun decode(obj: JSONObject): PublicationEntry? {
        val transactionId = obj.optString("transactionId").takeIf { it.isNotBlank() } ?: return null
        val jobId = obj.optString("jobId")
        val outputIndex = obj.optInt("outputIndex", 0)
        val totalOutputs = obj.optInt("totalOutputs", 1)
        val intendedFilename = obj.optString("intendedFilename")
        val stagingPath = obj.optString("stagingPath")
        val expectedSizeBytes = obj.optLong("expectedSizeBytes", 0L)
        val providerType = runCatching {
            PublicationProviderType.valueOf(obj.optString("providerType"))
        }.getOrDefault(PublicationProviderType.MEDIA_STORE)
        val destinationIdentity = obj.optString("destinationIdentity")
        val publicationPhase = runCatching {
            PublicationPhase.valueOf(obj.optString("publicationPhase"))
        }.getOrDefault(PublicationPhase.INTENT_RECORDED)
        val providerUri = obj.optString("providerUri").takeIf { it.isNotBlank() && it != "null" }
        val copiedBytes = obj.optLong("copiedBytes", 0L)
        val status = runCatching {
            PublicationTransactionStatus.valueOf(obj.optString("status"))
        }.getOrDefault(PublicationTransactionStatus.PENDING)
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        val errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
        return PublicationEntry(
            transactionId = transactionId,
            jobId = jobId,
            outputIndex = outputIndex,
            totalOutputs = totalOutputs,
            intendedFilename = intendedFilename,
            stagingPath = stagingPath,
            expectedSizeBytes = expectedSizeBytes,
            providerType = providerType,
            destinationIdentity = destinationIdentity,
            publicationPhase = publicationPhase,
            providerUri = providerUri,
            copiedBytes = copiedBytes,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            errorMessage = errorMessage,
        )
    }

    companion object {
        private const val MAX_JOURNAL_ENTRIES = 200
    }
}
