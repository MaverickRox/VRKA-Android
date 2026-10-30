package com.mvrk.vrka

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime contract for the Android 16 Live Update notification. These run on a started
 * emulator, so they verify what the platform builder actually produces instead of what the
 * source appears to request.
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
        totalBytes = 48L * 1024 * 1024,
        totalIsExact = true,
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
        assertEquals(
            "33%",
            notification.extras.getString("android.shortCriticalText"),
        )
        // No custom RemoteViews and no group summary: the platform draws the Live Update.
        assertNull(notification.contentView)
        assertNull(notification.getGroup())
        assertNotNull(notification.smallIcon)
        assertEquals(
            Notification.ProgressStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
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
}
