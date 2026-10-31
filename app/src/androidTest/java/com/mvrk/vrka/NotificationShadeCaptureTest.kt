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
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator evidence for the notification icon contract. Each state is posted through the real
 * builder, the Android 16 shade is expanded, and a screenshot is written to the app's external
 * files directory so it can be pulled without a custom RemoteViews.
 */
@RunWith(AndroidJUnit4::class)
class NotificationShadeCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private fun job(id: String, progress: Float, state: JobState) = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://example.com/video.mp4"),
        state = state,
        title = "Harbour walk 4K",
        progress = progress,
        downloadedBytes = 24L * 1024 * 1024,
        reportedTotalBytes = 96L * 1024 * 1024,
        reportedTotalIsExact = true,
    )

    @Test
    fun capturesActiveCompletedInterruptedAndConcurrentStates() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        grantPostNotifications()
        assumeTrue("notifications are disabled", manager.areNotificationsEnabled())
        DownloadNotifications.ensureChannel(context)
        val outDir = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }

        capture(
            "wp3_active",
            outDir,
            notificationIdFor("qa-active") to DownloadNotifications.build(
                context,
                job("qa-active", 0.42f, JobState.DOWNLOADING),
            ),
        )
        capture(
            "wp3_completed",
            outDir,
            notificationIdFor("qa-completed") to DownloadNotifications.buildCompleted(
                context,
                job("qa-completed", 1f, JobState.DONE).copy(actualFinalFileBytes = 96L * 1024 * 1024),
            ),
        )
        capture(
            "wp3_interrupted",
            outDir,
            0x5151_0003 to DownloadNotifications.buildInterrupted(context),
        )
        capture(
            "wp3_concurrent",
            outDir,
            notificationIdFor("qa-alpha") to DownloadNotifications.build(
                context,
                job("qa-alpha", 0.27f, JobState.DOWNLOADING),
            ),
            notificationIdFor("qa-beta") to DownloadNotifications.build(
                context,
                job("qa-beta", 0.71f, JobState.DOWNLOADING),
            ),
        )
    }

    private fun capture(label: String, outDir: File, vararg entries: Pair<Int, Notification>) {
        try {
            entries.forEach { (id, notification) ->
                assertEquals(
                    "$label: every state must use the static download-arrow small icon",
                    R.drawable.ic_download,
                    notification.smallIcon.resId,
                )
                assertNull(
                    "$label: no second right-side large logo",
                    notification.extras.get(Notification.EXTRA_LARGE_ICON),
                )
                manager.notify(id, notification)
            }
            shell("cmd statusbar expand-notifications")
            Thread.sleep(1_800L)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val file = File(outDir, "$label.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            Log.i(QA_TAG, "captured $label -> ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
            // The connected test runner uninstalls the app afterwards, which removes its
            // external files directory. Keep a copy in shared storage for the QA record.
            shell("mkdir -p $SHARED_QA_DIR")
            shell("cp ${file.absolutePath} $SHARED_QA_DIR/$label.png")
            assertTrue("screenshot must not be empty", bitmap.width > 0 && bitmap.height > 0)
        } finally {
            shell("cmd statusbar collapse")
            entries.forEach { (id, _) -> manager.cancel(id) }
            Thread.sleep(400L)
        }
    }

    private fun shell(command: String) {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        shell("pm grant ${context.packageName} $permission")
    }

    private companion object {
        const val QA_TAG = "NotificationQA"
        const val SHARED_QA_DIR = "/sdcard/Download/vrka_qa"
    }
}
