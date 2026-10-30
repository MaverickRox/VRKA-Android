package com.mvrk.vrka

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mvrk.vrka.R

/**
 * Single source of truth for download notification content. Both the foreground service and
 * the user-initiated transfer worker render the same notification ID and content, so a
 * handoff after the six-hour FGS window does not produce a duplicate notification.
 *
 * Only the standard platform progress notification is used. A promoted/live-update rendering
 * cannot be verified on the supported hardware, so the app does not request one.
 */
internal object DownloadNotifications {
    const val CHANNEL_ID = "vrka_downloads"

    /**
     * Completed downloads use their own channel and a tag-derived identity outside the
     * active-transfer ID range. Both notification owners (foreground service and transfer job)
     * delete only ids from [notificationIdFor], so this record survives their idle cleanup.
     */
    const val COMPLETION_CHANNEL_ID = "vrka_downloads_complete"
    // Outside the active-transfer range (4200..69735) and the reserved foreground ids, so no
    // cleanup path can address these records by accident.
    const val COMPLETION_NOTIFICATION_ID = 100_100
    private const val COMPLETION_TAG_PREFIX = "completed:"

    internal fun completionTagFor(jobId: String): String = COMPLETION_TAG_PREFIX + jobId

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_active_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_active_downloads_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val completionChannel = NotificationChannel(
            COMPLETION_CHANNEL_ID,
            context.getString(R.string.notification_channel_completed_downloads),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(
                R.string.notification_channel_completed_downloads_description,
            )
            setShowBadge(true)
        }
        manager.createNotificationChannel(completionChannel)
    }

    /**
     * Posts the post-transfer record for a job that already reached [JobState.DONE]. The job
     * state is the guard: a retry, a cancellation, or a failure can never post this.
     */
    fun notifyCompleted(context: Context, job: DownloadJob) {
        val allowed = job.state == JobState.DONE &&
            job.outputUris.isNotEmpty() &&
            canPostNotifications(context)
        Log.d(
            "VRKA",
            "completion notification id=${job.id.take(8)} state=${job.state} " +
                "outputs=${job.outputUris.size} allowed=$allowed",
        )
        if (!allowed) {
            // The completed record stays in History; only the platform alert is skipped.
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        runCatching {
            manager.notify(
                completionTagFor(job.id),
                COMPLETION_NOTIFICATION_ID,
                buildCompleted(context, job),
            )
        }.onFailure { error ->
            Log.w("VRKA", "Completion notification could not be posted: ${error::class.java.simpleName}")
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun buildCompleted(context: Context, job: DownloadJob): Notification {
        val title = job.title.ifBlank { context.getString(R.string.notification_default_title) }
        val text = completionText(context, job)
        val fileIntent = completionOpenIntent(context, job)
        val builder = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.vrka_purple))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // The platform drops a notification whose timestamp predates the enqueue (seen as
            // "Ignored enqueue for old ... notification"), and a UIDT handoff can post this
            // minutes after the job finished. The alert time is therefore the posting time.
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            // A finished transfer is a dismissible record, not an ongoing operation.
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                fileIntent?.let { viewFilePendingIntent(context, job, it) } ?: openQueueIntent(context),
            )
        if (fileIntent != null) {
            builder.addAction(
                R.drawable.ic_download,
                context.getString(R.string.btn_open),
                viewFilePendingIntent(context, job, fileIntent),
            )
        }
        return builder.build()
    }

    internal fun completionText(context: Context, job: DownloadJob): String {
        val size = job.actualFinalFileBytes?.takeIf { it > 0L }?.let(::formatDownloadBytes)
        return when {
            size == null -> context.getString(R.string.notification_download_completed)
            job.outputUris.size > 1 -> context.getString(
                R.string.notification_download_completed_files,
                job.outputUris.size,
                size,
            )
            else -> context.getString(R.string.notification_download_completed, size)
        }
    }

    /**
     * A content URI plus the provider's MIME type. Raw filesystem paths are never used: the
     * published document is the only thing a viewer can open.
     */
    internal fun completionViewIntent(context: Context, job: DownloadJob): Intent? {
        val uriText = job.outputUris.firstOrNull() ?: return null
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
            ?: OutputPublisher.guessMimeType(resolver.queryDisplayName(uri, uriText))
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The viewer intent, but only when some installed activity can actually handle it. */
    internal fun completionOpenIntent(context: Context, job: DownloadJob): Intent? =
        completionViewIntent(context, job)
            ?.takeIf { it.resolveActivity(context.packageManager) != null }

    private fun ContentResolver.queryDisplayName(uri: Uri, fallback: String): String =
        runCatching {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull() ?: fallback

    private fun viewFilePendingIntent(
        context: Context,
        job: DownloadJob,
        intent: Intent,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        job.id.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun build(context: Context, job: DownloadJob?): Notification {
        val openIntent = openQueueIntent(context)
        val title = job?.title?.ifBlank { context.getString(R.string.active_download_title) }
            ?: context.getString(R.string.app_name)
        val status = job?.let { jobStatusLabel(context, it) } ?: context.getString(R.string.status_preparing)
        val summary = job?.let {
            val progress = jobProgressSummary(context, it)
            if (progress == it.detail) "$status • ${requestSummary(context, it.request)}"
            else "$status • $progress"
        } ?: context.getString(R.string.notification_preparing_download)
        return buildProgressNotification(context, job, title, status, summary, openIntent)
    }

    /**
     * The ordinary progress notification. It is used on every API level: the platform owns the
     * rendering, and the app only supplies the real percentage, bytes and cancellation action.
     */
    internal fun buildProgressNotification(
        context: Context,
        job: DownloadJob?,
        title: String,
        status: String,
        summary: String,
        openIntent: PendingIntent,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.vrka_purple))
            .setContentTitle(title)
            .setContentText(summary)
            // The shade already labels the notification with the app name; repeating it
            // here produced "VRKA • VRKA • Downloading".
            .setSubText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Without this, Android 12+ may defer the first foreground notification by up to
            // ten seconds.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (job != null) {
            val percent = determinateProgressPercent(job.state, job.progress)
            if (percent != null) {
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
            builder.addAction(
                R.drawable.ic_close,
                context.getString(R.string.btn_cancel),
                cancelIntent(context, job),
            )
        }
        return builder.build()
    }

    fun buildInterrupted(context: Context): Notification {
        val openIntent = openQueueIntent(context)
        val text = context.getString(R.string.notification_downloads_interrupted_text)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.vrka_purple))
            .setContentTitle(context.getString(R.string.notification_downloads_interrupted_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_download, context.getString(R.string.btn_open_queue), openIntent)
            .build()
    }

    private fun cancelIntent(context: Context, job: DownloadJob): PendingIntent = PendingIntent.getService(
        context,
        job.id.hashCode(),
        Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
            .putExtra(DownloadService.EXTRA_JOB_ID, job.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openQueueIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
