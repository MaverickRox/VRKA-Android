package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvrk.vrka.storage.JobJsonCodec
import com.mvrk.vrka.storage.PublicationStore
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.VrkaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real Android provider integration for the MediaStore publication path (API 29+).
 * SAF tree-provider integration is not covered here because a persisted tree grant requires
 * interactive document-picker consent; that boundary is reported in DEVICE_VALIDATION.md.
 */
@RunWith(AndroidJUnit4::class)
class PublicationStorageIntegrationTest {

    private lateinit var context: Context
    private lateinit var publisher: OutputPublisher
    private lateinit var databaseName: String
    private lateinit var database: VrkaDatabase
    private lateinit var repository: RoomStorageRepository
    private lateinit var stagingRoot: File
    private lateinit var scope: CoroutineScope
    private val createdUris = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val stamp = System.nanoTime()
        databaseName = "media-publication-$stamp.db"
        database = Room.databaseBuilder(context, VrkaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        repository = RoomStorageRepository(database)
        stagingRoot = File(context.cacheDir, "integration_staging_$stamp").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        publisher = OutputPublisher(
            context = context,
            settingsRepository = SettingsRepository(context, scope),
            journal = publicationStore,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    @After
    fun tearDown() {
        createdUris.forEach { uri -> runCatching { publisher.delete(uri) } }
        stagingRoot.deleteRecursively()
        database.close()
        val dbFile = context.getDatabasePath(databaseName)
        listOf("", "-wal", "-shm").forEach { File(dbFile.absolutePath + it).delete() }
        scope.cancel()
    }

    private val publicationStore: PublicationStore by lazy {
        val roomStore = repository.publicationStore
        object : PublicationStore by roomStore {
            override fun recordIntent(entry: PublicationEntry) = recordBatchIntents(listOf(entry))

            override fun recordBatchIntents(batch: List<PublicationEntry>) {
                val missing = batch.map { it.jobId }.distinct().filter { id ->
                    repository.cachedJobs().none { it.id == id }
                }
                if (missing.isNotEmpty()) {
                    val seeded = runBlocking {
                        repository.saveJobs(missing.map { id ->
                            DownloadJob(
                                id = id,
                                request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
                                state = JobState.FAILED,
                                publishStarted = true,
                            )
                        }, missing.toSet())
                    }
                    check(seeded.success) { "Could not seed Room publication jobs: ${seeded.error}" }
                    runBlocking { repository.loadCache() }
                }
                roomStore.recordBatchIntents(batch)
            }
        }
    }

    @Test
    fun mediaStorePublicationIsFinalizedAndVerifiedByTheRealProvider() {
        val displayName = "vrka-integration-${System.currentTimeMillis()}.mp4"
        val staging = File(stagingRoot, displayName).apply { writeBytes(ByteArray(4096)) }
        seedJob("integration-job")

        val published = publisher.publishAll(
            files = listOf(staging to displayName),
            destinationTreeUri = null,
            jobId = "integration-job",
        ).single()
        createdUris += published.uri.toString()

        assertTrue(
            "Real MediaStore row must verify as finalized with IS_PENDING cleared",
            publisher.verifyMediaStoreFinalized(published.uri, displayName),
        )
        val inspection = publisher.inspectMediaStoreOutput(published.uri, displayName)
        assertEquals(ProviderObjectStatus.VERIFIED, inspection.status)
        assertEquals(displayName, inspection.displayName)

        val entry = publisher.activeJournal.entriesForJob("integration-job").single()
        assertEquals(PublicationTransactionStatus.COMPLETED, entry.status)
        assertEquals(published.transactionId, entry.transactionId)
        assertEquals(published.uri.toString(), entry.providerUri)

        assertTrue(
            "publishAll must leave the staging source for the terminal commit",
            staging.exists(),
        )

        assertTrue("The real provider row must be deletable by its owner", publisher.delete(published.uri.toString()))
        createdUris.remove(published.uri.toString())
        assertFalse(publisher.fileExists(published.uri.toString()))
    }

    @Test
    fun realMediaStorePendingRowIsRecoveredIntoDurableHistory() {
        val finalName = "vrka-recovered-${System.currentTimeMillis()}.mp4"
        val jobId = "integration-recovery-${System.nanoTime()}"
        val transactionId = "tx-recovery-${System.nanoTime()}"
        val pendingName = PublicationUriRules.pendingNameFor(transactionId, finalName)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, pendingName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VRKA")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        createdUris += uri.toString()
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(ByteArray(2048)) }

        val staging = File(stagingRoot, "$jobId/$finalName").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2048))
        }
        val job = DownloadJob(
            id = jobId,
            request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = JobState.FAILED,
            publishStarted = true,
            error = INTERRUPTED_DOWNLOAD_ERROR,
        )
        assertTrue(runBlocking { repository.saveJobs(listOf(job), setOf(jobId)) }.success)
        runBlocking { repository.loadCache() }
        val journal = publicationStore
        journal.recordIntent(
            PublicationEntry(
                transactionId = transactionId,
                jobId = jobId,
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = finalName,
                pendingFilename = pendingName,
                stagingPath = staging.absolutePath,
                expectedSizeBytes = 2048,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = PublicationUriRules.MEDIA_STORE_DESTINATION,
                publicationPhase = PublicationPhase.INTENT,
                attemptNumber = 1,
                attemptId = JobJsonCodec.attemptIdFor(jobId, 1),
                batchId = "batch-$transactionId",
            ),
        )
        journal.updateProviderCreated(transactionId, uri.toString())
        journal.updateDataCopied(transactionId, 2048)

        val settings = SettingsRepository(context, scope)
        val manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customRepository = repository,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            autoRecoverOnInit = false,
        )
        manager.reconcilePublicationJournalOnStartup()

        val durable = repository.cachedJobs().single()
        assertEquals(JobState.DONE, durable.state)
        assertEquals(listOf(uri.toString()), durable.outputUris)
        assertTrue(journal.unreconciledEntries().isEmpty())
        assertTrue(
            "Recovery must finalize the real pending MediaStore row",
            publisher.verifyMediaStoreFinalized(uri, finalName),
        )
        assertFalse(staging.exists())

        // Retry against the same durable files must be idempotent.
        manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, repository.cachedJobs().single().state)

        createdUris.remove(uri.toString())
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun seedJob(jobId: String) {
        val job = DownloadJob(
            id = jobId,
            request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = JobState.FAILED,
            publishStarted = true,
        )
        val saved = runBlocking { repository.saveJobs(listOf(job), setOf(jobId)) }
        check(saved.success) { "Could not seed Room job: ${saved.error}" }
        runBlocking { repository.loadCache() }
    }
}
