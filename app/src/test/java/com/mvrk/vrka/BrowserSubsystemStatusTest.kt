package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowserSubsystemStatusTest {

    @Test
    fun freshInstallReportsBundledInstalledAndReady() {
        assertEquals(BrowserSubsystemState.BUNDLED, BrowserSubsystemStatus.engine(runtimeStarted = false))
        assertEquals(
            BrowserSubsystemState.INSTALLED_IDLE,
            BrowserSubsystemStatus.adBlocker(active = false, installedVersion = "1.60.0"),
        )
        assertEquals(
            BrowserSubsystemState.READY,
            BrowserSubsystemStatus.mediaDetection(active = false, bundled = true),
        )
        assertEquals(BrowserSubsystemState.IDLE, BrowserSubsystemStatus.session(active = false))
    }

    @Test
    fun activeFallbackReportsActiveOnlyFromRuntimeSignals() {
        assertEquals(BrowserSubsystemState.ACTIVE, BrowserSubsystemStatus.engine(runtimeStarted = true))
        assertEquals(
            BrowserSubsystemState.ACTIVE,
            BrowserSubsystemStatus.adBlocker(active = true, installedVersion = "1.60.0"),
        )
        assertEquals(
            BrowserSubsystemState.ACTIVE,
            BrowserSubsystemStatus.mediaDetection(active = true, bundled = true),
        )
        assertEquals(BrowserSubsystemState.ACTIVE, BrowserSubsystemStatus.session(active = true))
    }

    @Test
    fun artifactPresenceAloneIsNeverActive() {
        assertNotEquals(
            BrowserSubsystemState.ACTIVE,
            BrowserSubsystemStatus.adBlocker(active = false, installedVersion = "1.60.0"),
        )
        assertNotEquals(
            BrowserSubsystemState.ACTIVE,
            BrowserSubsystemStatus.mediaDetection(active = false, bundled = true),
        )
        assertNotEquals(BrowserSubsystemState.ACTIVE, BrowserSubsystemStatus.session(active = false))
        assertNotEquals(BrowserSubsystemState.ACTIVE, BrowserSubsystemStatus.engine(runtimeStarted = false))
    }

    @Test
    fun missingOrUnknownArtifactsAreUnavailable() {
        assertEquals(
            BrowserSubsystemState.UNAVAILABLE,
            BrowserSubsystemStatus.adBlocker(active = false, installedVersion = ""),
        )
        assertEquals(
            BrowserSubsystemState.UNAVAILABLE,
            BrowserSubsystemStatus.adBlocker(active = false, installedVersion = "Unknown"),
        )
        assertEquals(
            BrowserSubsystemState.UNAVAILABLE,
            BrowserSubsystemStatus.adBlocker(active = false, installedVersion = null),
        )
        assertEquals(
            BrowserSubsystemState.UNAVAILABLE,
            BrowserSubsystemStatus.mediaDetection(active = false, bundled = false),
        )
    }
}
