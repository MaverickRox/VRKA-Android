package com.mvrk.vrka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Reserved foreground id used before the first job is visible to the service.
internal const val PENDING_NOTIFICATION_ID = 4107

// Fallback notification posted when Android ends the data-sync foreground window.
internal const val INTERRUPTED_NOTIFICATION_ID = 4108

internal const val NOTIFICATION_ID_BASE = 4200

internal fun notificationIdFor(jobId: String): Int =
    NOTIFICATION_ID_BASE + (jobId.hashCode() and 0xFFFF)

internal fun determinateProgressPercent(state: JobState, progress: Float): Int? =
    if (state == JobState.DOWNLOADING && progress > 0f && progress.isFinite()) {
        progress.toInt().coerceIn(0, 100)
    } else {
        null
    }

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { applicationContext.vrkaApplication.downloads }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val postedJobNotifications = mutableSetOf<Int>()
    private var foregroundNotificationId: Int? = null
    private var foregroundTimedOut = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        showForeground(manager.jobs.value.firstOrNull { it.state.isForegroundWork })
        serviceScope.launch {
            manager.jobs.collectLatest(::syncNotifications)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let(manager::cancel)
                syncNotifications(manager.jobs.value)
            }
            ACTION_STOP_IF_IDLE -> {
                if (manager.jobs.value.none { it.state.isForegroundWork }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    clearJobNotifications()
                    stopSelf()
                } else {
                    syncNotifications(manager.jobs.value)
                }
                return START_NOT_STICKY
            }
            else -> showForeground(manager.jobs.value.firstOrNull { it.state.isForegroundWork })
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleForegroundTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        clearJobNotifications()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncNotifications(jobs: List<DownloadJob>) {
        if (foregroundTimedOut) return
        val active = jobs.filter { it.state.isForegroundWork }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            clearJobNotifications()
            stopSelf()
            return
        }
        showForeground(active.first())
        active.drop(1).forEach { job ->
            notificationManager.notify(notificationIdFor(job.id), buildNotification(job))
        }
        val activeIds = active.mapTo(mutableSetOf()) { notificationIdFor(it.id) }
        postedJobNotifications.filterNot(activeIds::contains).forEach(notificationManager::cancel)
        postedJobNotifications.clear()
        postedJobNotifications.addAll(activeIds)
    }

    private fun clearJobNotifications() {
        postedJobNotifications.forEach(notificationManager::cancel)
        postedJobNotifications.clear()
        foregroundNotificationId = null
    }

    private fun handleForegroundTimeout() {
        foregroundTimedOut = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        clearJobNotifications()
        notificationManager.notify(INTERRUPTED_NOTIFICATION_ID, buildInterruptedNotification())
        stopSelf()
    }

    private fun showForeground(job: DownloadJob?) {
        val id = job?.let { notificationIdFor(it.id) } ?: PENDING_NOTIFICATION_ID
        if (foregroundNotificationId != null && foregroundNotificationId != id) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        val notification = buildNotification(job)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
        foregroundNotificationId = id
        postedJobNotifications.add(id)
    }

    private fun buildNotification(job: DownloadJob?): Notification {
        val openIntent = openQueueIntent()
        val title = job?.title?.ifBlank { getString(R.string.active_download_title) }
            ?: getString(R.string.app_name)
        val status = job?.let { jobStatusLabel(this, it) } ?: getString(R.string.status_preparing)
        val summary = job?.let {
            val progress = jobProgressSummary(this, it)
            if (progress == it.detail) "$status • ${requestSummary(this, it.request)}"
            else "$status • $progress"
        } ?: getString(R.string.notification_preparing_download)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdateNotification(job, title, status, summary, openIntent)
        } else {
            buildCompatNotification(job, title, status, summary, openIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildLiveUpdateNotification(
        job: DownloadJob?,
        title: String,
        status: String,
        summary: String,
        openIntent: PendingIntent,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.vrka_purple))
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
            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_download))
        val percent = determinateProgressPercent(job.state, job.progress)
        if (percent != null) {
            style.setProgress(percent)
            style.setProgressIndeterminate(false)
        } else {
            style.setProgressIndeterminate(true)
        }
        builder.setStyle(style)
        builder.addAction(cancelAction(job))
        // Promotion exists from 36.1; on 36.0 the Live Update style still applies.
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
            notificationManager.canPostPromotedNotifications()
        ) {
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun buildCompatNotification(
        job: DownloadJob?,
        title: String,
        status: String,
        summary: String,
        openIntent: PendingIntent,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.vrka_purple))
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
                getString(R.string.btn_cancel),
                cancelIntent(job),
            )
        }
        return builder.build()
    }

    private fun buildInterruptedNotification(): Notification {
        val openIntent = openQueueIntent()
        val text = getString(R.string.notification_downloads_interrupted_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.vrka_purple))
            .setContentTitle(getString(R.string.notification_downloads_interrupted_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_download, getString(R.string.btn_open_queue), openIntent)
            .build()
    }

    private fun cancelAction(job: DownloadJob): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_close),
            getString(R.string.btn_cancel),
            cancelIntent(job),
        ).build()

    private fun cancelIntent(job: DownloadJob): PendingIntent = PendingIntent.getService(
        this,
        job.id.hashCode(),
        Intent(this, DownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_JOB_ID, job.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openQueueIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_active_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_active_downloads_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vrka_downloads"
        private const val ACTION_CANCEL = "com.mvrk.vrka.CANCEL"
        private const val ACTION_STOP_IF_IDLE = "com.mvrk.vrka.STOP_IF_IDLE"
        private const val EXTRA_JOB_ID = "job_id"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIfIdle(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_STOP_IF_IDLE),
            )
        }
    }
}
