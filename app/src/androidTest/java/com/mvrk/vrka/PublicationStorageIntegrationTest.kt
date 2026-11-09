package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private lateinit var journalFile: File
    private lateinit var stagingRoot: File
    private lateinit var scope: CoroutineScope
    private val createdUris = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val stamp = System.nanoTime()
        journalFile = File(context.cacheDir, "integration_journal_$stamp.json")
        stagingRoot = File(context.cacheDir, "integration_staging_$stamp").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        publisher = OutputPublisher(
            context = context,
            settingsRepository = SettingsRepository(context, scope),
            journal = PublicationJournal(journalFile),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    @After
    fun tearDown() {
        createdUris.forEach { uri -> runCatching { publisher.delete(uri) } }
        journalFile.delete()
        stagingRoot.deleteRecursively()
    }

    @Test
    fun mediaStorePublicationIsFinalizedAndVerifiedByTheRealProvider() {
        val displayName = "vrka-integration-${System.currentTimeMillis()}.mp4"
        val staging = File(stagingRoot, displayName).apply { writeBytes(ByteArray(4096)) }

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
        val journal = PublicationJournal(journalFile)
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
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        journal.updateProviderCreated(transactionId, uri.toString())
        journal.updateDataCopied(transactionId, 2048)

        val storeFile = File(context.cacheDir, "integration_jobs_${System.nanoTime()}.json")
        val store = JobStore(storeFile)
        val job = DownloadJob(
            id = jobId,
            request = DownloadRequest(url = "", mode = MediaMode.VIDEO, quality = VideoQuality.P1080),
            state = JobState.FAILED,
            publishStarted = true,
            error = INTERRUPTED_DOWNLOAD_ERROR,
        )
        assertTrue(store.save(listOf(job), setOf(jobId)).success)

        val settings = SettingsRepository(context, scope)
        val manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = scope,
            customStore = store,
            customPublisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = journal,
                sdkInt = Build.VERSION.SDK_INT,
            ),
            customStagingRoot = stagingRoot,
            autoRecoverOnInit = false,
        )
        manager.reconcilePublicationJournalOnStartup()

        val durable = JobStore(storeFile).load(recoverActive = true).single()
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
        assertEquals(JobState.DONE, JobStore(storeFile).load(recoverActive = true).single().state)

        storeFile.delete()
        createdUris.remove(uri.toString())
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
