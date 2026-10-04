package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentStatusTransitionsTest {
    private val components = listOf(
        Triple(ComponentUpdateManager.ID_YTDLP, "yt-dlp", "2026.08.19"),
        Triple(ComponentUpdateManager.ID_UBLOCK, "uBlock Origin", "1.74.0"),
    )

    @Test
    fun bothComponentsKeepInstalledVersionAcrossCheckAndRetryOutcomes() {
        components.forEach { (id, name, installed) ->
            val initial = ComponentStatus(id = id, name = name, installedVersion = installed)
            val checking = ComponentStatusTransitions.beginCheck(initial)
            assertEquals(installed, checking.installedVersion)
            assertEquals(ComponentCheckState.CHECKING, checking.checkState)

            val current = ComponentStatusTransitions.checkSucceeded(checking, installed)
            assertEquals(ComponentCheckState.UP_TO_DATE, current.checkState)
            assertEquals(installed, current.installedVersion)
            assertEquals(installed, current.latestVersion)

            val newer = if (id == ComponentUpdateManager.ID_YTDLP) "2026.08.20" else "1.75.0"
            val available = ComponentStatusTransitions.checkSucceeded(checking, newer)
            assertEquals(ComponentCheckState.UPDATE_AVAILABLE, available.checkState)
            assertEquals(installed, available.installedVersion)
            assertEquals(newer, available.latestVersion)

            val failed = ComponentStatusTransitions.checkFailed(checking, "Network timeout", checkedAt = 12L)
            assertEquals(ComponentCheckState.CHECK_FAILED, failed.checkState)
            assertEquals(installed, failed.installedVersion)
            assertEquals(null, failed.latestVersion)
            assertEquals("Network timeout", failed.error)

            val retried = ComponentStatusTransitions.beginCheck(failed)
            assertEquals(ComponentCheckState.CHECKING, retried.checkState)
            assertEquals(installed, retried.installedVersion)
            assertEquals(null, retried.error)
            assertEquals(ComponentCheckState.UP_TO_DATE, ComponentStatusTransitions.checkSucceeded(retried, installed).checkState)

            val updated = ComponentStatusTransitions.updateSucceeded(available, newer)
            assertEquals(newer, updated.installedVersion)
            assertEquals("Updated successfully to v$newer", updated.message)
            assertEquals(ComponentUpdateState.UPDATE_SUCCESS, updated.updateState)
        }
    }

    @Test
    fun localVersionRefreshClearsAStaleUnavailableVersionError() {
        val failed = ComponentStatusTransitions.checkFailed(
            ComponentStatus(
                id = ComponentUpdateManager.ID_YTDLP,
                name = "yt-dlp",
                installedVersion = "",
            ),
            ComponentStatusTransitions.INSTALLED_VERSION_UNAVAILABLE,
        )

        val refreshed = ComponentStatusTransitions.installedVersionResolved(failed, "2026.08.19")
        assertEquals("2026.08.19", refreshed.installedVersion)
        assertEquals(ComponentCheckState.CHECK_IDLE, refreshed.checkState)
        assertEquals("", refreshed.message)
        assertEquals(null, refreshed.error)
    }

    @Test
    fun updateCheckResultUsesOnePresentationModelForBothTextLines() {
        val updated = ComponentStatusTransitions.updateSucceeded(
            ComponentStatus(
                id = ComponentUpdateManager.ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = "1.74.0",
            ),
            "1.75.0",
        )

        val presentation = ComponentStatusPresentation.from(updated)
        assertEquals("Installed: v1.75.0", presentation.installedText)
        assertEquals("Updated successfully to v1.75.0", presentation.secondaryText)
    }

    @Test
    fun failedBatchStateIsTerminalAndLeavesSettingsInteractive() {
        assertFalse(BatchOperationState.FAILED.isInProgress)
        assertFalse(BatchOperationState.COMPLETED.isInProgress)
        assertFalse(BatchOperationState.CANCELLED.isInProgress)
        assertTrue(BatchOperationState.CHECKING.isInProgress)
        assertTrue(BatchOperationState.UPDATING.isInProgress)
    }
}
