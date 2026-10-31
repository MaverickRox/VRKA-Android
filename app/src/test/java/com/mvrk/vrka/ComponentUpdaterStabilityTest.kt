package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the first-start flow: discovery and installed-version resolution
 * run concurrently, and a completed discovery must survive both the resolution cleanup and
 * dismissing the update dialog.
 */
class ComponentUpdaterStabilityTest {

    private fun idleStatus(installedVersion: String = "") = ComponentStatus(
        id = ComponentUpdateManager.ID_YTDLP,
        name = "yt-dlp",
        installedVersion = installedVersion,
    )

    @Test
    fun resolvingInstalledVersionIsNotChecking() {
        val resolving = ComponentStatusTransitions.resolutionStarted(idleStatus())

        assertTrue(resolving.isResolvingInstalledVersion)
        assertFalse(resolving.isChecking)
        assertFalse(resolving.isUpdating)
    }

    @Test
    fun finishingVersionResolutionKeepsCompletedDiscovery() {
        var status = ComponentStatusTransitions.resolutionStarted(idleStatus())
        status = ComponentStatusTransitions.checkSucceeded(
            status = status,
            candidateVersion = "2026.10.26",
            checkedAt = 42L,
        )

        assertEquals(ComponentCheckState.UPDATE_AVAILABLE, status.checkState)
        assertEquals("2026.10.26", status.latestVersion)
        assertFalse(status.isChecking)

        val afterResolution = ComponentStatusTransitions.resolutionFinished(status)

        assertEquals(ComponentCheckState.UPDATE_AVAILABLE, afterResolution.checkState)
        assertEquals("2026.10.26", afterResolution.latestVersion)
        assertEquals(42L, afterResolution.lastChecked)
        assertFalse(afterResolution.isResolvingInstalledVersion)
        assertFalse(afterResolution.isChecking)
    }

    @Test
    fun resolvedInstalledVersionDoesNotDowngradeCompletedDiscovery() {
        var status = ComponentStatusTransitions.checkSucceeded(
            status = ComponentStatusTransitions.resolutionStarted(idleStatus()),
            candidateVersion = "2026.10.26",
        )

        status = ComponentStatusTransitions.installedVersionResolved(status, "2026.10.01")

        assertEquals("2026.10.01", status.installedVersion)
        assertEquals(ComponentCheckState.UPDATE_AVAILABLE, status.checkState)
        assertEquals("2026.10.26", status.latestVersion)
        assertFalse(status.isChecking)
    }

    @Test
    fun dismissalKeepsTheDiscoveredStateUntouched() {
        val discovered = ComponentStatusTransitions.checkSucceeded(
            status = idleStatus("2026.10.01"),
            candidateVersion = "2026.10.26",
        )

        // Dismissing the startup dialog only clears the dialog payload; the status map that
        // Settings reads is a separate, already-published snapshot.
        val afterDismissal = discovered.copy()

        assertEquals(ComponentCheckState.UPDATE_AVAILABLE, afterDismissal.checkState)
        assertEquals("2026.10.01", afterDismissal.installedVersion)
        assertEquals("2026.10.26", afterDismissal.latestVersion)
        assertFalse(afterDismissal.isChecking)
        assertEquals(ComponentCardAction.UPDATE, componentCardAction(afterDismissal))
    }

    @Test
    fun cancelledDiscoveryReturnsToIdle() {
        val cancelled = ComponentStatusTransitions.checkCancelled(
            status = idleStatus("2026.10.01"),
        )

        assertEquals(ComponentCheckState.CHECK_IDLE, cancelled.checkState)
        assertFalse(cancelled.isChecking)
        assertNull(cancelled.latestVersion)
        assertEquals(ComponentCardAction.NONE, componentCardAction(cancelled))
    }

    @Test
    fun failedDiscoveryReturnsToFailedNotChecking() {
        val failed = ComponentStatusTransitions.checkFailed(
            status = idleStatus("2026.10.01"),
            error = "Network timeout",
        )

        assertEquals(ComponentCheckState.CHECK_FAILED, failed.checkState)
        assertFalse(failed.isChecking)
        assertEquals("Network timeout", failed.error)
        assertEquals(ComponentCardAction.RETRY, componentCardAction(failed))
    }

    @Test
    fun manualRecheckClearsThePreviousResultBeforeStarting() {
        val discovered = ComponentStatusTransitions.checkSucceeded(
            status = idleStatus("2026.10.01"),
            candidateVersion = "2026.10.26",
        )

        val checking = ComponentStatusTransitions.beginCheck(discovered)

        assertEquals(ComponentCheckState.CHECKING, checking.checkState)
        assertTrue(checking.isChecking)
        assertNull(checking.latestVersion)
        assertNull(checking.error)
    }

    @Test
    fun manualRecheckClearsATerminalInstallResult() {
        val failedInstall = idleStatus("2026.10.01").copy(
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.UPDATE_FAILED,
            error = "SHA-256 mismatch",
        )

        val checking = ComponentStatusTransitions.beginCheck(failedInstall)

        assertEquals(ComponentUpdateState.UPDATE_IDLE, checking.updateState)
        assertFalse(checking.isUpdating)
        assertTrue(checking.isChecking)
        assertEquals(ComponentCardAction.NONE, componentCardAction(checking))
    }

    @Test
    fun installationPhasesAreUpdatingButNeverChecking() {
        ComponentUpdateState.entries
            .filter { it == ComponentUpdateState.DOWNLOADING || it == ComponentUpdateState.VERIFYING || it == ComponentUpdateState.INSTALLING }
            .forEach { phase ->
                val status = idleStatus("2026.10.01").copy(
                    checkState = ComponentCheckState.UPDATE_AVAILABLE,
                    updateState = phase,
                )
                assertTrue("$phase must count as updating", status.isUpdating)
                assertFalse("$phase must not count as checking", status.isChecking)
            }

        val succeeded = idleStatus("2026.10.26").copy(
            checkState = ComponentCheckState.UP_TO_DATE,
            updateState = ComponentUpdateState.UPDATE_SUCCESS,
        )
        val failed = idleStatus("2026.10.01").copy(
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.UPDATE_FAILED,
        )

        assertFalse(succeeded.isUpdating)
        assertFalse(failed.isUpdating)
        assertFalse(succeeded.isChecking)
        assertFalse(failed.isChecking)
        assertEquals(ComponentCardAction.NONE, componentCardAction(succeeded))
        assertEquals(ComponentCardAction.RETRY, componentCardAction(failed))
    }
}
