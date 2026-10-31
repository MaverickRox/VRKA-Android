package com.mvrk.vrka

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime contract for the completion notification. Everything here is posted and read back
 * through the real NotificationManager, so it proves what the system stores rather than what
 * the builder appears to request.
 */
@RunWith(AndroidJUnit4::class)
class CompletionNotificationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val publishedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        grantPostNotifications()
        DownloadNotifications.ensureChannel(context)
        assumeTrue(
            "notifications are disabled for this test package",
            notificationManager.areNotificationsEnabled(),
        )
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        publishedUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        publishedUris.clear()
    }

    private fun completedJob(
        id: String,
        title: String = "I Made the Perfect Android Home Screen",
        actualBytes: Long? = 80L * 1024 * 1024,
        estimatedBytes: Long? = 171L * 1024 * 1024,
        outputs: List<String>,
    ) = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://example.com/video"),
        state = JobState.DONE,
        title = title,
        progress = 100f,
        downloadedBytes = estimatedBytes,
        estimatedTotalBytes = estimatedBytes,
        estimatedSizeAccuracy = if (estimatedBytes != null) SizeEstimateKind.APPROXIMATE else SizeEstimateKind.UNAVAILABLE,
        reportedTotalBytes = estimatedBytes,
        reportedTotalIsExact = false,
        actualFinalFileBytes = actualBytes,
        outputUris = outputs,
        updatedAt = 1_760_000_000_000L,
    )

    private fun publishTestFile(name: String, bytes: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VRKA-QA",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        publishedUris += uri
        resolver.openOutputStream(uri, "w")!!.use { it.write(ByteArray(bytes)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    private fun completionNotifications(jobId: String) =
        notificationManager.activeNotifications.filter {
            it.tag == DownloadNotifications.completionTagFor(jobId)
        }

    /**
     * NotificationManager.notify() is asynchronous, so the posted record has to be awaited
     * instead of sampled immediately.
     */
    private fun awaitCompletion(jobId: String): List<StatusBarNotification> {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = completionNotifications(jobId)
            if (matches.isNotEmpty()) return matches
            Thread.sleep(25)
        }
        return emptyList()
    }

    @Test
    fun successfulDownloadProducesOneDismissibleCompletionNotification() {
        val uri = publishTestFile("vrka-completion.mp4", 2048)
        val job = completedJob("completion-alpha", outputs = listOf(uri.toString()))

        DownloadNotifications.notifyCompleted(context, job)

        val posted = awaitCompletion(job.id)
        assertEquals("exactly one completion record", 1, posted.size)
        val notification = posted.single().notification
        assertEquals(job.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(DownloadNotifications.COMPLETION_CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(
            "a finished transfer must not stay marked ongoing",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )
        assertNotNull("the record must be tappable", notification.contentIntent)
        assertTrue(
            "the record must carry its completion time",
            kotlin.math.abs(System.currentTimeMillis() - notification.`when`) < 5_000L,
        )
    }

    @Test
    fun notificationShowsThePublishedSizeNotThePreDownloadEstimate() {
        val uri = publishTestFile("vrka-size.mp4", 2048)
        val job = completedJob(
            "completion-size",
            actualBytes = 80L * 1024 * 1024,
            estimatedBytes = 171L * 1024 * 1024,
            outputs = listOf(uri.toString()),
        )

        DownloadNotifications.notifyCompleted(context, job)

        val text = awaitCompletion(job.id).single().notification
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        Log.i(QA_TAG, "completion text: $text")
        assertTrue("published size must be displayed: $text", text.contains("80.0 MiB"))
        assertFalse("the estimate must not be reused: $text", text.contains("171"))
    }

    @Test
    fun failedAndCancelledDownloadsNeverPostACompletion() {
        listOf(JobState.FAILED, JobState.CANCELLED, JobState.DOWNLOADING).forEach { state ->
            val job = completedJob("completion-${state.name.lowercase()}", outputs = listOf("content://media/x/1"))
                .copy(state = state)

            DownloadNotifications.notifyCompleted(context, job)

            assertTrue(
                "$state must not produce a completion record",
                completionNotifications(job.id).isEmpty(),
            )
        }
    }

    @Test
    fun completionSurvivesForegroundServiceAndTransferJobCleanup() {
        val uri = publishTestFile("vrka-survive.mp4", 2048)
        val completed = completedJob("completion-survives", outputs = listOf(uri.toString()))
        val active = DownloadJob(
            id = "active-survives",
            request = DownloadRequest(url = "https://example.com/other"),
            state = JobState.DOWNLOADING,
            title = "Active transfer",
            progress = 20f,
        )
        DownloadNotifications.notifyCompleted(context, completed)
        assertTrue(awaitCompletion(completed.id).isNotEmpty())
        notificationManager.notify(
            notificationIdFor(active.id),
            DownloadNotifications.build(context, active),
        )

        // Exactly what the foreground service and the transfer job delete on idle cleanup:
        // their own active id range plus the reserved foreground ids.
        (listOf(notificationIdFor(active.id), notificationIdFor(completed.id)) +
            PENDING_NOTIFICATION_ID + INTERRUPTED_NOTIFICATION_ID)
            .forEach(notificationManager::cancel)

        assertTrue(
            "idle cleanup must not delete a finished download",
            awaitCompletion(completed.id).isNotEmpty(),
        )
        assertTrue(
            "the active record must be gone after cleanup",
            notificationManager.activeNotifications.none { it.id == notificationIdFor(active.id) },
        )
    }

    @Test
    fun multipleCompletionsRemainDistinctRecords() {
        val first = publishTestFile("vrka-first.mp4", 1024)
        val second = publishTestFile("vrka-second.mp4", 1024)
        val jobA = completedJob("completion-one", title = "First clip", outputs = listOf(first.toString()))
        val jobB = completedJob("completion-two", title = "Second clip", outputs = listOf(second.toString()))

        DownloadNotifications.notifyCompleted(context, jobA)
        DownloadNotifications.notifyCompleted(context, jobB)

        val a = awaitCompletion(jobA.id).single().notification
        val b = awaitCompletion(jobB.id).single().notification
        assertEquals("First clip", a.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Second clip", b.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(DownloadNotifications.completionTagFor(jobA.id) != DownloadNotifications.completionTagFor(jobB.id))
        assertEquals(2, notificationManager.activeNotifications.count { it.tag?.startsWith("completed:") == true })
    }

    @Test
    fun openActionTargetsThePublishedContentUri() {
        val uri = publishTestFile("vrka-open.mp4", 2048)
        val job = completedJob("completion-open", outputs = listOf(uri.toString()))

        val intent = DownloadNotifications.completionViewIntent(context, job)
        assertNotNull("a published media file must be openable", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(uri, intent.data)
        assertEquals("video/mp4", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(
            "raw filesystem paths must never be exposed",
            intent.dataString?.startsWith("file:") == true,
        )

        val resolvable = DownloadNotifications.completionOpenIntent(context, job)
        if (resolvable != null) {
            DownloadNotifications.notifyCompleted(context, job)
            val notification = awaitCompletion(job.id).single().notification
            assertEquals("the Open action must be present", 1, notification.actions.size)
            assertEquals(
                context.getString(R.string.btn_open),
                notification.actions.single().title.toString(),
            )
        } else {
            Log.i(QA_TAG, "no installed viewer resolves ACTION_VIEW for video/mp4")
        }
    }

    @Test
    fun completionChannelIsAnnouncingAndBadged() {
        DownloadNotifications.ensureChannel(context)
        val channel = notificationManager.getNotificationChannel(
            DownloadNotifications.COMPLETION_CHANNEL_ID,
        )

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        assertTrue(channel.canShowBadge())
        assertFalse(
            "completion must not share the quiet ongoing channel",
            DownloadNotifications.COMPLETION_CHANNEL_ID == DownloadNotifications.CHANNEL_ID,
        )
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
        const val QA_TAG = "CompletionQA"
    }
}
