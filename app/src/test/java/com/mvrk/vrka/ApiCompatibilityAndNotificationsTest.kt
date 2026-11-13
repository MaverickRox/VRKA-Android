package com.mvrk.vrka

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.VrkaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ApiCompatibilityAndNotificationsTest {

    private fun sampleDoneJob(id: String) = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://example.invalid/media.mp4"),
        state = JobState.DONE,
        title = "Sample Completed Clip",
        outputUris = listOf("content://media/external/downloads/42"),
    )

    private fun sampleQueuedJob(id: String) = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://example.invalid/media.mp4"),
        state = JobState.QUEUED,
        title = "Sample Queued Clip",
    )

    private fun verifyRoomRoundTrip(app: Application, jobId: String) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = RoomStorageRepository(db)
            val job = sampleQueuedJob(jobId)
            assertTrue(repo.saveJobs(listOf(job), emptySet()).success)
            repo.loadCache()
            assertEquals(jobId, repo.cachedJobs().single().id)
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [26])
    fun api26MinSdkEnforcesSafCreatesChannelsAndPostsWithoutRuntimePermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(26, Build.VERSION.SDK_INT)

        // Scoped storage policy at minSdk 26: requires an explicit granted SAF tree.
        assertTrue(DestinationPolicy.requiresSafDestination(Build.VERSION.SDK_INT))
        assertFalse(
            DestinationPolicy.acceptsDestination(
                treeUri = null,
                sdkInt = Build.VERSION.SDK_INT,
                hasPersistedWritePermission = false,
            ),
        )
        assertTrue(
            DestinationPolicy.acceptsDestination(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                sdkInt = Build.VERSION.SDK_INT,
                hasPersistedWritePermission = true,
            ),
        )

        val nm = app.getSystemService(NotificationManager::class.java)
        DownloadNotifications.ensureChannel(app)
        assertNotNull(nm.getNotificationChannel(DownloadNotifications.CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(DownloadNotifications.COMPLETION_CHANNEL_ID))

        DownloadNotifications.notifyCompleted(app, sampleDoneJob("job-api26-done"))
        val posted = Shadows.shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertEquals(DownloadNotifications.COMPLETION_CHANNEL_ID, posted.first().channelId)

        verifyRoomRoundTrip(app, "room-api26")
    }

    @Test
    @Config(sdk = [36])
    fun api36TargetSdkUsesMediaStoreDefaultAndGatesCompletionNotificationsOnPostNotificationsPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(36, Build.VERSION.SDK_INT)

        // Scoped storage policy at targetSdk 36: default MediaStore Downloads without SAF grant.
        assertFalse(DestinationPolicy.requiresSafDestination(Build.VERSION.SDK_INT))
        assertTrue(
            DestinationPolicy.acceptsDestination(
                treeUri = null,
                sdkInt = Build.VERSION.SDK_INT,
                hasPersistedWritePermission = false,
            ),
        )
        assertFalse(
            DestinationPolicy.acceptsDestination(
                treeUri = "content://tree/revoked",
                sdkInt = Build.VERSION.SDK_INT,
                hasPersistedWritePermission = false,
            ),
        )

        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationsEnabled(true)
        val shadowApp = Shadows.shadowOf(app)

        // Permission denied -> platform alert is skipped cleanly.
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DownloadNotifications.notifyCompleted(app, sampleDoneJob("job-api36-denied"))
        assertEquals(0, shadowNm.allNotifications.size)

        // Permission granted -> completion notification posts onto completion channel.
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS),
        )
        DownloadNotifications.notifyCompleted(app, sampleDoneJob("job-api36-granted"))
        val posted = shadowNm.allNotifications
        assertEquals(1, posted.size)
        assertEquals(DownloadNotifications.COMPLETION_CHANNEL_ID, posted.first().channelId)

        verifyRoomRoundTrip(app, "room-api36")
    }

    @Test
    @Config(sdk = [36])
    fun api36UserInitiatedDataTransferHandoffLifecycle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        DownloadTransferRuntime.resetForTesting()

        // 1. Initial state: not scheduled, not running
        assertFalse(DownloadTransferScheduler.isScheduled(app))
        assertFalse(DownloadTransferRuntime.isTransferJobRunning)

        // 2. Schedule user-initiated transfer job while in eligible foreground state
        DownloadTransferScheduler.schedule(app)
        assertTrue(DownloadTransferScheduler.isScheduled(app))

        // 3. Case A: Transfer job has not started before timeout -> Must trigger durable recovery (INTERRUPT)
        val preAction = DownloadRecovery.foregroundTimeoutAction(DownloadTransferRuntime.isTransferJobRunning)
        assertEquals(ForegroundTimeoutAction.INTERRUPT, preAction)

        // 4. Case B: Transfer job started, acquired ownership -> Must HANDOFF cleanly without false interruption
        DownloadTransferRuntime.setTransferJobRunning(true)
        DownloadTransferRuntime.markTransferJobInvoked()
        val postAction = DownloadRecovery.foregroundTimeoutAction(DownloadTransferRuntime.isTransferJobRunning)
        assertEquals(ForegroundTimeoutAction.HANDOFF, postAction)

        // 5. Active work recovery requires both API 34+ and scheduled transfer job
        assertTrue(DownloadRecovery.shouldRecoverPersistedActiveWork(sdkInt = 36, transferJobScheduled = true))
        assertFalse(DownloadRecovery.shouldRecoverPersistedActiveWork(sdkInt = 36, transferJobScheduled = false))

        DownloadTransferRuntime.resetForTesting()
    }
}
