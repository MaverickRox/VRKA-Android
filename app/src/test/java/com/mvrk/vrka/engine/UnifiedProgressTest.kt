package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

class UnifiedProgressTest {

    @Test
    fun `safePercentage never returns zero percent when downloaded bytes are positive`() {
        val zeroBytes = UnifiedProgressSnapshot(
            downloadedBytes = 0L,
            percentage = 0f
        )
        assertEquals(0f, zeroBytes.safePercentage)

        val positiveBytesZeroPct = UnifiedProgressSnapshot(
            downloadedBytes = 1048576L, // 1 MB
            percentage = 0f
        )
        // Must be bumped to at least 1%
        assertEquals(1f, positiveBytesZeroPct.safePercentage)

        val positiveBytesCalculatedZero = UnifiedProgressSnapshot(
            downloadedBytes = 1000L,
            totalBytes = 1000000000L // 0.0001%
        )
        assertEquals(1f, positiveBytesCalculatedZero.safePercentage)

        val positiveBytesNormal = UnifiedProgressSnapshot(
            downloadedBytes = 500L,
            totalBytes = 1000L
        )
        assertEquals(50f, positiveBytesNormal.safePercentage)
    }

    @Test
    fun `formatCardStatusText formats complete metrics with total bytes`() {
        val snapshot = UnifiedProgressSnapshot(
            downloadedBytes = 324L * 1024L * 1024L,
            totalBytes = 1200L * 1024L * 1024L,
            bytesPerSecond = 3800000L,
            percentage = 27f
        )
        val text = snapshot.formatCardStatusText()
        assertTrue("Expected 324.0 MB / 1.2 GB in: $text", text.contains("324.0 MB / 1.2 GB"))
        assertTrue("Expected 3.6 MB/s or speed in: $text", text.contains("MB/s"))
        assertTrue("Expected (27%) in: $text", text.contains("(27%)"))
    }

    @Test
    fun `formatCardStatusText formats stream without total bytes and never shows bare multiplier alone`() {
        val snapshot = UnifiedProgressSnapshot(
            downloadedBytes = 45L * 1024L * 1024L,
            totalBytes = null,
            bytesPerSecond = 4200000L,
            realtimeMultiplier = 10.7
        )
        val text = snapshot.formatCardStatusText()
        assertTrue("Expected 45.0 MB in: $text", text.contains("45.0 MB"))
        assertTrue("Expected speed in: $text", text.contains("MB/s"))
        assertTrue("Expected 10.7x in: $text", text.contains("10.7x"))
        assertNotEquals("10.7x", text) // Never bare 10.7x alone!
    }

    @Test
    fun `formatCardStatusText formats segment progress properly`() {
        val snapshot = UnifiedProgressSnapshot(
            downloadedBytes = 50L * 1024L * 1024L,
            segmentProgress = 15 to 120,
            bytesPerSecond = 2000000L
        )
        val text = snapshot.formatCardStatusText()
        assertTrue("Expected Segment 15/120 in: $text", text.contains("Segment 15/120"))
        assertTrue("Expected 50.0 MB in: $text", text.contains("50.0 MB"))
        assertTrue("Expected (13%) in: $text", text.contains("(13%)"))
    }
}
