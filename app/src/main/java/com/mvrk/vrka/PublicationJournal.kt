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

internal enum class PublicationJournalLoadState {
    MISSING,
    OK,
    EMPTY,
    CORRUPT,
    READ_FAILED,
}

internal data class PublicationJournalLoadResult(
    val entries: List<PublicationEntry>,
    val state: PublicationJournalLoadState,
    val recoveryCopy: File? = null,
    val detail: String = "",
)

internal data class PublicationEntry(
    val transactionId: String,
    val jobId: String,
    val outputIndex: Int,
    val totalOutputs: Int,
    val intendedFilename: String,
    val pendingFilename: String = ".vrka_pending_${transactionId}_$intendedFilename",
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

    @Volatile
    var lastLoadResult: PublicationJournalLoadResult? = null
        private set

    fun recordIntent(entry: PublicationEntry): Unit = synchronized(lock) {
        recordBatchIntents(listOf(entry))
    }

    fun recordBatchIntents(batch: List<PublicationEntry>): Unit = synchronized(lock) {
        val entries = loadEntries().toMutableList()
        val updatedBatch = batch.map { entry ->
            entry.copy(
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                status = PublicationTransactionStatus.PENDING,
                updatedAt = System.currentTimeMillis(),
            )
        }
        for (item in updatedBatch) {
            val idx = entries.indexOfFirst { it.transactionId == item.transactionId }
            if (idx >= 0) entries[idx] = item else entries.add(item)
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

    fun updateFinalized(transactionId: String, newUri: String? = null): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                publicationPhase = PublicationPhase.FINALIZED,
                status = PublicationTransactionStatus.COMPLETED,
                providerUri = newUri ?: it.providerUri,
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
            if (entries[i].jobId == jobId) {
                entries[i] = entries[i].copy(
                    status = PublicationTransactionStatus.RECONCILED,
                    publicationPhase = if (entries[i].status == PublicationTransactionStatus.COMPLETED ||
                        entries[i].publicationPhase == PublicationPhase.FINALIZED
                    ) PublicationPhase.RECORDED else entries[i].publicationPhase,
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

    fun loadWithStatus(): PublicationJournalLoadResult = synchronized(lock) {
        if (!target.isFile) {
            val res = PublicationJournalLoadResult(emptyList(), PublicationJournalLoadState.MISSING)
            lastLoadResult = res
            return res
        }
        val text = try {
            ops.readText(target)
        } catch (error: Throwable) {
            val res = PublicationJournalLoadResult(
                emptyList(),
                PublicationJournalLoadState.READ_FAILED,
                detail = error.message.orEmpty().take(MAX_DETAIL_CHARS),
            )
            lastLoadResult = res
            return res
        }
        if (text.isBlank()) {
            val res = PublicationJournalLoadResult(emptyList(), PublicationJournalLoadState.EMPTY)
            lastLoadResult = res
            return res
        }
        val trimmed = text.trim().removePrefix("\uFEFF")
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
        if (root == null) {
            val res = preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    emptyList(),
                    PublicationJournalLoadState.CORRUPT,
                    detail = "JSON could not be parsed as a root object",
                ),
            )
            lastLoadResult = res
            return res
        }
        val array = root.optJSONArray("entries")
        if (array == null) {
            val res = preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    emptyList(),
                    PublicationJournalLoadState.CORRUPT,
                    detail = "JSON root has no entries array",
                ),
            )
            lastLoadResult = res
            return res
        }
        val result = mutableListOf<PublicationEntry>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skipped++
                continue
            }
            val decoded = decode(obj)
            if (decoded != null) {
                result.add(decoded)
            } else {
                skipped++
            }
        }
        val state = if (skipped > 0) PublicationJournalLoadState.CORRUPT else PublicationJournalLoadState.OK
        val res = if (skipped > 0) {
            preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    result,
                    state,
                    detail = "skipped $skipped invalid entry record(s)",
                ),
            )
        } else {
            PublicationJournalLoadResult(result, PublicationJournalLoadState.OK)
        }
        lastLoadResult = res
        return res
    }

    private fun loadEntries(): List<PublicationEntry> {
        return loadWithStatus().entries
    }

    private fun mutateEntry(transactionId: String, transform: (PublicationEntry) -> PublicationEntry) {
        val entries = loadEntries().toMutableList()
        val index = entries.indexOfFirst { it.transactionId == transactionId }
        if (index >= 0) {
            entries[index] = transform(entries[index])
            saveEntries(entries)
        }
    }

    private fun preserveAndReport(
        originalText: String,
        result: PublicationJournalLoadResult,
    ): PublicationJournalLoadResult {
        val copy = runCatching { writeRecoveryCopy(originalText) }.getOrNull()
        return result.copy(recoveryCopy = copy)
    }

    private fun writeRecoveryCopy(originalText: String): File? {
        val parent = target.parentFile ?: return null
        if (!parent.isDirectory) parent.mkdirs()
        pruneRecoveryCopies(parent)
        val base = target.name.substringBeforeLast('.', target.name)
        var stamp = System.currentTimeMillis()
        var copy = File(parent, "$base.corrupt-$stamp.json")
        while (copy.exists()) {
            stamp++
            copy = File(parent, "$base.corrupt-$stamp.json")
        }
        return runCatching {
            ops.writeText(copy, originalText)
            copy
        }.getOrNull()
    }

    private fun pruneRecoveryCopies(parent: File) {
        val base = target.name.substringBeforeLast('.', target.name)
        val copies = (parent.listFiles { file -> file.isFile && file.name.startsWith("$base.corrupt-") } ?: emptyArray())
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .toMutableList()
        while (copies.size >= MAX_RECOVERY_COPIES) {
            copies.removeAt(0).delete()
        }
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
        .put("pendingFilename", entry.pendingFilename)
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
        val pendingFilename = obj.optString("pendingFilename").ifBlank { ".vrka_pending_${transactionId}_$intendedFilename" }
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
            pendingFilename = pendingFilename,
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
        private const val MAX_RECOVERY_COPIES = 3
        private const val MAX_DETAIL_CHARS = 300
    }
}
