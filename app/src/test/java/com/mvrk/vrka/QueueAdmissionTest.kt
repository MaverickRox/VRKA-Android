package com.mvrk.vrka

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Queue admission must never accept a job that cannot be durably persisted, and a full
 * active queue must be rejected before allocating another job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueAdmissionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stagingRoot = File(tempFolder.root, "staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager(store: JobStore, cleanupStore: StagingCleanupStore): VrkaDownloadManager =
        VrkaDownloadManager(
            context = context,
            settingsRepository = SettingsRepository(context, scope),
            scope = scope,
            customStore = store,
            customPublisher = OutputPublisher(
                context = context,
                settingsRepository = SettingsRepository(context, scope),
                journal = PublicationJournal(File(tempFolder.root, "journal-${System.nanoTime()}.json")),
            ),
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = cleanupStore,
            autoRecoverOnInit = false,
        )

    private fun request() = DownloadRequest(
        url = "https://example.com/watch?v=admission",
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
    )

    @Test
    fun invalidUrlIsRejectedBeforeAnyPersistence() {
        val storeFile = File(tempFolder.root, "jobs-invalid.json")
        val manager = manager(
            JobStore(storeFile),
            StagingCleanupStore(File(tempFolder.root, "cleanup-invalid.json")),
        )

        val result = manager.enqueue(request().copy(url = "javascript:alert(1)"))

        assertEquals("invalid_url", (result as EnqueueResult.Rejected).reason)
        assertFalse(storeFile.exists())
        assertTrue(manager.jobs.value.isEmpty())
    }

    @Test
    fun fullActiveQueueIsRejectedBeforeAcceptance() {
        val storeFile = File(tempFolder.root, "jobs-full.json")
        val jobs = (0 until 1_000).map { index ->
            DownloadJob(
                id = "active-$index",
                request = request(),
                state = JobState.QUEUED,
                createdAt = index.toLong(),
            )
        }
        val seeded = JobStore(storeFile).save(jobs, jobs.mapTo(HashSet()) { it.id })
        assertTrue("Seed save must succeed: ${seeded.error}", seeded.success)

        DownloadTransferRuntime.markTransferJobInvoked()
        val manager = try {
            manager(
                JobStore(storeFile),
                StagingCleanupStore(File(tempFolder.root, "cleanup-full.json")),
            )
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
        val result = manager.enqueue(request())

        assertEquals("queue_capacity", (result as EnqueueResult.Rejected).reason)
        assertEquals(1_000, manager.jobs.value.size)
    }

    @Test
    fun persistenceFailureRejectsTheJobAndRollsBackTheCache() {
        val storeFile = File(tempFolder.root, "jobs-failing.json")
        val store = JobStore(
            storeFile,
            PublicationJournalRecoveryTest.TestJobStoreFileOps(failWrite = true),
        )
        val manager = manager(
            store,
            StagingCleanupStore(File(tempFolder.root, "cleanup-failing.json")),
        )

        val result = manager.enqueue(request())

        assertEquals("storage_write_failed", (result as EnqueueResult.Rejected).reason)
        assertTrue("A rejected job must not remain in the cache", manager.jobs.value.isEmpty())
        assertFalse(storeFile.exists())
    }
}
