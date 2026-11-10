package com.mvrk.vrka.storage

import androidx.room.withTransaction
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.JobStore
import com.mvrk.vrka.JobStoreLoadState
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournalLoadState
import com.mvrk.vrka.PublicationJournal
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationTransactionStatus
import com.mvrk.vrka.PendingStagingCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

internal data class MigrationReport(
    val profile: String,
    val legacySourcesPresent: Boolean,
    val importedJobs: Int,
    val importedTransactions: Int,
    val importedCleanupTasks: Int,
    val preservedRecords: Int,
    val detail: String = "",
)

internal class LegacyStorageMigrator(
    private val filesDir: File,
    private val database: VrkaDatabase,
    private val repository: RoomStorageRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val jobsFile = File(filesDir, "download_jobs.json")
    private val journalFile = File(filesDir, "publication_journal.json")
    private val cleanupFile = File(filesDir, "staging_cleanup.json")
    private val snapshotRoot = File(filesDir, "storage_migration/snapshots")
    private val profileKey = "legacy_profile"

    suspend fun migrateIfNeeded(): MigrationReport = withContext(Dispatchers.IO) {
        val sources = sourceFiles()
        val profile = detectProfile()
        if (sources.isEmpty()) {
            recordProfile(profile, legacyPresent = false, detail = "No legacy storage files present")
            return@withContext MigrationReport(profile, false, 0, 0, 0, 0, "fresh install")
        }
        recordProfile(profile, legacyPresent = true, detail = "Detected legacy storage profile")
        var importedJobs = 0
        var importedTransactions = 0
        var importedCleanup = 0
        var preserved = 0

        if (jobsFile.isFile) {
            val result = importJobs()
            importedJobs += result.first
            preserved += result.second
        } else {
            recordAbsent("download_jobs.json", profile)
        }
        if (journalFile.isFile) {
            val result = importJournal()
            importedTransactions += result.first
            preserved += result.second
        } else {
            recordAbsent("publication_journal.json", profile)
        }
        if (cleanupFile.isFile) {
            val result = importCleanup()
            importedCleanup += result.first
            preserved += result.second
        } else {
            recordAbsent("staging_cleanup.json", profile)
        }
        for (copy in recoveryCopies()) {
            snapshot(copy)
            val key = copy.name
            database.migrationDao().upsertLedger(
                MigrationLedgerEntity(
                    sourceKey = key,
                    sourceTag = profile,
                    sha256 = sha256(copy.readBytes()),
                    status = "PRESERVED_RECOVERY_COPY",
                    totalRecords = 0,
                    importedRecords = 0,
                    detail = "Bounded legacy recovery copy preserved; not imported as authority",
                    updatedAt = clock(),
                ),
            )
        }
        val integrity = pragmaCheck()
        if (!integrity) error("Database integrity check failed after legacy import")
        MigrationReport(
            profile = profile,
            legacySourcesPresent = true,
            importedJobs = importedJobs,
            importedTransactions = importedTransactions,
            importedCleanupTasks = importedCleanup,
            preservedRecords = preserved,
        )
    }

    private fun sourceFiles(): List<File> = listOf(jobsFile, journalFile, cleanupFile).filter { it.isFile }

    private fun detectProfile(): String {
        val jobsText = jobsFile.takeIf { it.isFile }?.readText()?.trim()?.removePrefix("\uFEFF")
        val hasJournal = journalFile.isFile
        val hasCleanup = cleanupFile.isFile
        return when {
            hasCleanup -> "unreleased-baseline"
            hasJournal -> "v5.1.2"
            jobsText == null -> "fresh"
            jobsText.startsWith("[") -> "v5.1.0"
            else -> "v5.1.1"
        }
    }

    private suspend fun recordProfile(profile: String, legacyPresent: Boolean, detail: String) {
        val existing = database.migrationDao().ledger(profileKey)
        val status = if (existing == null) {
            if (legacyPresent) "PROFILE_DETECTED" else "FRESH_INSTALL"
        } else {
            existing.status
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = profileKey,
                sourceTag = profile,
                sha256 = sha256(profile.toByteArray()),
                status = status,
                totalRecords = 0,
                importedRecords = 0,
                detail = detail,
                updatedAt = clock(),
            ),
        )
    }

    private fun recordAbsent(name: String, profile: String) {
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = name,
                sourceTag = profile,
                sha256 = sha256("absent:$name".toByteArray()),
                status = "ABSENT",
                totalRecords = 0,
                importedRecords = 0,
                detail = "Source file absent for this installation",
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun importJobs(): Pair<Int, Int> {
        val key = jobsFile.name
        val bytes = jobsFile.readBytes()
        val hash = snapshot(jobsFile)
        val existing = database.migrationDao().ledger(key)
        if (existing != null && existing.sha256 != hash) {
            error("Legacy JobStore changed after migration snapshot")
        }
        if (existing != null && existing.status in COMPLETE_STATUSES) {
            return existing.importedRecords to 0
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = "IMPORTING",
                totalRecords = 0,
                importedRecords = 0,
                detail = null,
                updatedAt = clock(),
            ),
        )
        val result = JobStore(jobsFile).loadWithStatus(recoverActive = true)
        val rawText = bytes.toString(Charsets.UTF_8)
        var preserved = 0
        val jobs = result.jobs.map { job ->
            val hasJournal = journalFile.isFile
            val legacyVerification = when {
                job.state == JobState.DONE -> "LEGACY_DONE_NO_HASH"
                job.state.isTerminal -> "LEGACY_TERMINAL"
                job.publishStarted && !hasJournal -> "RECOVERY_REQUIRED"
                else -> "LEGACY_ACTIVE"
            }
            val recoveryRequired = job.publishStarted && !hasJournal
            job.copy(
                legacyVerification = legacyVerification,
                recoveryRequired = recoveryRequired,
            )
        }
        if (result.state !in setOf(
                JobStoreLoadState.MISSING,
                JobStoreLoadState.OK,
                JobStoreLoadState.EMPTY,
            ) || result.skippedRecords > 0
        ) {
            database.migrationDao().insertRecord(
                MigrationRecordEntity(
                    sourceKey = key,
                    sourceRow = 0,
                    reason = "JobStore state ${result.state.name}; original preserved",
                    rawJson = rawText,
                    createdAt = clock(),
                ),
            )
            preserved = 1
        }
        jobs.chunked(250).forEach { chunk ->
            val saved = repository.saveJobs(chunk, emptySet())
            check(saved.success) { "Failed to import legacy jobs: ${saved.error}" }
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = if (preserved > 0) "IMPORTED_WITH_PRESERVED_RECORDS" else "VERIFIED",
                totalRecords = jobs.size,
                importedRecords = jobs.size,
                detail = "state=${result.state.name} skipped=${result.skippedRecords}",
                updatedAt = clock(),
            ),
        )
        return jobs.size to preserved
    }

    private suspend fun importJournal(): Pair<Int, Int> {
        val key = journalFile.name
        val bytes = journalFile.readBytes()
        val hash = snapshot(journalFile)
        val existing = database.migrationDao().ledger(key)
        if (existing != null && existing.sha256 != hash) {
            error("Legacy publication journal changed after migration snapshot")
        }
        if (existing != null && existing.status in COMPLETE_STATUSES) {
            return existing.importedRecords to 0
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = "IMPORTING",
                totalRecords = 0,
                importedRecords = 0,
                detail = null,
                updatedAt = clock(),
            ),
        )
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null) {
            database.migrationDao().insertRecord(
                MigrationRecordEntity(
                    sourceKey = key,
                    sourceRow = 0,
                    reason = "Journal root is not a JSON object",
                    rawJson = text.take(1_000_000),
                    createdAt = clock(),
                ),
            )
            database.migrationDao().upsertLedger(
                MigrationLedgerEntity(
                    sourceKey = key,
                    sourceTag = detectProfile(),
                    sha256 = hash,
                    status = "PRESERVED_CORRUPT",
                    totalRecords = 0,
                    importedRecords = 0,
                    detail = "Unparseable journal preserved",
                    updatedAt = clock(),
                ),
            )
            return 0 to 1
        }
        val version = (root.opt("schemaVersion") as? Number)?.toInt()
        if (version != null && version != 1) {
            database.migrationDao().insertRecord(
                MigrationRecordEntity(
                    sourceKey = key,
                    sourceRow = 0,
                    reason = "Unsupported legacy journal schemaVersion $version",
                    rawJson = text.take(1_000_000),
                    createdAt = clock(),
                ),
            )
            database.migrationDao().upsertLedger(
                MigrationLedgerEntity(
                    sourceKey = key,
                    sourceTag = detectProfile(),
                    sha256 = hash,
                    status = "PRESERVED_UNSUPPORTED",
                    totalRecords = 0,
                    importedRecords = 0,
                    detail = "Unsupported journal schema preserved",
                    updatedAt = clock(),
                ),
            )
            return 0 to 1
        }
        val array = root.optJSONArray("entries")
        if (array == null) {
            database.migrationDao().insertRecord(
                MigrationRecordEntity(
                    sourceKey = key,
                    sourceRow = 0,
                    reason = "Journal has no entries array",
                    rawJson = text.take(1_000_000),
                    createdAt = clock(),
                ),
            )
            database.migrationDao().upsertLedger(
                MigrationLedgerEntity(
                    sourceKey = key,
                    sourceTag = detectProfile(),
                    sha256 = hash,
                    status = "PRESERVED_CORRUPT",
                    totalRecords = 0,
                    importedRecords = 0,
                    detail = "Journal has no entries array",
                    updatedAt = clock(),
                ),
            )
            return 0 to 1
        }

        val parsed = mutableListOf<PublicationEntry>()
        var preserved = 0
        val seenTransactions = HashSet<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                addRecord(key, index, "Journal entry is not an object", "null")
                preserved++
                continue
            }
            val transactionId = obj.optString("transactionId")
            val jobId = obj.optString("jobId")
            val intended = obj.optString("intendedFilename")
            if (transactionId.isBlank() || jobId.isBlank() || intended.isBlank()) {
                addRecord(key, index, "Missing transaction/job/filename identity", obj.toString())
                preserved++
                continue
            }
            if (!seenTransactions.add(transactionId)) {
                addRecord(key, index, "Duplicate transaction identity", obj.toString())
                preserved++
                continue
            }
            val total = obj.optInt("totalOutputs", 1)
            val outputIndex = obj.optInt("outputIndex", 0)
            if (total < 1 || outputIndex !in 0 until total) {
                addRecord(key, index, "Invalid output index/total", obj.toString())
                preserved++
                continue
            }
            val phase = PublicationPhase.values().firstOrNull {
                it.name == obj.optString("publicationPhase")
            }
            val status = PublicationTransactionStatus.values().firstOrNull {
                it.name == obj.optString("status")
            }
            val providerType = PublicationProviderType.values().firstOrNull {
                it.name == obj.optString("providerType")
            }
            if (phase == null || status == null || providerType == null) {
                addRecord(
                    key,
                    index,
                    "Unknown or missing legacy publication enum; original row preserved",
                    obj.toString(),
                )
                preserved++
                continue
            }
            val pending = obj.optString("pendingFilename").takeIf { it.isNotBlank() }
                ?: ".vrka_pending_${transactionId}_$intended"
            val destination = obj.optString("destinationIdentity").takeIf { it.isNotBlank() }
                ?: if (providerType == PublicationProviderType.SAF) "" else "Downloads/VRKA"
            val verification = when (status) {
                PublicationTransactionStatus.RECONCILED -> "LEGACY_RECONCILED_TOMBSTONE"
                PublicationTransactionStatus.COMPLETED -> "LEGACY_UNVERIFIED"
                else -> "LEGACY_UNVERIFIED"
            }
            val rawAttemptNumber = obj.optInt("attemptNumber", 0)
                .takeIf { it in 1..1_000_000 }
            // The source journal is legacy evidence, not a trusted owner. Storage gets synthetic
            // identities only; the raw row (including any supplied IDs) remains in the ledger.
            val legacyAmbiguous = true
            addRecord(
                key,
                index,
                "Original legacy journal row preserved as unverified evidence",
                obj.toString(),
            )
            parsed += PublicationEntry(
                transactionId = transactionId,
                jobId = jobId,
                outputIndex = outputIndex,
                totalOutputs = total,
                intendedFilename = intended,
                pendingFilename = pending,
                stagingPath = obj.optString("stagingPath"),
                expectedSizeBytes = obj.optLong("expectedSizeBytes", 0L).coerceAtLeast(0L),
                providerType = providerType,
                destinationIdentity = destination,
                publicationPhase = phase,
                providerUri = obj.optString("providerUri").takeIf { it.isNotBlank() && it != "null" },
                copiedBytes = obj.optLong("copiedBytes", 0L).coerceAtLeast(0L),
                status = status,
                createdAt = obj.optLong("createdAt", clock()).takeIf { it > 0 } ?: clock(),
                updatedAt = obj.optLong("updatedAt", clock()).takeIf { it > 0 } ?: clock(),
                errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
                attemptNumber = rawAttemptNumber ?: 1,
                attemptId = "",
                batchId = "",
                verificationLevel = verification,
                expectedSha256 = null,
                legacyAmbiguous = legacyAmbiguous,
            )
        }

        val jobsById = repository.cachedJobs().associateBy { it.id }
        val missingJobIds = parsed.map { it.jobId }.distinct().filterNot { jobsById.containsKey(it) }
        if (missingJobIds.isNotEmpty()) {
            val placeholders = missingJobIds.map { jobId ->
                DownloadJob(
                    id = jobId,
                    request = DownloadRequest(url = ""),
                    state = JobState.POSTPROCESSING,
                    title = "Recovered publication",
                    detail = "Legacy publication transaction requires recovery",
                    publishStarted = true,
                    recoveryRequired = true,
                    legacyVerification = "RECOVERY_REQUIRED",
                )
            }
            val saved = repository.saveJobs(placeholders, emptySet())
            check(saved.success) { "Failed to create legacy publication placeholders" }
        }
        val refreshedJobs = repository.cachedJobs().associateBy { it.id }
        var imported = 0
        for ((jobId, entries) in parsed.groupBy { it.jobId }) {
            val jobAttempt = refreshedJobs[jobId]?.attempt ?: 1
            val attemptId = JobJsonCodec.attemptIdFor(jobId, jobAttempt)
            val byTotal = entries.groupBy { it.totalOutputs }
            for ((total, group) in byTotal) {
                val sorted = group.sortedBy { it.outputIndex }
                val indices = sorted.map { it.outputIndex }
                val duplicateIndex = indices.size != indices.toSet().size
                val sameTotalAttempts = byTotal[total]?.size != null && byTotal.size > 1
                val batchId = "legacy-batch:$jobId:$total:" + sha256(sorted.joinToString("|") { it.transactionId }.toByteArray()).take(20)
                val enriched = sorted.map {
                    it.copy(
                        attemptNumber = jobAttempt,
                        attemptId = attemptId,
                        batchId = batchId,
                        verificationLevel = it.verificationLevel,
                    )
                }
                repository.publicationStore.recordBatchIntents(enriched)
                if (enriched.any { it.legacyAmbiguous } ||
                    duplicateIndex ||
                    sameTotalAttempts ||
                    byTotal.size > 1
                ) {
                    val batch = database.publicationBatchDao().byId(batchId)
                    if (batch != null) {
                        database.publicationBatchDao().upsert(batch.copy(legacyAmbiguous = true))
                    }
                }
                imported += enriched.size
            }
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = if (preserved > 0) "IMPORTED_WITH_PRESERVED_RECORDS" else "VERIFIED",
                totalRecords = array.length(),
                importedRecords = array.length(),
                detail = "imported=$imported preserved=$preserved",
                updatedAt = clock(),
            ),
        )
        return imported to preserved
    }

    private suspend fun importCleanup(): Pair<Int, Int> {
        val key = cleanupFile.name
        val bytes = cleanupFile.readBytes()
        val hash = snapshot(cleanupFile)
        val existing = database.migrationDao().ledger(key)
        if (existing != null && existing.sha256 != hash) {
            error("Legacy cleanup store changed after migration snapshot")
        }
        if (existing != null && existing.status in COMPLETE_STATUSES) {
            return existing.importedRecords to 0
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = "IMPORTING",
                totalRecords = 0,
                importedRecords = 0,
                detail = null,
                updatedAt = clock(),
            ),
        )
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        val array = root?.optJSONArray("entries")
        if (array == null) {
            database.migrationDao().insertRecord(
                MigrationRecordEntity(
                    sourceKey = key,
                    sourceRow = 0,
                    reason = "Cleanup store is not a supported JSON object",
                    rawJson = bytes.toString(Charsets.UTF_8).take(1_000_000),
                    createdAt = clock(),
                ),
            )
            database.migrationDao().upsertLedger(
                MigrationLedgerEntity(
                    sourceKey = key,
                    sourceTag = detectProfile(),
                    sha256 = hash,
                    status = "PRESERVED_CORRUPT",
                    totalRecords = 0,
                    importedRecords = 0,
                    detail = "Cleanup store preserved",
                    updatedAt = clock(),
                ),
            )
            return 0 to 1
        }
        var imported = 0
        var preserved = 0
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            val jobId = obj?.optString("jobId")
            if (obj == null || jobId.isNullOrBlank()) {
                addRecord(key, index, "Invalid cleanup job identity", obj?.toString() ?: "null")
                preserved++
                continue
            }
            val now = clock()
            database.cleanupTaskDao().upsert(
                CleanupTaskEntity(
                    id = "legacy-staging:$jobId",
                    jobId = jobId,
                    attemptId = null,
                    transactionId = null,
                    resourceType = "STAGING",
                    resourceIdentity = jobId,
                    stage = "LEGACY_STAGING_CLEANUP",
                    retryCount = obj.optInt("attempts", 0),
                    nextRetryAt = now,
                    lastErrorClass = "LEGACY_CLEANUP",
                    lastError = "Imported from legacy staging cleanup store",
                    deadLetter = false,
                    createdAt = obj.optLong("firstSeenAt", now).takeIf { it > 0 } ?: now,
                    updatedAt = obj.optLong("updatedAt", now).takeIf { it > 0 } ?: now,
                ),
            )
            imported++
        }
        database.migrationDao().upsertLedger(
            MigrationLedgerEntity(
                sourceKey = key,
                sourceTag = detectProfile(),
                sha256 = hash,
                status = if (preserved > 0) "IMPORTED_WITH_PRESERVED_RECORDS" else "VERIFIED",
                totalRecords = array.length(),
                importedRecords = imported,
                detail = "imported=$imported preserved=$preserved",
                updatedAt = clock(),
            ),
        )
        return imported to preserved
    }

    private fun addRecord(sourceKey: String, row: Int, reason: String, raw: String) {
        database.migrationDao().insertRecord(
            MigrationRecordEntity(
                sourceKey = sourceKey,
                sourceRow = row,
                reason = reason,
                rawJson = raw.take(1_000_000),
                createdAt = clock(),
            ),
        )
    }

    private fun recoveryCopies(): List<File> {
        val list = filesDir.listFiles() ?: return emptyList()
        return list.filter {
            it.isFile && (
                it.name.startsWith("download_jobs.corrupt-") ||
                    it.name.startsWith("publication_journal.corrupt-")
                )
        }
    }

    private fun snapshot(file: File): String {
        val relative = file.name
        val target = File(snapshotRoot, relative).apply {
            parentFile?.mkdirs()
            if (!exists()) {
                writeBytes(file.readBytes())
            }
        }
        return sha256(target.readBytes())
    }

    private fun pragmaCheck(): Boolean {
        val db = database.openHelper.writableDatabase
        val integrity = db.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val fkViolations = db.query("PRAGMA foreign_key_check").use { cursor -> cursor.count }
        return integrity == "ok" && fkViolations == 0
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val COMPLETE_STATUSES = setOf(
            "VERIFIED",
            "IMPORTED_WITH_PRESERVED_RECORDS",
            "PRESERVED_CORRUPT",
            "PRESERVED_UNSUPPORTED",
            "VERIFIED_EMPTY",
            "PRESERVED_RECOVERY_COPY",
            "ABSENT",
        )
    }
}
