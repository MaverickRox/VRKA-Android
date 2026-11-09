package com.mvrk.vrka.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.ActiveStream
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationTransactionStatus
import com.mvrk.vrka.SizeEstimateKind
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.subtitle.SubtitleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomStorageRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository

    private fun deleteDb() {
        val base = context.getDatabasePath(VrkaDatabase.NAME)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun buildDb(): VrkaDatabase =
        Room.databaseBuilder(context, VrkaDatabase::class.java, VrkaDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDb()
        database = buildDb()
        repository = RoomStorageRepository(database)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        deleteDb()
    }

    private fun job(
        id: String = "job-1",
        state: JobState = JobState.POSTPROCESSING,
        attempt: Int = 1,
    ) = DownloadJob(
        id = id,
        request = DownloadRequest(
            url = "https://example.com/watch?v=$id",
            mode = com.mvrk.vrka.MediaMode.VIDEO,
            quality = VideoQuality.P1080,
            prefer60Fps = true,
            audioFormat = AudioFormat.OPUS,
            mp3Bitrate = 256,
            isPlaylist = true,
            playlistStart = 2,
            playlistEnd = 7,
            downloadSubtitles = true,
            automaticCaptions = false,
            embedSubtitles = true,
            subtitleLanguages = "en,de",
            embedMetadata = true,
            embedThumbnail = false,
            sponsorBlock = true,
            sponsorCategories = "sponsor,intro",
            trimStart = "00:00:05",
            trimEnd = "00:01:00",
            customArguments = listOf("--no-cache-dir"),
            commandTemplateId = "template-1",
            commandTemplateName = "Fixture",
            commandTemplateArguments = listOf("--limit-rate", "2M"),
            h264CompatibilityMode = true,
            referer = "https://example.com/ref",
            origin = "https://example.com",
            customHeaders = mapOf("X-Trace" to "visible", "Cookie" to "secret"),
            destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        ),
        state = state,
        title = "Fixture title",
        detail = "Fixture detail",
        progress = 42f,
        speed = "1 MB/s",
        etaSeconds = 10L,
        downloadedBytes = 1000L,
        estimatedTotalBytes = 2000L,
        estimatedSizeAccuracy = SizeEstimateKind.APPROXIMATE,
        reportedTotalBytes = 1800L,
        reportedTotalIsExact = false,
        activeStream = ActiveStream.VIDEO,
        playlistItemIndex = 3,
        playlistItemCount = 8,
        actualFinalFileBytes = 1700L,
        createdAt = 100L,
        updatedAt = 200L,
        outputUris = listOf("content://media/external/downloads/1"),
        error = "",
        attempt = attempt,
        actualResolution = "1080p",
        runtimeName = "Stock yt-dlp",
        runtimeVersion = "2026.09.05",
        subtitleStatus = SubtitleStatus.SAVED_SEPARATELY,
        publishStarted = true,
    )

    @Test
    fun fullJobAndRequestFieldsSurviveRoomRoundTrip() = runBlocking {
        val original = job()
        val saved = repository.saveJobs(listOf(original), emptySet())
        assertTrue(saved.success)
        repository.loadCache()
        val loaded = repository.cachedJobs().single()

        assertEquals(original.id, loaded.id)
        assertEquals(original.state, loaded.state)
        assertEquals(original.title, loaded.title)
        assertEquals(original.detail, loaded.detail)
        assertEquals(original.progress, loaded.progress, 0.001f)
        assertEquals(original.etaSeconds, loaded.etaSeconds)
        assertEquals(original.downloadedBytes, loaded.downloadedBytes)
        assertEquals(original.estimatedTotalBytes, loaded.estimatedTotalBytes)
        assertEquals(original.estimatedSizeAccuracy, loaded.estimatedSizeAccuracy)
        assertEquals(original.reportedTotalBytes, loaded.reportedTotalBytes)
        assertEquals(original.reportedTotalIsExact, loaded.reportedTotalIsExact)
        assertEquals(original.activeStream, loaded.activeStream)
        assertEquals(original.playlistItemIndex, loaded.playlistItemIndex)
        assertEquals(original.playlistItemCount, loaded.playlistItemCount)
        assertEquals(original.actualFinalFileBytes, loaded.actualFinalFileBytes)
        assertEquals(original.outputUris, loaded.outputUris)
        assertEquals(original.attempt, loaded.attempt)
        assertEquals(original.actualResolution, loaded.actualResolution)
        assertEquals(original.runtimeName, loaded.runtimeName)
        assertEquals(original.runtimeVersion, loaded.runtimeVersion)
        assertEquals(original.subtitleStatus, loaded.subtitleStatus)
        assertEquals(original.publishStarted, loaded.publishStarted)
        assertEquals(original.request.url, loaded.request.url)
        assertEquals(original.request.mode, loaded.request.mode)
        assertEquals(original.request.quality, loaded.request.quality)
        assertEquals(original.request.prefer60Fps, loaded.request.prefer60Fps)
        assertEquals(original.request.audioFormat, loaded.request.audioFormat)
        assertEquals(original.request.mp3Bitrate, loaded.request.mp3Bitrate)
        assertEquals(original.request.isPlaylist, loaded.request.isPlaylist)
        assertEquals(original.request.playlistStart, loaded.request.playlistStart)
        assertEquals(original.request.playlistEnd, loaded.request.playlistEnd)
        assertEquals(original.request.downloadSubtitles, loaded.request.downloadSubtitles)
        assertEquals(original.request.automaticCaptions, loaded.request.automaticCaptions)
        assertEquals(original.request.embedSubtitles, loaded.request.embedSubtitles)
        assertEquals(original.request.subtitleLanguages, loaded.request.subtitleLanguages)
        assertEquals(original.request.embedMetadata, loaded.request.embedMetadata)
        assertEquals(original.request.embedThumbnail, loaded.request.embedThumbnail)
        assertEquals(original.request.sponsorBlock, loaded.request.sponsorBlock)
        assertEquals(original.request.sponsorCategories, loaded.request.sponsorCategories)
        assertEquals(original.request.trimStart, loaded.request.trimStart)
        assertEquals(original.request.trimEnd, loaded.request.trimEnd)
        assertEquals(original.request.customArguments, loaded.request.customArguments)
        assertEquals(original.request.commandTemplateId, loaded.request.commandTemplateId)
        assertEquals(original.request.commandTemplateName, loaded.request.commandTemplateName)
        assertEquals(original.request.commandTemplateArguments, loaded.request.commandTemplateArguments)
        assertEquals(original.request.h264CompatibilityMode, loaded.request.h264CompatibilityMode)
        assertEquals(original.request.referer, loaded.request.referer)
        assertEquals(original.request.origin, loaded.request.origin)
        assertEquals(original.request.customHeaders["X-Trace"], loaded.request.customHeaders["X-Trace"])
        assertFalse("Sensitive headers must never reappear", loaded.request.customHeaders.containsKey("Cookie"))
        assertEquals(original.request.destinationTreeUri, loaded.request.destinationTreeUri)
    }

    @Test
    fun terminalCommitIsAtomicAndReconcilesExactTransactions() = runBlocking {
        val original = job("job-terminal")
        assertTrue(repository.saveJobs(listOf(original), emptySet()).success)
        val entry = PublicationEntry(
            transactionId = "tx-terminal",
            jobId = original.id,
            outputIndex = 0,
            totalOutputs = 1,
            intendedFilename = "video.mp4",
            pendingFilename = ".vrka_pending_tx-terminal_video.mp4",
            stagingPath = "/staging/job-terminal/video.mp4",
            expectedSizeBytes = 100L,
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.FINALIZED,
            providerUri = "content://media/external/downloads/1",
            copiedBytes = 100L,
            status = PublicationTransactionStatus.COMPLETED,
            attemptNumber = 1,
            attemptId = JobJsonCodec.attemptIdFor(original.id, 1),
            batchId = "batch-terminal",
            verificationLevel = "HASH_VERIFIED",
        )
        repository.publicationStore.recordBatchIntents(listOf(entry))

        val committed = repository.commitTerminalPublishedJob(
            jobId = original.id,
            attemptNumber = 1,
            batchId = "batch-terminal",
            outputUris = listOf("content://media/external/downloads/1"),
            detail = "Saved",
            downloadedBytes = 100L,
            reportedTotalBytes = 100L,
            reportedTotalIsExact = true,
            actualFinalFileBytes = 100L,
            subtitleStatus = SubtitleStatus.NONE,
        )
        assertTrue(committed)
        repository.loadCache()
        val done = repository.cachedJobs().single()
        assertEquals(JobState.DONE, done.state)
        assertEquals(listOf("content://media/external/downloads/1"), done.outputUris)
        assertEquals(PublicationTransactionStatus.RECONCILED, repository.publicationStore.allEntries().single().status)
        assertEquals(1, database.cleanupTaskDao().all().size)

        val wrongAttempt = repository.commitTerminalPublishedJob(
            jobId = original.id,
            attemptNumber = 2,
            batchId = "batch-terminal",
            outputUris = listOf("content://media/external/downloads/1"),
            detail = "Bad",
            downloadedBytes = 100L,
            reportedTotalBytes = 100L,
            reportedTotalIsExact = true,
            actualFinalFileBytes = 100L,
            subtitleStatus = null,
        )
        assertFalse("A stale attempt must not commit", wrongAttempt)
    }

    @Test
    fun terminalCommitRejectsAnUnverifiedOutput() = runBlocking {
        val original = job("job-unverified")
        assertTrue(repository.saveJobs(listOf(original), emptySet()).success)
        repository.publicationStore.recordBatchIntents(
            listOf(
                PublicationEntry(
                    transactionId = "tx-unverified",
                    jobId = original.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    stagingPath = "/staging/job-unverified/video.mp4",
                    expectedSizeBytes = 10L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.FINALIZED,
                    providerUri = "content://media/external/downloads/2",
                    copiedBytes = 10L,
                    status = PublicationTransactionStatus.COMPLETED,
                    attemptNumber = 1,
                    attemptId = JobJsonCodec.attemptIdFor(original.id, 1),
                    batchId = "batch-unverified",
                    verificationLevel = "",
                ),
            ),
        )

        assertFalse(
            "An output without readback evidence must never commit a DONE job",
            repository.commitTerminalPublishedJob(
                jobId = original.id,
                attemptNumber = 1,
                batchId = "batch-unverified",
                outputUris = listOf("content://media/external/downloads/2"),
                detail = "Saved",
                downloadedBytes = 10L,
                reportedTotalBytes = 10L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 10L,
                subtitleStatus = SubtitleStatus.NONE,
            ),
        )
    }

    @Test
    fun historyPagingDeliversEveryTerminalJobInStablePages() = runBlocking {
        val jobs = (0 until 75).map { index ->
            job(
                id = "history-$index",
                state = JobState.DONE,
            ).copy(
                createdAt = 1_000L + index,
                updatedAt = 1_000L + index,
                title = "History $index",
            )
        }
        assertTrue(repository.saveJobs(jobs, emptySet()).success)

        val source = repository.historyPagingSource()
        val first = source.load(
            androidx.paging.PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 30,
                placeholdersEnabled = false,
            ),
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(30, first.data.size)
        assertEquals(30, first.nextKey)

        val second = source.load(
            androidx.paging.PagingSource.LoadParams.Append(
                key = first.nextKey!!,
                loadSize = 30,
                placeholdersEnabled = false,
            ),
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(30, second.data.size)
        assertEquals(60, second.nextKey)

        val third = source.load(
            androidx.paging.PagingSource.LoadParams.Append(
                key = second.nextKey!!,
                loadSize = 30,
                placeholdersEnabled = false,
            ),
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(15, third.data.size)
        assertEquals(null, third.nextKey)

        val allIds = first.data.map { it.id } + second.data.map { it.id } + third.data.map { it.id }
        assertEquals(75, allIds.toSet().size)
        assertEquals("history-74", allIds.first())
    }

    @Test
    fun providerCleanupObligationIsDurableAndClearedByDeletion() = runBlocking {
        val original = job("job-cleanup-task")
        assertTrue(repository.saveJobs(listOf(original), emptySet()).success)
        repository.publicationStore.recordBatchIntents(
            listOf(
                PublicationEntry(
                    transactionId = "tx-cleanup-task",
                    jobId = original.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    stagingPath = "/staging/job-cleanup-task/video.mp4",
                    expectedSizeBytes = 10L,
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                    publicationPhase = PublicationPhase.COPYING,
                    providerUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/video.mp4",
                    copiedBytes = 5L,
                    status = PublicationTransactionStatus.FAILED,
                    attemptNumber = 1,
                    attemptId = JobJsonCodec.attemptIdFor(original.id, 1),
                    batchId = "batch-cleanup-task",
                ),
            ),
        )

        repository.publicationStore.markCleanupPending("tx-cleanup-task", "delete failed")
        val pending = database.cleanupTaskDao().byResource("PROVIDER_OBJECT", "tx-cleanup-task")
        assertNotNull(pending)
        assertFalse(pending!!.deadLetter)

        repository.publicationStore.updateDeleted("tx-cleanup-task")
        assertEquals(null, database.cleanupTaskDao().byResource("PROVIDER_OBJECT", "tx-cleanup-task"))
    }

    @Test
    fun unresolvedTransactionsProtectHistoryDeletion() = runBlocking {        val original = job("job-protected")
        assertTrue(repository.saveJobs(listOf(original), emptySet()).success)
        repository.publicationStore.recordBatchIntents(
            listOf(
                PublicationEntry(
                    transactionId = "tx-protected",
                    jobId = original.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    stagingPath = "/staging/job-protected/video.mp4",
                    expectedSizeBytes = 10L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                    attemptNumber = 1,
                    attemptId = JobJsonCodec.attemptIdFor(original.id, 1),
                    batchId = "batch-protected",
                ),
            ),
        )
        assertFalse(repository.deleteJobs(listOf(original.id)))
        assertNotNull(database.jobDao().byId(original.id))
    }

    @Test
    fun databaseReopenPreservesJobsAndCleanupTasks() = runBlocking {
        val original = job("job-reopen")
        assertTrue(repository.saveJobs(listOf(original.copy(state = JobState.DONE)), emptySet()).success)
        database.cleanupTaskDao().upsert(
            CleanupTaskEntity(
                id = "staging:job-reopen",
                jobId = "job-reopen",
                attemptId = null,
                transactionId = null,
                resourceType = "STAGING",
                resourceIdentity = "job-reopen",
                stage = "STAGING_CLEANUP_PENDING",
                retryCount = 1,
                nextRetryAt = 0L,
                lastErrorClass = "TEST",
                lastError = "test",
                deadLetter = false,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.close()
        database = buildDb()
        repository = RoomStorageRepository(database)
        repository.loadCache()
        assertEquals(JobState.DONE, repository.cachedJobs().single().state)
        assertEquals(1, database.cleanupTaskDao().all().size)
    }
}
