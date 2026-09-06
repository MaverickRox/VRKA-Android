package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentUpdateManagerTest {

    @Test
    fun dateBasedVersionComparisons() {
        // Newer release
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19", "2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("v2026.08.19", "2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19", "yt-dlp 2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19.1", "2026.08.19"))

        // Equal release
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.06.30", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("v2026.06.30", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.06.30", "yt-dlp 2026.06.30"))

        // Older release
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.05.01", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2025.12.31", "2026.06.30"))
    }

    @Test
    fun nightlyVersionNormalizationAndParity() {
        // Various release tag and banner formats
        val stableFormats = listOf(
            "v2026.08.19",
            "2026.08.19",
            "yt-dlp 2026.08.19",
        )
        // All forms of 2026.08.19 must extract the exact same numbers and compare equal
        for (f1 in stableFormats) {
            for (f2 in stableFormats) {
                assertFalse("Comparing $f1 vs $f2 should not report update", ComponentUpdateManager.isNewerVersion(f1, f2))
                assertEquals(
                    listOf(2026L, 8L, 19L),
                    ComponentUpdateManager.extractVersionNumbers(f1),
                )
            }
        }

        val nightlyFormats = listOf(
            "v2026.08.30.232658",
            "yt-dlp 2026.08.30.232658",
            "yt-dlp nightly 2026.08.30.232658",
            "nightly 2026.08.30.232658",
        )
        // All forms of nightly 2026.08.30.232658 must extract the exact same numbers and compare equal
        for (f1 in nightlyFormats) {
            for (f2 in nightlyFormats) {
                assertFalse("Comparing $f1 vs $f2 should not report update", ComponentUpdateManager.isNewerVersion(f1, f2))
                assertEquals(
                    listOf(2026L, 8L, 30L, 232658L),
                    ComponentUpdateManager.extractVersionNumbers(f1),
                )
            }
        }

        // Cross-format comparison: Candidate == Installed must yield false (not UPDATE_AVAILABLE)
        assertFalse(
            ComponentUpdateManager.isNewerVersion(
                candidate = "v2026.08.30.232658",
                installed = "yt-dlp nightly 2026.08.30.232658",
            ),
        )
        assertFalse(
            ComponentUpdateManager.isNewerVersion(
                candidate = "v2026.08.30.232658",
                installed = "nightly 2026.08.30.232658",
            ),
        )

        // Strict ordering
        assertTrue(ComponentUpdateManager.isNewerVersion("v2026.08.30.232658", "v2026.08.19"))
        assertTrue(ComponentUpdateManager.isNewerVersion("yt-dlp nightly 2026.08.30.232658", "yt-dlp 2026.08.19"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.08.19", "yt-dlp nightly 2026.08.30.232658"))

        // Prefix cleaning
        assertEquals("2026.08.19", ComponentUpdateManager.cleanVersionString("v2026.08.19"))
        assertEquals("2026.08.19", ComponentUpdateManager.cleanVersionString("yt-dlp 2026.08.19"))
        assertEquals("2026.08.30.232658", ComponentUpdateManager.cleanVersionString("yt-dlp nightly 2026.08.30.232658"))
        assertEquals("2026.08.30.232658", ComponentUpdateManager.cleanVersionString("nightly 2026.08.30.232658"))
    }

    @Test
    fun semVerComparisons() {
        // Newer
        assertTrue(ComponentUpdateManager.isNewerVersion("1.75.0", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.74.1", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2.0.0", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("v1.75.0", "1.74.0"))

        // Equal
        assertFalse(ComponentUpdateManager.isNewerVersion("1.74.0", "1.74.0"))
        assertFalse(ComponentUpdateManager.isNewerVersion("v1.74.0", "1.74.0"))

        // Older
        assertFalse(ComponentUpdateManager.isNewerVersion("1.73.9", "1.74.0"))
        assertFalse(ComponentUpdateManager.isNewerVersion("1.0.0", "1.74.0"))
    }

    @Test
    fun stateMachineOrthogonality() {
        val checkingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.CHECKING,
            updateState = ComponentUpdateState.UPDATE_IDLE,
        )
        assertTrue(checkingStatus.isChecking)
        assertFalse(checkingStatus.isUpdating)

        val downloadingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.DOWNLOADING,
        )
        assertFalse(downloadingStatus.isChecking)
        assertTrue(downloadingStatus.isUpdating)

        val verifyingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.VERIFYING,
        )
        assertFalse(verifyingStatus.isChecking)
        assertTrue(verifyingStatus.isUpdating)

        val successStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.1.0",
            checkState = ComponentCheckState.UP_TO_DATE,
            updateState = ComponentUpdateState.UPDATE_SUCCESS,
        )
        assertFalse(successStatus.isChecking)
        assertFalse(successStatus.isUpdating)
    }
}
