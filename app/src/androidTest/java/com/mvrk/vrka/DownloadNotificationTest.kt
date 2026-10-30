package com.mvrk.vrka

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
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

    /** Android 16.0 promotes colorized notifications; 16 QPR1 needs the explicit request. */
    @Test
    fun api36NotificationRequestsPromotionWithThePlatformContract() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        DownloadNotifications.ensureChannel(context)

        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))

        Log.i(
            QA_TAG,
            "api contract: sdkIntFull=${Build.VERSION.SDK_INT_FULL} " +
                "richOngoingContract=${DownloadNotifications.usesRichOngoingContract(context)} " +
                "promotionAvailable=${isPromotionAvailable(context)}",
        )

        assertTrue(
            "the promotion request extra must be present before posting",
            notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING),
        )
        if (DownloadNotifications.usesRichOngoingContract(context)) {
            // QPR1 disqualifies colorized notifications, so the app must not request it.
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        } else {
            // 16.0 refuses to promote anything that is not colorized.
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        }
        // Only the system may assign the promoted flag; the app never sets it.
        assertEquals(0, notification.flags and Notification.FLAG_PROMOTED_ONGOING)
    }

    @Test
    fun api36NotificationIsStructurallyPromotable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        DownloadNotifications.ensureChannel(context)

        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))

        assertTrue(
            "ongoing, titled, non-summary, no RemoteViews and a promotable style: " +
                "the platform must accept it as a Live Update candidate",
            notification.hasPromotableCharacteristics(),
        )
    }

    @Test
    fun promotionPermissionMatchesTheSystem() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            assertFalse(DownloadNotifications.supportsPromotion(context))
            return
        }
        assertEquals(manager.canPostPromotedNotifications(), DownloadNotifications.supportsPromotion(context))
    }

    /** The moving marker under the progress bar must not be drawn at all. */
    @Test
    fun progressTrackerIsFullyTransparent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        DownloadNotifications.ensureChannel(context)

        val notification = DownloadNotifications.build(context, job(33f, JobState.DOWNLOADING))
        val keys = notification.extras.keySet()
        Log.i(QA_TAG, "notification extras: $keys")
        val trackerKey = keys.firstOrNull { it.contains("tracker", ignoreCase = true) }
        assertNotNull("the progress style must declare a tracker icon, got $keys", trackerKey)

        val icon = notification.extras.getParcelable(trackerKey, Icon::class.java)
        assertNotNull(icon)
        assertEquals("only a resource icon is accepted", Icon.TYPE_RESOURCE, icon!!.type)

        val drawable = icon.loadDrawable(context)
        assertNotNull(drawable)
        val size = 16
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable!!.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        for (x in 0 until size) {
            for (y in 0 until size) {
                assertEquals(
                    "tracker pixel $x,$y must be invisible",
                    0,
                    Color.alpha(bitmap.getPixel(x, y)),
                )
            }
        }
    }

    /**
     * Posts two jobs and inspects what the system stored. The promoted flag is read back from
     * the system instead of being trusted or asserted blindly: on a device where the user has
     * not allowed promotion it is legitimately absent.
     */
    @Test
    fun systemAssignedPromotionFlagAndPerJobIndependence() {
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

        val notificationA = DownloadNotifications.build(context, jobA)
        assertEquals(0, notificationA.flags and Notification.FLAG_PROMOTED_ONGOING)
        try {
            manager.notify(idA, notificationA)
            manager.notify(idB, DownloadNotifications.build(context, jobB))

            val active = manager.activeNotifications.associateBy { it.id }
            assertTrue("job A must be listed", active.containsKey(idA))
            assertTrue("job B must be listed", active.containsKey(idB))
            // Posting the second job must not rewrite the first one.
            assertEquals(
                40,
                active.getValue(idA).notification.extras.getInt(Notification.EXTRA_PROGRESS),
            )
            assertEquals(
                60,
                active.getValue(idB).notification.extras.getInt(Notification.EXTRA_PROGRESS),
            )

            val posted = active.getValue(idA).notification
            val promoted = posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            val canPromote = manager.canPostPromotedNotifications()
            Log.i(
                QA_TAG,
                "promotion state: canPostPromotedNotifications=$canPromote " +
                    "hasPromotableCharacteristics=${posted.hasPromotableCharacteristics()} " +
                    "systemAssignedPromotedFlag=$promoted",
            )
            // A promoted notification must have been structurally eligible; the reverse is
            // only guaranteed once the user allows promotion, so it is logged, not asserted.
            if (promoted) assertTrue(posted.hasPromotableCharacteristics())
            assertTrue(posted.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        } finally {
            manager.cancel(idA)
            manager.cancel(idB)
        }
    }

    /** The Settings entry may only ever target this app's own page. */
    @Test
    fun liveUpdateSettingsIntentTargetsThisApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            assertNull(liveUpdateSettingsIntent(context))
            return
        }
        val intent = liveUpdateSettingsIntent(context)
        assertNotNull("a settings destination must resolve on Android 16", intent)
        assertEquals(context.packageName, intent!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(
            intent.action == Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS ||
                intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        )
        assertNotNull(
            "the chosen action must resolve to a real activity",
            intent.resolveActivity(context.packageManager),
        )
    }

    /** An OS without promotion support must not advertise an entry that cannot do anything. */
    @Test
    fun promotionEntryIsOnlyAvailableWhenTheOsExposesPromotion() {
        val available = isPromotionAvailable(context)
        val canPost = DownloadNotifications.supportsPromotion(context)
        Log.i(QA_TAG, "settings entry: available=$available canPostPromotedNotifications=$canPost")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            assertFalse(available)
            assertFalse(canPost)
            return
        }
        if (!available) {
            // No switch anywhere on this build: the app must not claim promotion is possible.
            assertFalse(canPost)
            return
        }
        assertNotNull(liveUpdateSettingsIntent(context))
    }

    /** Pre-36 devices and manual fallbacks keep the ordinary progress notification. */
    @Test
    fun compatFallbackStaysAnOrdinaryProgressNotification() {
        DownloadNotifications.ensureChannel(context)
        val openQueue = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = DownloadNotifications.buildCompatNotification(
            context,
            job(33f, JobState.DOWNLOADING),
            "Test clip",
            localizedJobStateLabel(context, JobState.DOWNLOADING),
            "Downloading • 33%",
            openQueue,
        )

        assertEquals(33, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("the Cancel action must survive the fallback", 1, notification.actions.size)
        assertNull(notification.contentView)
        // Ordinary progress is not a Live Alert: no promotion request and no colorized flag.
        assertFalse(notification.extras.containsKey(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertFalse(notification.hasPromotableCharacteristics())
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
