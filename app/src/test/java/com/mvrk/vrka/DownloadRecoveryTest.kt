package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecoveryTest {
    private fun job(
        id: String,
        state: JobState,
        publishStarted: Boolean = false,
    ) = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://example.com/$id"),
        state = state,
        publishStarted = publishStarted,
    )

    @Test
    fun queuedAndTransferringJobsResume() {
        val resumable = listOf(
            JobState.QUEUED,
            JobState.PREPARING,
            JobState.DOWNLOADING,
            JobState.BROWSER_FALLBACK,
        )

        resumable.forEach { state ->
            assertEquals(
                "$state must be resumable",
                DownloadRecoveryAction.RESUME,
                DownloadRecovery.actionFor(job("job-$state", state)),
            )
        }
    }

    @Test
    fun publishingBeforeAnyPublishIsResumable() {
        assertEquals(
            DownloadRecoveryAction.RESUME,
            DownloadRecovery.actionFor(job("post", JobState.POSTPROCESSING, publishStarted = false)),
        )
    }

    @Test
    fun publishingAfterOutputHandoffFailsClosed() {
        assertEquals(
            "A job that may already have published a file must not run again automatically",
            DownloadRecoveryAction.INTERRUPT,
            DownloadRecovery.actionFor(job("publishing", JobState.POSTPROCESSING, publishStarted = true)),
        )
    }

    @Test
    fun waitingForUserIsNeverStartedAutomatically() {
        assertEquals(
            DownloadRecoveryAction.KEEP_WAITING,
            DownloadRecovery.actionFor(job("waiting", JobState.WAITING_FOR_USER)),
        )
    }

    @Test
    fun terminalJobsAreNeverRestarted() {
        listOf(JobState.DONE, JobState.FAILED, JobState.CANCELLED).forEach { state ->
            assertEquals(
                "$state must stay terminal",
                DownloadRecoveryAction.NONE,
                DownloadRecovery.actionFor(job("terminal-$state", state)),
            )
        }
    }

    @Test
    fun planDeduplicatesLogicalJobs() {
        val plan = DownloadRecovery.plan(
            listOf(
                job("same", JobState.DOWNLOADING),
                job("same", JobState.QUEUED),
                job("publishing", JobState.POSTPROCESSING, publishStarted = true),
                job("publishing", JobState.POSTPROCESSING, publishStarted = true),
                job("done", JobState.DONE),
            ),
        )

        assertEquals(listOf("same"), plan.resumeIds)
        assertEquals(listOf("publishing"), plan.interruptedIds)
    }

    @Test
    fun emptyPlanForOnlyFinishedOrWaitingWork() {
        val plan = DownloadRecovery.plan(
            listOf(
                job("done", JobState.DONE),
                job("failed", JobState.FAILED),
                job("waiting", JobState.WAITING_FOR_USER),
            ),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun userInitiatedTransfersRequireAndroid14() {
        assertFalse(DownloadRecovery.usesUserInitiatedTransfer(33))
        assertTrue(DownloadRecovery.usesUserInitiatedTransfer(34))
        assertTrue(DownloadRecovery.usesUserInitiatedTransfer(36))
    }

    @Test
    fun activeWorkIsRecoveredOnlyWithAPendingTransferJob() {
        assertFalse(
            "A plain cold start must not silently restart interrupted downloads",
            DownloadRecovery.shouldRecoverPersistedActiveWork(sdkInt = 36, transferJobScheduled = false),
        )
        assertTrue(
            DownloadRecovery.shouldRecoverPersistedActiveWork(sdkInt = 36, transferJobScheduled = true),
        )
        assertFalse(
            "Older platforms keep the explicit interrupted/retry behaviour",
            DownloadRecovery.shouldRecoverPersistedActiveWork(sdkInt = 33, transferJobScheduled = true),
        )
    }

    @Test
    fun foregroundTimeoutHandsOffOnlyWhenTheTransferJobRuns() {
        assertEquals(
            ForegroundTimeoutAction.HANDOFF,
            DownloadRecovery.foregroundTimeoutAction(transferJobRunning = true),
        )
        assertEquals(
            ForegroundTimeoutAction.INTERRUPT,
            DownloadRecovery.foregroundTimeoutAction(transferJobRunning = false),
        )
    }
}
