package com.mvrk.vrka

internal enum class DownloadRecoveryAction {
    RESUME,
    INTERRUPT,
    KEEP_WAITING,
    NONE,
}

internal data class DownloadRecoveryPlan(
    val resumeIds: List<String>,
    val interruptedIds: List<String>,
) {
    val isEmpty: Boolean
        get() = resumeIds.isEmpty() && interruptedIds.isEmpty()
}

internal enum class ForegroundTimeoutAction {
    HANDOFF,
    INTERRUPT,
}

/**
 * Decides which persisted jobs may continue automatically after a process restart.
 *
 * A transfer that already reached the publishing phase is never resumed automatically:
 * its outputs may exist in MediaStore or the selected folder, and running yt-dlp again
 * would publish a second copy. Such jobs fail closed and require an explicit user retry.
 */
internal object DownloadRecovery {
    private val RESUMABLE_STATES = setOf(
        JobState.QUEUED,
        JobState.PREPARING,
        JobState.DOWNLOADING,
        JobState.BROWSER_FALLBACK,
    )

    fun actionFor(job: DownloadJob): DownloadRecoveryAction = when {
        job.state.isTerminal -> DownloadRecoveryAction.NONE
        job.state == JobState.WAITING_FOR_USER -> DownloadRecoveryAction.KEEP_WAITING
        job.state == JobState.POSTPROCESSING -> {
            if (job.publishStarted) {
                DownloadRecoveryAction.INTERRUPT
            } else {
                DownloadRecoveryAction.RESUME
            }
        }
        job.state in RESUMABLE_STATES -> DownloadRecoveryAction.RESUME
        else -> DownloadRecoveryAction.INTERRUPT
    }

    fun plan(jobs: List<DownloadJob>): DownloadRecoveryPlan {
        val resume = LinkedHashSet<String>()
        val interrupted = LinkedHashSet<String>()
        for (job in jobs) {
            when (actionFor(job)) {
                DownloadRecoveryAction.RESUME -> resume += job.id
                DownloadRecoveryAction.INTERRUPT -> interrupted += job.id
                DownloadRecoveryAction.KEEP_WAITING,
                DownloadRecoveryAction.NONE,
                -> Unit
            }
        }
        return DownloadRecoveryPlan(resume.toList(), interrupted.toList())
    }

    /** User-initiated data transfer jobs exist from Android 14 (API 34). */
    fun usesUserInitiatedTransfer(sdkInt: Int): Boolean = sdkInt >= 34

    /**
     * Persisted in-flight work is only revived when a transfer job is actually pending.
     * A plain cold launch has no such guarantee, so those records stay failed.
     */
    fun shouldRecoverPersistedActiveWork(
        sdkInt: Int,
        transferJobScheduled: Boolean,
    ): Boolean = usesUserInitiatedTransfer(sdkInt) && transferJobScheduled

    /**
     * The dataSync foreground window ends after six hours on Android 15+. If a
     * user-initiated transfer job is already keeping the process alive, the transfer
     * continues and notifications hand off instead of being reported as interrupted.
     */
    fun foregroundTimeoutAction(transferJobRunning: Boolean): ForegroundTimeoutAction =
        if (transferJobRunning) ForegroundTimeoutAction.HANDOFF else ForegroundTimeoutAction.INTERRUPT
}
