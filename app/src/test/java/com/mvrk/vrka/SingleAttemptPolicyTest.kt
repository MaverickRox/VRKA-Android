package com.mvrk.vrka

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the user's explicit no-automatic-retry requirement.
 *
 * The behavioural contract is covered by [DownloadRequestFactory.shouldAttemptBrowserFallback]
 * and the failure classifier tests. This test additionally asserts that the manager source no
 * longer contains a retry loop or retry status presentation, so a future change cannot quietly
 * reintroduce a second or third automatic native attempt.
 */
class SingleAttemptPolicyTest {

    @Test
    fun `one automatic native attempt is the configured contract`() {
        assertEquals(1, MAX_NATIVE_TRANSFER_ATTEMPTS)
    }

    @Test
    fun `browser fallback is entered after the first classified failure`() {
        val request = DownloadRequest(url = "https://example.test/watch")
        assertTrue(
            DownloadRequestFactory.shouldAttemptBrowserFallback(
                request = request,
                hasFailure = true,
                cancelled = false,
            ),
        )
        assertFalse(
            DownloadRequestFactory.shouldAttemptBrowserFallback(
                request = request,
                hasFailure = true,
                cancelled = true,
            ),
        )
    }

    @Test
    fun `manager has no automatic retry loop or retry countdown`() {
        val candidates = listOf(
            File("src/main/java/com/mvrk/vrka/VrkaDownloadManager.kt"),
            File("app/src/main/java/com/mvrk/vrka/VrkaDownloadManager.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }
        assertTrue("manager source not found in ${candidates.map { it.absolutePath }}", source != null)
        val text = source!!.readText()
        assertFalse("RetryPolicy must not be referenced", text.contains("RetryPolicy"))
        assertFalse("retry countdown must not be presented", text.contains("Retrying in"))
        assertFalse("attempt counters must not be presented", text.contains("attempt ${'$'}{retriesUsed"))
        assertFalse("automatic retry loop must not exist", text.contains("retriesUsed <"))
    }
}
