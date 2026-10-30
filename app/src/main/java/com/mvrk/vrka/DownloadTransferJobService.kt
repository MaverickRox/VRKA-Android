package com.mvrk.vrka

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * User-initiated data transfer job (Android 14+). It keeps the process alive for transfers
 * that outlive the dataSync foreground-service window, owns the notification after handoff,
 * and provides the durable recovery trigger after process death.
 */
// The transfer anchor uses a fixed VRKA job id. A theoretical WorkManager collision only
// delays one scheduler (both retry), and transfers still fall back to the foreground service.
@SuppressLint("SpecifyJobSchedulerIdRange")
class DownloadTransferJobService : JobService() {
    // Recreated per job: a JobService instance may be reused after jobFinished().
    private var jobScope: CoroutineScope? = null
    private var jobParameters: JobParameters? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeNotificationIds: List<Int> = emptyList()
    private var postedNotificationIds = mutableSetOf<Int>()
    private var lastPostedAt = 0L

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            jobFinished(params, false)
            return false
        }
        jobParameters = params
        DownloadTransferRuntime.setTransferJobRunning(true)
        DownloadTransferRuntime.markTransferJobInvoked()
        acquireWakeLock()
        val manager = applicationContext.vrkaApplication.downloads
        // A user-initiated transfer job must associate a notification immediately, even if
        // recovery decides that the remaining job only needs user input.
        runCatching {
            setNotification(
                params,
                PENDING_NOTIFICATION_ID,
                DownloadNotifications.build(this, null),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        postNotification(params, manager.jobs.value, force = true)
        jobScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        jobScope = scope
        scope.launch {
            manager.jobs.collectLatest { jobs ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@collectLatest
                if (jobs.none { it.state.isForegroundWork }) {
                    finish(params, jobs)
                    return@collectLatest
                }
                postNotification(params, jobs, force = false)
            }
        }
        scope.launch {
            DownloadTransferRuntime.notificationRefreshRequests.collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    postNotification(params, manager.jobs.value, force = true)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        DownloadTransferRuntime.setTransferJobRunning(false)
        releaseWakeLock()
        jobParameters = null
        jobScope?.cancel()
        jobScope = null
        // Ask the system to bring the anchor back if downloads are still logically active.
        return runCatching {
            applicationContext.vrkaApplication.downloads.jobs.value.any { it.state.isForegroundWork }
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        jobScope?.cancel()
        jobScope = null
        DownloadTransferRuntime.setTransferJobRunning(false)
        releaseWakeLock()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun postNotification(
        params: JobParameters,
        jobs: List<DownloadJob>,
        force: Boolean,
    ) {
        val active = jobs.filter { it.state.isForegroundWork }
        if (active.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val activeIds = active.map { notificationIdFor(it.id) }
        val activeSetChanged = activeIds != activeNotificationIds
        if (!force && !activeSetChanged && now - lastPostedAt < REFRESH_INTERVAL_MS) return

        val primary = active.first()
        runCatching {
            setNotification(
                params,
                notificationIdFor(primary.id),
                DownloadNotifications.build(this, primary),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        active.drop(1).forEach { job ->
            runCatching {
                notificationManager?.notify(
                    notificationIdFor(job.id),
                    DownloadNotifications.build(this, job),
                )
            }
        }
        if (activeSetChanged) {
            (postedNotificationIds - activeIds.toSet()).forEach { notificationManager?.cancel(it) }
        }
        postedNotificationIds.clear()
        postedNotificationIds.addAll(activeIds)
        activeNotificationIds = activeIds
        lastPostedAt = now
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun finish(params: JobParameters, jobs: List<DownloadJob>) {
        if (jobParameters !== params) return
        jobParameters = null
        releaseWakeLock()
        DownloadTransferRuntime.setTransferJobRunning(false)
        val notificationManager = getSystemService(NotificationManager::class.java)
        (jobs.map { notificationIdFor(it.id) } + PENDING_NOTIFICATION_ID).forEach {
            notificationManager?.cancel(it)
        }
        postedNotificationIds.clear()
        activeNotificationIds = emptyList()
        jobFinished(params, false)
        jobScope?.cancel()
        jobScope = null
    }

    // A fixed timeout would cut off long transfers; the lock is bounded by job end instead
    // and always released in onStopJob/onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private companion object {
        const val WAKE_LOCK_TAG = "VRKA:downloadTransfer"
        const val REFRESH_INTERVAL_MS = 2_000L
    }
}
