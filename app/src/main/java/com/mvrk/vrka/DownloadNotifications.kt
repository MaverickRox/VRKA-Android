package com.mvrk.vrka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mvrk.vrka.R

/**
 * Single source of truth for download notification content. Both the foreground service and
 * the user-initiated transfer worker render the same notification ID and content, so a
 * handoff after the six-hour FGS window does not produce a duplicate notification.
 */
internal object DownloadNotifications {
    const val CHANNEL_ID = "vrka_downloads"

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
    }

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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdateNotification(context, job, title, status, summary, openIntent)
        } else {
            buildCompatNotification(context, job, title, status, summary, openIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildLiveUpdateNotification(
        context: Context,
        job: DownloadJob?,
        title: String,
        status: String,
        summary: String,
        openIntent: PendingIntent,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.vrka_purple))
            .setContentTitle(title)
            .setContentText(summary)
            .setSubText("VRKA • $status")
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (job == null) {
            builder.setStyle(Notification.BigTextStyle().bigText(summary))
            return builder.build()
        }

        val style = Notification.ProgressStyle()
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_download))
        val percent = determinateProgressPercent(job.state, job.progress)
        if (percent != null) {
            style.setProgress(percent)
            style.setProgressIndeterminate(false)
        } else {
            style.setProgressIndeterminate(true)
        }
        builder.setStyle(style)
        builder.addAction(cancelAction(context, job))
        // Promotion exists from 36.1; on 36.0 the Live Update style still applies.
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
            notificationManager?.canPostPromotedNotifications() == true
        ) {
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun buildCompatNotification(
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
            .setSubText("VRKA • $status")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

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

    private fun cancelAction(context: Context, job: DownloadJob): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_close),
            context.getString(R.string.btn_cancel),
            cancelIntent(context, job),
        ).build()

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
