package com.mvrk.vrka

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal const val DOWNLOAD_TRANSFER_JOB_ID = 4106

/**
 * Process state shared by the foreground service and the user-initiated transfer worker.
 */
internal object DownloadTransferRuntime {
    @Volatile
    private var transferJobRunning = false

    // Set before the download manager first touches persisted state. A running job is not
    // necessarily reported by getPendingJob(), so this is the reliable recovery signal.
    @Volatile
    private var transferJobInvoked = false

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val notificationRefreshRequests = refreshRequests.asSharedFlow()

    val isTransferJobRunning: Boolean
        get() = transferJobRunning

    val wasTransferJobInvoked: Boolean
        get() = transferJobInvoked

    fun setTransferJobRunning(running: Boolean) {
        transferJobRunning = running
    }

    fun markTransferJobInvoked() {
        transferJobInvoked = true
    }

    /** Asks the worker to repost its notification immediately (for example after FGS handoff). */
    fun requestNotificationRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}

/**
 * Schedules one user-initiated data transfer job while downloads are active on Android 14+.
 * The job acts as the process-lifetime anchor and notification owner so a transfer can
 * outlive the six-hour dataSync foreground-service window on Android 15+.
 */
internal object DownloadTransferScheduler {
    // Large enough for playlists; the system treats this as a scheduling hint, not a cap.
    private const val MAX_ESTIMATED_BYTES = 64L * 1024L * 1024L * 1024L

    fun schedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        if (scheduler.getPendingJob(DOWNLOAD_TRANSFER_JOB_ID) != null) return true
        val info = JobInfo.Builder(
            DOWNLOAD_TRANSFER_JOB_ID,
            ComponentName(context, DownloadTransferJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(1L, MAX_ESTIMATED_BYTES)
            .setPersisted(false)
            .build()
        return runCatching { scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS }
            .onFailure { Log.w("VRKA", "Could not schedule user-initiated transfer job", it) }
            .getOrDefault(false)
    }

    fun cancel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.getSystemService(JobScheduler::class.java)?.cancel(DOWNLOAD_TRANSFER_JOB_ID)
        }
    }

    fun isScheduled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            context.getSystemService(JobScheduler::class.java)
                ?.getPendingJob(DOWNLOAD_TRANSFER_JOB_ID) != null
        }.getOrDefault(false)
    }
}
