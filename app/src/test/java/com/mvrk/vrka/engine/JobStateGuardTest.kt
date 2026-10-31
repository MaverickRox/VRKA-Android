package com.mvrk.vrka.engine

import com.mvrk.vrka.JobState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStateGuardTest {

    @Test
    fun `terminal jobs reject non terminal state changes`() {
        for (terminal in listOf(JobState.DONE, JobState.FAILED, JobState.CANCELLED)) {
            assertFalse(JobStateGuard.allowsStateChange(terminal, JobState.DOWNLOADING))
            assertFalse(JobStateGuard.allowsStateChange(terminal, JobState.PREPARING))
            assertFalse(JobStateGuard.allowsMetadataUpdate(terminal))
            assertTrue(JobStateGuard.allowsStateChange(terminal, terminal))
        }
        assertTrue(JobStateGuard.allowsStateChange(JobState.DOWNLOADING, JobState.POSTPROCESSING))
        assertTrue(JobStateGuard.allowsStateChange(JobState.QUEUED, JobState.CANCELLED))
    }
}
