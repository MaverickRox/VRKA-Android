package com.mvrk.vrka

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    private var handingOffToTransferJob = false

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
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
        if (!handingOffToTransferJob) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            clearJobNotifications()
        }
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
            notificationManager.notify(
                notificationIdFor(job.id),
                DownloadNotifications.build(this, job),
            )
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
        val action = DownloadRecovery.foregroundTimeoutAction(
            DownloadTransferRuntime.isTransferJobRunning,
        )
        if (action == ForegroundTimeoutAction.HANDOFF) {
            // Keep the current notification: the user-initiated transfer job takes ownership
            // of progress and cancellation without reporting a false interruption.
            handingOffToTransferJob = true
            stopForeground(STOP_FOREGROUND_DETACH)
            DownloadTransferRuntime.requestNotificationRefresh()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            clearJobNotifications()
            notificationManager.notify(
                INTERRUPTED_NOTIFICATION_ID,
                DownloadNotifications.buildInterrupted(this),
            )
        }
        stopSelf()
    }

    private fun showForeground(job: DownloadJob?) {
        val id = job?.let { notificationIdFor(it.id) } ?: PENDING_NOTIFICATION_ID
        if (foregroundNotificationId != null && foregroundNotificationId != id) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        val notification = DownloadNotifications.build(this, job)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
        foregroundNotificationId = id
        postedJobNotifications.add(id)
    }

    companion object {
        const val ACTION_CANCEL = "com.mvrk.vrka.CANCEL"
        const val ACTION_STOP_IF_IDLE = "com.mvrk.vrka.STOP_IF_IDLE"
        const val EXTRA_JOB_ID = "job_id"

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
