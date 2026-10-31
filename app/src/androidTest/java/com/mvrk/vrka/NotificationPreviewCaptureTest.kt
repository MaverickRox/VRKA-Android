package com.mvrk.vrka

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Posts one active and one completed download notification and captures the expanded shade,
 * so the single left VRKA icon and the absent right-side logo can be reviewed on device.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPreviewCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun captureNotificationShadeWithOneVrkaIcon() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
        DownloadNotifications.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = DownloadJob(
            id = "qa-notification",
            request = DownloadRequest(url = "https://example.com/qa"),
            state = JobState.DOWNLOADING,
            title = "QA download",
            progress = 43f,
            speed = "6.2 MiB/s",
            etaSeconds = 42,
            downloadedBytes = 20L * 1024 * 1024,
            reportedTotalBytes = 48L * 1024 * 1024,
            reportedTotalIsExact = true,
        )
        val completed = active.copy(
            id = "qa-notification-done",
            state = JobState.DONE,
            progress = 100f,
            actualFinalFileBytes = 48L * 1024 * 1024,
            outputUris = listOf("content://media/external/downloads/qa"),
        )
        val completedTag = DownloadNotifications.completionTagFor(completed.id)
        manager.notify(ACTIVE_ID, DownloadNotifications.build(context, active))
        manager.notify(completedTag, COMPLETED_ID, DownloadNotifications.buildCompleted(context, completed))

        Thread.sleep(500)
        instrumentation.uiAutomation
            .executeShellCommand("cmd statusbar expand-notifications")
            .close()
        Thread.sleep(2_500)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(context.getExternalFilesDir(null), "notification_previews").apply { mkdirs() }
        FileOutputStream(File(output, "notification_shade.png")).use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.uiAutomation
            .executeShellCommand("cmd statusbar collapse")
            .close()
        manager.cancel(ACTIVE_ID)
        manager.cancel(completedTag, COMPLETED_ID)
    }

    private companion object {
        const val ACTIVE_ID = 7_101
        const val COMPLETED_ID = 7_102
    }
}
