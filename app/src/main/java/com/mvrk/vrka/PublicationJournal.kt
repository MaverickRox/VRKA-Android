package com.mvrk.vrka

import android.net.Uri
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
    CLEANUP_PENDING,
    RECONCILED,
}

internal enum class PublicationJournalLoadState {
    MISSING,
    OK,
    EMPTY,
    CORRUPT,
    UNSUPPORTED_SCHEMA,
    READ_FAILED,
}

internal data class PublicationJournalLoadResult(
    val entries: List<PublicationEntry>,
    val state: PublicationJournalLoadState,
    val recoveryCopy: File? = null,
    val detail: String = "",
    /**
     * True only when invalid records were attributable to distinct jobs and every remaining
     * entry passed full validation. Destructive recovery may then proceed for those jobs.
     */
    val isolated: Boolean = false,
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
            // A failed cleanup must stay cleanup-pending: retiring it as FAILED would let a
            // broad reconciliation pass drop the retry obligation.
            if (it.status == PublicationTransactionStatus.CLEANUP_PENDING) {
                it.copy(errorMessage = error, updatedAt = System.currentTimeMillis())
            } else {
                it.copy(
                    status = PublicationTransactionStatus.FAILED,
                    errorMessage = error,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun markCleanupPending(transactionId: String, error: String? = null): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                status = PublicationTransactionStatus.CLEANUP_PENDING,
                errorMessage = error ?: it.errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateDeleted(transactionId: String): Unit = synchronized(lock) {
        mutateEntry(transactionId) {
            it.copy(
                status = PublicationTransactionStatus.INTERRUPTED,
                providerUri = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Retires exactly the supplied publication transactions. Job-scoped reconciliation is
     * deliberately not supported: a retry reuses the logical job ID, so a job-wide sweep
     * could silently retire failed transactions belonging to an earlier attempt.
     */
    fun markReconciled(transactionIds: Collection<String>): Unit = synchronized(lock) {
        if (transactionIds.isEmpty()) return
        val targets = transactionIds.toSet()
        val entries = loadEntries().toMutableList()
        var changed = false
        for (i in entries.indices) {
            if (entries[i].transactionId !in targets) continue
            if (entries[i].status == PublicationTransactionStatus.CLEANUP_PENDING) continue
            entries[i] = entries[i].copy(
                status = PublicationTransactionStatus.RECONCILED,
                publicationPhase = if (entries[i].publicationPhase == PublicationPhase.FINALIZED) {
                    PublicationPhase.RECORDED
                } else {
                    entries[i].publicationPhase
                },
                updatedAt = System.currentTimeMillis(),
            )
            changed = true
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
        val schemaVersion = when (val raw = root.opt("schemaVersion")) {
            null, JSONObject.NULL -> null
            is Number -> raw.toInt()
            else -> -1
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            val res = preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    emptyList(),
                    PublicationJournalLoadState.UNSUPPORTED_SCHEMA,
                    detail = "Unsupported journal schema version: ${schemaVersion ?: "missing"}",
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
        val decoded = decodeEntries(array)
        val res = when {
            decoded.failClosed -> preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    emptyList(),
                    PublicationJournalLoadState.CORRUPT,
                    detail = decoded.detail,
                ),
            )
            decoded.invalidRecords > 0 -> preserveAndReport(
                text,
                PublicationJournalLoadResult(
                    entries = decoded.entries,
                    state = PublicationJournalLoadState.CORRUPT,
                    detail = decoded.detail,
                    isolated = decoded.entries.isNotEmpty(),
                ),
            )
            else -> PublicationJournalLoadResult(decoded.entries, PublicationJournalLoadState.OK)
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
            .put("schemaVersion", CURRENT_SCHEMA_VERSION)
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

    private data class DecodeOutcome(
        val entries: List<PublicationEntry>,
        val invalidRecords: Int = 0,
        val failClosed: Boolean = false,
        val detail: String = "",
    )

    private sealed interface RecordResult {
        data class Valid(val entry: PublicationEntry) : RecordResult
        data class Invalid(
            val jobId: String?,
            val transactionId: String?,
            val reason: String,
        ) : RecordResult
    }

    private fun decodeEntries(array: JSONArray): DecodeOutcome {
        val valid = mutableListOf<PublicationEntry>()
        val invalid = mutableListOf<RecordResult.Invalid>()
        var failClosed = false
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                invalid += RecordResult.Invalid(null, null, "record $index is not an object")
                failClosed = true
                continue
            }
            when (val result = decodeRecord(obj)) {
                is RecordResult.Valid -> valid += result.entry
                is RecordResult.Invalid -> {
                    invalid += result
                    if (result.jobId == null) failClosed = true
                }
            }
        }

        val validTransactions = valid.mapTo(HashSet()) { it.transactionId }
        if (validTransactions.size != valid.size) failClosed = true
        for (record in invalid) {
            if (record.transactionId != null && record.transactionId in validTransactions) {
                failClosed = true
            }
        }

        val invalidJobs = invalid.mapNotNullTo(HashSet()) { it.jobId }
        val validByJob = valid.groupBy { it.jobId }
        val droppedJobs = HashSet<String>()
        for ((jobId, entries) in validByJob) {
            if (jobId in invalidJobs || !isCompleteBatch(entries)) droppedJobs += jobId
        }
        val retained = valid.filter { it.jobId !in droppedJobs }
        val invalidCount = invalid.size + valid.count { it.jobId in droppedJobs }
        if (failClosed || (invalidCount > 0 && retained.isEmpty())) {
            return DecodeOutcome(
                entries = emptyList(),
                invalidRecords = invalidCount,
                failClosed = true,
                detail = "Journal contains records that cannot be isolated safely; recovery is disabled",
            )
        }
        return if (invalidCount > 0) {
            DecodeOutcome(
                entries = retained,
                invalidRecords = invalidCount,
                detail = "Quarantined $invalidCount invalid record(s); ${retained.map { it.jobId }.toSet().size} valid job(s) isolated",
            )
        } else {
            DecodeOutcome(valid)
        }
    }

    /**
     * A logical job can be retried, so the journal legitimately holds several transactions
     * for the same output index. The newest transaction per index is the current attempt;
     * older ones are cleanup/reconciliation candidates and must not invalidate the batch.
     */
    private fun isCompleteBatch(entries: List<PublicationEntry>): Boolean {
        if (entries.isEmpty()) return false
        val newestPerIndex = entries.groupBy { it.outputIndex }.mapValues { (_, group) ->
            group.maxWithOrNull(
                compareBy<PublicationEntry> { it.createdAt }
                    .thenBy { it.updatedAt }
                    .thenBy { it.transactionId },
            )!!
        }
        val totals = newestPerIndex.values.map { it.totalOutputs }.toSet()
        if (totals.size != 1) return false
        val total = totals.single()
        if (total != newestPerIndex.size) return false
        if (newestPerIndex.keys.sorted() != (0 until total).toList()) return false
        val current = newestPerIndex.values
        if (current.any { it.providerType != current.first().providerType }) return false
        if (current.any { it.destinationIdentity != current.first().destinationIdentity }) return false
        return true
    }

    private fun decodeRecord(obj: JSONObject): RecordResult {
        val rawTransactionId = stringField(obj, "transactionId")
        val transactionId = rawTransactionId?.takeIf { isSafeIdentifier(it, MAX_TRANSACTION_ID_CHARS) }
        val rawJobId = stringField(obj, "jobId")
        val jobId = rawJobId?.takeIf { isSafeIdentifier(it, MAX_JOB_ID_CHARS) }

        fun invalid(reason: String) = RecordResult.Invalid(jobId, transactionId, reason)

        if (transactionId == null) return invalid("missing or invalid transactionId")
        if (jobId == null) return invalid("missing or invalid jobId")

        val outputIndex = number(obj, "outputIndex")?.toInt() ?: return invalid("missing outputIndex")
        val totalOutputs = number(obj, "totalOutputs")?.toInt() ?: return invalid("missing totalOutputs")
        if (totalOutputs !in 1..MAX_OUTPUTS) return invalid("totalOutputs out of range")
        if (outputIndex !in 0 until totalOutputs) return invalid("outputIndex out of range")

        val intendedFilename = stringField(obj, "intendedFilename")
            ?.takeIf(::isSafeFilename)
            ?: return invalid("missing or unsafe intendedFilename")
        val pendingFilename = stringField(obj, "pendingFilename")
            ?: return invalid("missing pendingFilename")
        if (pendingFilename != pendingNameFor(transactionId, intendedFilename)) {
            return invalid("pendingFilename does not belong to this transaction")
        }
        val stagingPath = stringField(obj, "stagingPath")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PATH_CHARS && runCatching { File(it).isAbsolute }.getOrDefault(false) }
            ?: return invalid("missing or non-absolute stagingPath")
        val expectedSizeBytes = number(obj, "expectedSizeBytes")?.toLong()
            ?: return invalid("missing expectedSizeBytes")
        if (expectedSizeBytes < 0L) return invalid("negative expectedSizeBytes")

        val providerType = PublicationProviderType.values()
            .firstOrNull { it.name == stringField(obj, "providerType") }
            ?: return invalid("invalid providerType")
        val destinationIdentity = stringField(obj, "destinationIdentity")
            ?: return invalid("missing destinationIdentity")
        when (providerType) {
            PublicationProviderType.MEDIA_STORE -> {
                if (destinationIdentity != PublicationUriRules.MEDIA_STORE_DESTINATION) {
                    return invalid("invalid MediaStore destination identity")
                }
            }
            PublicationProviderType.SAF -> {
                if (!isTreeUri(destinationIdentity)) return invalid("invalid SAF tree destination")
            }
        }

        val publicationPhase = PublicationPhase.values()
            .firstOrNull { it.name == stringField(obj, "publicationPhase") }
            ?: return invalid("invalid publicationPhase")
        val status = PublicationTransactionStatus.values()
            .firstOrNull { it.name == stringField(obj, "status") }
            ?: return invalid("invalid status")

        val providerUri = stringField(obj, "providerUri")
        if (providerUri != null) {
            val structurallyValid = when (providerType) {
                PublicationProviderType.MEDIA_STORE -> isMediaStoreUri(providerUri)
                PublicationProviderType.SAF -> isDocumentUnderTree(providerUri, destinationIdentity)
            }
            if (!structurallyValid) return invalid("providerUri does not belong to the declared provider/destination")
        }

        val copiedBytes = number(obj, "copiedBytes")?.toLong() ?: return invalid("missing copiedBytes")
        if (copiedBytes < 0L) return invalid("negative copiedBytes")

        val now = System.currentTimeMillis()
        val createdAt = number(obj, "createdAt")?.toLong() ?: return invalid("missing createdAt")
        val updatedAt = number(obj, "updatedAt")?.toLong() ?: return invalid("missing updatedAt")
        if (createdAt <= 0L || updatedAt <= 0L) return invalid("non-positive timestamp")
        if (createdAt > now + MAX_CLOCK_SKEW_MS || updatedAt > now + MAX_CLOCK_SKEW_MS) {
            return invalid("timestamp in the future")
        }
        if (updatedAt < createdAt) return invalid("updatedAt precedes createdAt")

        when (publicationPhase) {
            PublicationPhase.INTENT_RECORDED -> {
                if (providerUri != null) return invalid("intent record has a provider URI")
                if (copiedBytes != 0L) return invalid("intent record has copied bytes")
                if (status != PublicationTransactionStatus.PENDING &&
                    status != PublicationTransactionStatus.FAILED &&
                    status != PublicationTransactionStatus.INTERRUPTED &&
                    status != PublicationTransactionStatus.CLEANUP_PENDING
                ) {
                    return invalid("intent record has an inconsistent status")
                }
            }
            PublicationPhase.PROVIDER_OBJECT_CREATED -> {
                if (copiedBytes != 0L) return invalid("provider-created record has copied bytes")
            }
            PublicationPhase.DATA_COPIED -> {
                if (copiedBytes > expectedSizeBytes) return invalid("copied bytes exceed the expected size")
            }
            PublicationPhase.OUTPUT_VALIDATED,
            PublicationPhase.FINALIZED,
            PublicationPhase.RECORDED,
            -> {
                if (copiedBytes != expectedSizeBytes) {
                    return invalid("validated record byte count does not match the expected size")
                }
            }
        }

        val pendingProviderObject = status == PublicationTransactionStatus.CLEANUP_PENDING
        val uriRequired = publicationPhase != PublicationPhase.INTENT_RECORDED &&
            status != PublicationTransactionStatus.INTERRUPTED &&
            status != PublicationTransactionStatus.RECONCILED
        if (uriRequired && providerUri == null) return invalid("missing providerUri for ${status.name}")
        if (pendingProviderObject && providerUri == null) {
            return invalid("cleanup-pending record has no provider object to clean up")
        }
        when (status) {
            PublicationTransactionStatus.PENDING -> {
                if (publicationPhase != PublicationPhase.INTENT_RECORDED) {
                    return invalid("pending record has advanced phase")
                }
            }
            PublicationTransactionStatus.IN_PROGRESS -> {
                if (publicationPhase == PublicationPhase.INTENT_RECORDED ||
                    publicationPhase == PublicationPhase.FINALIZED ||
                    publicationPhase == PublicationPhase.RECORDED
                ) {
                    return invalid("in-progress record has inconsistent phase")
                }
            }
            PublicationTransactionStatus.COMPLETED -> {
                if (publicationPhase != PublicationPhase.FINALIZED &&
                    publicationPhase != PublicationPhase.RECORDED
                ) {
                    return invalid("completed record is not finalized")
                }
            }
            PublicationTransactionStatus.RECONCILED -> Unit
            PublicationTransactionStatus.FAILED,
            PublicationTransactionStatus.INTERRUPTED,
            PublicationTransactionStatus.CLEANUP_PENDING,
            -> Unit
        }
        if (publicationPhase == PublicationPhase.RECORDED &&
            status != PublicationTransactionStatus.COMPLETED &&
            status != PublicationTransactionStatus.RECONCILED
        ) {
            return invalid("recorded phase has inconsistent status")
        }

        val errorMessage = stringField(obj, "errorMessage")?.take(MAX_DETAIL_CHARS)
        return RecordResult.Valid(
            PublicationEntry(
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
            ),
        )
    }

    private fun number(obj: JSONObject, name: String): Number? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return obj.opt(name) as? Number
    }

    private fun stringField(obj: JSONObject, name: String): String? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return obj.opt(name) as? String
    }

    private fun isSafeIdentifier(value: String, maxChars: Int): Boolean =
        value.isNotBlank() &&
            value.length <= maxChars &&
            value.none { it.isWhitespace() || it.isISOControl() }

    private fun isSafeFilename(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_FILENAME_CHARS &&
            value != "." &&
            value != ".." &&
            !value.startsWith(".") &&
            value.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }

    private fun isTreeUri(value: String): Boolean =
        runCatching { PublicationUriRules.isTreeUri(Uri.parse(value)) }.getOrDefault(false)

    private fun isDocumentUnderTree(document: String, tree: String): Boolean = runCatching {
        PublicationUriRules.isDocumentUnderTree(Uri.parse(document), Uri.parse(tree))
    }.getOrDefault(false)

    private fun isMediaStoreUri(value: String): Boolean =
        runCatching { PublicationUriRules.isMediaStoreUri(Uri.parse(value)) }.getOrDefault(false)

    private fun pendingNameFor(transactionId: String, intendedFilename: String): String =
        PublicationUriRules.pendingNameFor(transactionId, intendedFilename)

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_JOURNAL_ENTRIES = 200
        private const val MAX_RECOVERY_COPIES = 3
        private const val MAX_DETAIL_CHARS = 300
        private const val MAX_OUTPUTS = 500
        private const val MAX_FILENAME_CHARS = 255
        private const val MAX_TRANSACTION_ID_CHARS = 128
        private const val MAX_JOB_ID_CHARS = 256
        private const val MAX_PATH_CHARS = 4096
        private const val MAX_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L
    }
}
