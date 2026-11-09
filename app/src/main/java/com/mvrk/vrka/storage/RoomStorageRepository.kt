package com.mvrk.vrka.storage

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.JobState
import com.mvrk.vrka.JobStoreLoadResult
import com.mvrk.vrka.JobStoreLoadState
import com.mvrk.vrka.JobStoreSaveResult
import com.mvrk.vrka.SizeEstimateKind
import com.mvrk.vrka.subtitle.SubtitleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durable Room-backed repository. The StateFlow is a cache refreshed only after committed
 * database transactions; it is not an independent authority.
 */
internal class RoomStorageRepository(
    private val database: VrkaDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val writeMutex = Mutex()
    val jobPersistence: JobPersistence = RoomJobPersistence(this)
    val publicationStore: PublicationStore = RoomPublicationStore(database, this, clock)
    val cleanupStore: CleanupStore = RoomCleanupStore(database, clock)

    suspend fun loadCache() {
        val decoded = withContext(Dispatchers.IO) {
            database.jobDao().all().mapNotNull { entity ->
                runCatching { JobJsonCodec.decodeJob(entity) }.getOrNull()
            }
        }
        _jobs.value = decoded.sortedByDescending { it.createdAt }
    }

    fun cachedJobs(): List<DownloadJob> = _jobs.value

    suspend fun activeCount(): Int = withContext(Dispatchers.IO) { database.jobDao().activeCount() }
    suspend fun terminalCount(): Int = withContext(Dispatchers.IO) { database.jobDao().terminalCount() }
    suspend fun terminalJobIds(): List<String> = withContext(Dispatchers.IO) { database.jobDao().terminalIds() }

    fun historyPagingSource(): PagingSource<Int, DownloadJob> =
        MappingPagingSource(database.jobDao().historyPagingSource())

    suspend fun saveJobs(jobs: List<DownloadJob>, pinnedJobIds: Set<String>): JobStoreSaveResult =
        writeMutex.withLock {
            if (jobs.mapTo(HashSet()) { it.id }.size != jobs.size) {
                return@withLock JobStoreSaveResult(false, "Duplicate job identities cannot be persisted")
            }
            try {
                val persistedIds = mutableSetOf<String>()
                database.withTransaction {
                    for (job in jobs) {
                        val attemptId = JobJsonCodec.attemptIdFor(job.id, job.attempt)
                        val existing = database.jobDao().byId(job.id)
                        if (existing != null && shouldIgnoreIncoming(existing, job)) continue
                        val commitSequence = when {
                            !job.state.isTerminal -> existing?.commitSequence ?: 0L
                            existing != null && existing.commitSequence > 0L -> existing.commitSequence
                            else -> database.jobDao().nextCommitSequence()
                        }
                        val legacyVerification = job.legacyVerification.ifBlank {
                            existing?.legacyVerification.orEmpty()
                        }
                        val recoveryRequired = job.recoveryRequired ||
                            (existing?.recoveryRequired == true && job.state != JobState.DONE)
                        val entity = JobJsonCodec.encodeJob(
                            job = job,
                            currentAttemptId = attemptId,
                            legacyVerification = legacyVerification,
                            recoveryRequired = recoveryRequired,
                            terminalAt = if (job.state.isTerminal) existing?.terminalAt ?: clock() else null,
                            commitSequence = commitSequence,
                            version = (existing?.version ?: 0L) + 1L,
                        )
                        database.jobDao().upsert(entity)
                        val attemptExisting = database.attemptDao().byId(attemptId)
                        database.attemptDao().upsert(
                            AttemptEntity(
                                attemptId = attemptId,
                                jobId = job.id,
                                attemptNumber = job.attempt,
                                requestJson = entity.requestJson,
                                destinationTreeUri = job.request.destinationTreeUri,
                                status = job.state.name,
                                startedAt = attemptExisting?.startedAt ?: job.createdAt,
                                endedAt = if (job.state.isTerminal) attemptExisting?.endedAt ?: clock() else null,
                                legacyTrust = attemptExisting?.legacyTrust ?: legacyVerification,
                                version = (attemptExisting?.version ?: 0L) + 1L,
                            ),
                        )
                        persistedIds += job.id
                    }
                    for (job in jobs) {
                        if (job.id !in persistedIds) continue
                        val durable = database.jobDao().byId(job.id) ?: error("Missing durable job ${job.id}")
                        if (durable.state != job.state.name || durable.outputUrisJson != JobJsonCodec.encodeJob(
                                job = job,
                                currentAttemptId = JobJsonCodec.attemptIdFor(job.id, job.attempt),
                                legacyVerification = job.legacyVerification,
                                recoveryRequired = job.recoveryRequired,
                                terminalAt = null,
                                commitSequence = 0L,
                                version = 0L,
                            ).outputUrisJson
                        ) {
                            error("Durable job verification failed for ${job.id}")
                        }
                    }
                }
                reloadCache()
                JobStoreSaveResult(true, persistedJobIds = persistedIds)
            } catch (error: Throwable) {
                JobStoreSaveResult(false, error.message.orEmpty().take(300))
            }
        }

    private fun shouldIgnoreIncoming(existing: JobEntity, incoming: DownloadJob): Boolean {
        val existingState = runCatching { JobState.valueOf(existing.state) }.getOrDefault(JobState.FAILED)
        if (incoming.attempt < existing.attempt) return true
        if (incoming.attempt == existing.attempt && existingState.isTerminal && existingState != incoming.state) {
            return true
        }
        if (existingState == JobState.DONE && incoming.state != JobState.DONE) return true
        if (existingState == JobState.DONE && incoming.state == JobState.DONE && incoming.updatedAt < existing.updatedAt) {
            return true
        }
        return false
    }

    suspend fun deleteJobs(jobIds: Collection<String>): Boolean = writeMutex.withLock {
        if (jobIds.isEmpty()) return@withLock true
        val result = database.withTransaction { deleteJobsInTransaction(jobIds) }
        reloadCache()
        result
    }

    private fun deleteJobsInTransaction(jobIds: Collection<String>): Boolean {
        var allDeleted = true
        for (jobId in jobIds.toSet()) {
            val unresolved = database.publicationOutputDao().unreconciledForJob(jobId)
            if (unresolved.isNotEmpty()) {
                allDeleted = false
                continue
            }
            database.publicationOutputDao().deleteForJob(jobId)
            database.publicationBatchDao().deleteForJob(jobId)
            database.attemptDao().deleteForJob(jobId)
            database.cleanupTaskDao().deleteForJob(jobId)
            database.jobDao().delete(jobId)
        }
        return allDeleted
    }

    suspend fun clearTerminal(): Boolean = writeMutex.withLock {
        val result = database.withTransaction {
            deleteJobsInTransaction(database.jobDao().terminalIds())
        }
        reloadCache()
        result
    }

    suspend fun clearAll(): Boolean = writeMutex.withLock {
        val result = database.withTransaction {
            deleteJobsInTransaction(database.jobDao().all().map { it.id })
        }
        reloadCache()
        result
    }

    suspend fun commitTerminalPublishedJob(
        jobId: String,
        attemptNumber: Int,
        batchId: String,
        outputUris: List<String>,
        detail: String,
        downloadedBytes: Long?,
        reportedTotalBytes: Long?,
        reportedTotalIsExact: Boolean,
        actualFinalFileBytes: Long?,
        subtitleStatus: SubtitleStatus?,
    ): Boolean = writeMutex.withLock {
        val committed = try {
            database.withTransaction {
                val job = database.jobDao().byId(jobId) ?: return@withTransaction false
                if (job.attempt != attemptNumber) return@withTransaction false
                if (job.state == JobState.DONE.name) return@withTransaction false

                val attemptId = JobJsonCodec.attemptIdFor(jobId, attemptNumber)
                if (batchId.isBlank()) {
                    if (outputUris.isNotEmpty()) return@withTransaction false
                    val sequence = database.jobDao().nextCommitSequence()
                    database.jobDao().upsert(
                        job.copy(
                            state = JobState.DONE.name,
                            detail = detail,
                            progress = 1f,
                            downloadedBytes = downloadedBytes ?: job.downloadedBytes,
                            reportedTotalBytes = reportedTotalBytes ?: job.reportedTotalBytes,
                            reportedTotalIsExact = reportedTotalIsExact,
                            actualFinalFileBytes = actualFinalFileBytes ?: job.actualFinalFileBytes,
                            outputUrisJson = "[]",
                            error = "",
                            subtitleStatus = (subtitleStatus ?: runCatching {
                                SubtitleStatus.valueOf(job.subtitleStatus)
                            }.getOrDefault(SubtitleStatus.NONE)).name,
                            updatedAt = clock(),
                            terminalAt = clock(),
                            commitSequence = sequence,
                            version = job.version + 1L,
                        ),
                    )
                    return@withTransaction true
                }

                val batch = database.publicationBatchDao().byId(batchId)
                    ?: return@withTransaction false
                if (batch.jobId != jobId || batch.attemptId != attemptId) return@withTransaction false
                val outputs = database.publicationOutputDao().forBatch(batchId)
                if (outputs.size != batch.totalOutputs) return@withTransaction false
                if (outputs.map { it.outputIndex }.sorted() != (0 until batch.totalOutputs).toList()) {
                    return@withTransaction false
                }
                if (outputs.any {
                        it.jobId != jobId ||
                            it.attemptId != attemptId ||
                            it.status !in setOf("COMPLETED", "RECONCILED") ||
                            it.phase !in setOf("FINALIZED", "RECORDED", "PUBLISHED", "COMMITTED_RECONCILED") ||
                            it.providerUri.isNullOrBlank() ||
                            it.verificationLevel !in setOf("HASH_VERIFIED", "SIZE_VERIFIED")
                    }
                ) {
                    return@withTransaction false
                }
                val ordered = outputs.sortedBy { it.outputIndex }.map { it.providerUri!! }
                if (ordered != outputUris) return@withTransaction false

                val sequence = database.jobDao().nextCommitSequence()
                val terminalSubtitle = subtitleStatus ?: runCatching {
                    SubtitleStatus.valueOf(job.subtitleStatus)
                }.getOrDefault(SubtitleStatus.NONE)
                val terminal = job.copy(
                    state = JobState.DONE.name,
                    detail = detail,
                    progress = 1f,
                    downloadedBytes = downloadedBytes ?: job.downloadedBytes,
                    reportedTotalBytes = reportedTotalBytes ?: job.reportedTotalBytes,
                    reportedTotalIsExact = reportedTotalIsExact,
                    actualFinalFileBytes = actualFinalFileBytes ?: job.actualFinalFileBytes,
                    outputUrisJson = org.json.JSONArray(ordered).toString(),
                    error = "",
                    subtitleStatus = terminalSubtitle.name,
                    recoveryRequired = false,
                    updatedAt = clock(),
                    terminalAt = clock(),
                    commitSequence = sequence,
                    version = job.version + 1L,
                )
                database.jobDao().upsert(terminal)
                outputs.forEach { output ->
                    database.publicationOutputDao().upsert(
                        output.copy(
                            status = "RECONCILED",
                            phase = "COMMITTED_RECONCILED",
                            cleanupState = "STAGING_CLEANUP_PENDING",
                            updatedAt = clock(),
                            version = output.version + 1L,
                        ),
                    )
                }
                database.publicationBatchDao().upsert(
                    batch.copy(status = "COMMITTED", updatedAt = clock()),
                )
                database.cleanupTaskDao().upsert(
                    CleanupTaskEntity(
                        id = "staging:$jobId",
                        jobId = jobId,
                        attemptId = attemptId,
                        transactionId = null,
                        resourceType = "STAGING",
                        resourceIdentity = jobId,
                        stage = "STAGING_CLEANUP_PENDING",
                        retryCount = 0,
                        nextRetryAt = clock(),
                        lastErrorClass = null,
                        lastError = null,
                        deadLetter = false,
                        createdAt = clock(),
                        updatedAt = clock(),
                    ),
                )
                true
            }
        } catch (_: Throwable) {
            false
        }
        if (committed) {
            reloadCache()
            val durable = withContext(Dispatchers.IO) { database.jobDao().byId(jobId) }
            if (durable?.state != JobState.DONE.name ||
                runCatching { org.json.JSONArray(durable.outputUrisJson).toString() }.getOrNull() !=
                org.json.JSONArray(outputUris).toString()
            ) {
                return@withLock false
            }
        }
        committed
    }

    private suspend fun reloadCache() {
        val entities = withContext(Dispatchers.IO) { database.jobDao().all() }
        _jobs.value = entities.mapNotNull { runCatching { JobJsonCodec.decodeJob(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    private class MappingPagingSource(
        private val delegate: PagingSource<Int, JobEntity>,
    ) : PagingSource<Int, DownloadJob>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DownloadJob> =
            when (val result = delegate.load(params)) {
                is LoadResult.Page -> LoadResult.Page(
                    data = result.data.mapNotNull { runCatching { JobJsonCodec.decodeJob(it) }.getOrNull() },
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
                is LoadResult.Error -> LoadResult.Error(result.throwable)
                is LoadResult.Invalid -> LoadResult.Invalid()
            }

        override fun getRefreshKey(state: PagingState<Int, DownloadJob>): Int? =
            state.anchorPosition?.let { anchor ->
                state.closestPageToPosition(anchor)?.let { page ->
                    page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
                }
            }
    }
}

private class RoomJobPersistence(
    private val repository: RoomStorageRepository,
) : JobPersistence {
    override val lastLoadResult: JobStoreLoadResult? = null

    override fun load(recoverActive: Boolean): List<DownloadJob> = repository.cachedJobs()

    override fun cachedJobs(): List<DownloadJob> = repository.cachedJobs()

    override fun save(jobs: List<DownloadJob>, pinnedJobIds: Set<String>): JobStoreSaveResult =
        runBlocking(Dispatchers.IO) { repository.saveJobs(jobs, pinnedJobIds) }

    override fun deleteJobs(jobIds: Collection<String>): Boolean =
        runBlocking(Dispatchers.IO) { repository.deleteJobs(jobIds) }

    override fun clearTerminal(): Boolean = runBlocking(Dispatchers.IO) { repository.clearTerminal() }

    override fun clearAll(): Boolean = runBlocking(Dispatchers.IO) { repository.clearAll() }
}
