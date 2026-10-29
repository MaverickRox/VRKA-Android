package com.mvrk.vrka.engine

import com.mvrk.vrka.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `first retry delay uses base without jitter`() {
        assertEquals(RetryPolicy.BASE_DELAY_MS, RetryPolicy.delayForRetry(1) { 0.5 })
    }

    @Test
    fun `delay grows exponentially and is capped`() {
        assertEquals(4_000L, RetryPolicy.delayForRetry(2) { 0.5 })
        assertEquals(8_000L, RetryPolicy.delayForRetry(3) { 0.5 })
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayForRetry(20) { 0.5 })
    }

    @Test
    fun `jitter stays within configured bounds`() {
        val low = RetryPolicy.delayForRetry(1) { 0.0 }
        val high = RetryPolicy.delayForRetry(1) { 1.0 }
        assertEquals(1_500L, low)
        assertEquals(2_500L, high)
        assertTrue(low <= RetryPolicy.BASE_DELAY_MS)
        assertTrue(high >= RetryPolicy.BASE_DELAY_MS)
    }

    @Test
    fun `transient network failures are retryable`() {
        assertTrue(RetryPolicy.decide(FailureCategory.TIMEOUT, "read operation timed out", false, false).retryable)
        assertTrue(RetryPolicy.decide(FailureCategory.NETWORK_ERROR, "connection reset by peer", false, false).retryable)
        assertTrue(RetryPolicy.decide(FailureCategory.HTTP, "HTTP Error 429: Too Many Requests", false, false).retryable)
        assertTrue(RetryPolicy.decide(FailureCategory.HTTP, "HTTP Error 503: Service Unavailable", false, false).retryable)
        assertTrue(RetryPolicy.decide(FailureCategory.CLOUDFLARE, "Just a moment...", false, false).retryable)
    }

    @Test
    fun `permanent rejection is not retried automatically`() {
        assertFalse(RetryPolicy.decide(FailureCategory.HTTP, "HTTP Error 403: Forbidden", false, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.HTTP, "HTTP Error 404: Not Found", false, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.NETWORK_ERROR, "Name or service not known", false, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.DRM, "DRM protected", false, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.UNSUPPORTED, "Unsupported URL", false, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.STORAGE_ERROR, "No space left on device", false, false).retryable)
    }

    @Test
    fun `transferred or resolved downloads are never retried`() {
        assertFalse(RetryPolicy.decide(FailureCategory.TIMEOUT, "timed out", true, false).retryable)
        assertFalse(RetryPolicy.decide(FailureCategory.HTTP, "HTTP Error 503", false, true).retryable)
    }

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
