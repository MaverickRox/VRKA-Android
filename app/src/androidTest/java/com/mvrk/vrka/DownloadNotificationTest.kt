package com.mvrk.vrka

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime contract for download notifications. These run on a started emulator, so they verify
 * what the platform builder actually produces instead of what the source appears to request.
 *
 * The app intentionally posts ordinary progress notifications only: no promoted Live Update is
 * requested, because promotion cannot be verified on the supported hardware.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotificationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun job(progress: Float, state: JobState) = DownloadJob(
        id = "notification-contract",
        request = DownloadRequest(url = "https://example.com/video.mp4"),
        state = state,
        title = "Test clip",
        progress = progress,
        downloadedBytes = 16L * 1024 * 1024,
        reportedTotalBytes = 48L * 1024 * 1024,
        reportedTotalIsExact = true,
    )

    @Test
    fun downloadingNotificationIsDeterminateAndOngoing() {
        DownloadNotifications.ensureChannel(context)
        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))

        assertEquals("Test clip", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            localizedJobStateLabel(context, JobState.DOWNLOADING),
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString(),
        )
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(33, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        // No custom RemoteViews: the platform draws the progress notification.
        assertNull(notification.contentView)
        assertNull(notification.getGroup())
        assertNotNull(notification.smallIcon)
        assertEquals("the Cancel action must stay available", 1, notification.actions.size)
    }

    @Test
    fun channelIsVisibleButQuiet() {
        DownloadNotifications.ensureChannel(context)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(DownloadNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertTrue(channel!!.importance > NotificationManager.IMPORTANCE_MIN)
    }

    @Test
    fun unknownProgressIsIndeterminateInsteadOfFakePercent() {
        DownloadNotifications.ensureChannel(context)
        val notification = DownloadNotifications.build(context, job(0f, JobState.PREPARING))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    /** Outcome B: the app never asks the platform to promote these notifications. */
    @Test
    fun notificationNeverRequestsPromotion() {
        DownloadNotifications.ensureChannel(context)
        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))

        assertFalse(
            notification.extras.containsKey("android.requestPromotedOngoing"),
        )
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        // Only the system may assign the promoted flag; the app never sets it.
        assertEquals(0, notification.flags and Notification.FLAG_PROMOTED_ONGOING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertFalse(
                "an ordinary progress notification is not a Live Update candidate",
                notification.hasPromotableCharacteristics(),
            )
        }
    }

    /** The moving marker under the progress bar was part of the removed style. */
    @Test
    fun notificationCarriesNoProgressTracker() {
        DownloadNotifications.ensureChannel(context)
        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))
        val keys = notification.extras.keySet()
        Log.i(QA_TAG, "notification extras: $keys")
        assertNull(
            "no tracker extra may remain, got $keys",
            keys.firstOrNull { it.contains("tracker", ignoreCase = true) },
        )
    }

    @Test
    fun promotionPermissionIsNotRequested() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertFalse(
            "POST_PROMOTED_NOTIFICATIONS must not be declared, got $requested",
            requested.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"),
        )
    }

    /**
     * Posts two jobs and inspects what the system stored. The promoted flag is read back from
     * the system: on this build it must never be assigned to these notifications.
     */
    @Test
    fun postingKeepsPerJobIndependenceAndNeverPromotes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        grantPostNotifications()
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue(
            "notifications are disabled for this test package",
            manager.areNotificationsEnabled(),
        )
        DownloadNotifications.ensureChannel(context)

        val jobA = job(40f, JobState.DOWNLOADING).copy(id = "promotion-alpha")
        val jobB = job(60f, JobState.DOWNLOADING).copy(id = "promotion-beta")
        val idA = notificationIdFor(jobA.id)
        val idB = notificationIdFor(jobB.id)
        assertTrue(idA != idB)

        try {
            manager.notify(idA, DownloadNotifications.build(context, jobA))
            manager.notify(idB, DownloadNotifications.build(context, jobB))

            val active = awaitActive(manager, idA, idB)
            // Posting the second job must not rewrite the first one.
            assertEquals(40, active.getValue(idA).extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(60, active.getValue(idB).extras.getInt(Notification.EXTRA_PROGRESS))

            for ((id, notification) in active) {
                Log.i(
                    QA_TAG,
                    "posted id=$id promotedFlag=${notification.flags and Notification.FLAG_PROMOTED_ONGOING}",
                )
                assertEquals(0, notification.flags and Notification.FLAG_PROMOTED_ONGOING)
                assertFalse(notification.extras.containsKey("android.requestPromotedOngoing"))
            }
        } finally {
            manager.cancel(idA)
            manager.cancel(idB)
        }
    }

    /** [NotificationManager.notify] is asynchronous on some builds. */
    private fun awaitActive(
        manager: NotificationManager,
        vararg ids: Int,
    ): Map<Int, Notification> {
        val deadline = System.currentTimeMillis() + 3_000L
        while (true) {
            val active = manager.activeNotifications
                .filter { it.id in ids }
                .associate { it.id to it.notification }
            if (active.size == ids.size) return active
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("notifications $ids did not become active, got ${active.keys}")
            }
            Thread.sleep(25L)
        }
    }

    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} $permission")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private companion object {
        const val QA_TAG = "LiveUpdateQA"
    }
}
