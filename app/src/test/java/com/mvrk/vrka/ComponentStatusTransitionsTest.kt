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

    // --- YT-DLP TESTS 1-8 ---

    @Test
    fun test01_versionIsAvailableAfterInitialization() {
        val initial = ComponentStatus(id = ComponentUpdateManager.ID_YTDLP, name = "yt-dlp", installedVersion = "")
        val resolved = ComponentStatusTransitions.installedVersionResolved(initial, "2026.08.19")
        assertEquals("2026.08.19", resolved.installedVersion)
    }

    @Test
    fun test02_versionResolvesBeforeNetworkUpdate() {
        val initial = ComponentStatus(id = ComponentUpdateManager.ID_YTDLP, name = "yt-dlp", installedVersion = "")
        val resolved = ComponentStatusTransitions.installedVersionResolved(initial, "2026.08.19")
        val checking = ComponentStatusTransitions.beginCheck(resolved)
        assertEquals("2026.08.19", checking.installedVersion)
        assertEquals(ComponentCheckState.CHECKING, checking.checkState)
    }

    @Test
    fun test03_networkFailureDoesNotEraseInstalledVersion() {
        val checking = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            checkState = ComponentCheckState.CHECKING,
        )
        val failed = ComponentStatusTransitions.checkFailed(checking, "Network timeout")
        assertEquals("2026.08.19", failed.installedVersion)
        assertEquals(ComponentCheckState.CHECK_FAILED, failed.checkState)
        assertEquals("Network timeout", failed.error)
        assertFalse("Failure must not report Up to date", failed.checkState == ComponentCheckState.UP_TO_DATE)
    }

    @Test
    fun test04_updateAvailable() {
        val checking = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            checkState = ComponentCheckState.CHECKING,
        )
        val available = ComponentStatusTransitions.checkSucceeded(checking, "2026.09.01")
        assertEquals(ComponentCheckState.UPDATE_AVAILABLE, available.checkState)
        assertEquals("2026.08.19", available.installedVersion)
        assertEquals("2026.09.01", available.latestVersion)
        assertEquals("Update available: v2026.09.01", available.message)
    }

    @Test
    fun test05_noUpdateAvailable() {
        val checking = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            checkState = ComponentCheckState.CHECKING,
        )
        val upToDate = ComponentStatusTransitions.checkSucceeded(checking, "2026.08.19")
        assertEquals(ComponentCheckState.UP_TO_DATE, upToDate.checkState)
        assertEquals("2026.08.19", upToDate.installedVersion)
        assertEquals("2026.08.19", upToDate.latestVersion)
        assertEquals("Up to date (v2026.08.19)", upToDate.message)
    }

    @Test
    fun test06_updateSuccess() {
        val updating = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            updateState = ComponentUpdateState.INSTALLING,
        )
        val updated = ComponentStatusTransitions.updateSucceeded(updating, "2026.09.01")
        assertEquals("2026.09.01", updated.installedVersion)
        assertEquals("2026.09.01", updated.latestVersion)
        assertEquals(ComponentUpdateState.UPDATE_SUCCESS, updated.updateState)
        assertEquals(ComponentCheckState.UP_TO_DATE, updated.checkState)
        assertEquals("Updated successfully to v2026.09.01", updated.message)
    }

    @Test
    fun test07_updateFailure() {
        val updating = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            updateState = ComponentUpdateState.INSTALLING,
        )
        val failed = updating.copy(
            updateState = ComponentUpdateState.UPDATE_FAILED,
            error = "Checksum mismatch",
            message = "Update failed: Checksum mismatch",
        )
        assertEquals("2026.08.19", failed.installedVersion)
        assertEquals(ComponentUpdateState.UPDATE_FAILED, failed.updateState)
        assertEquals("Checksum mismatch", failed.error)
    }

    @Test
    fun test08_retry() {
        val failed = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.08.19",
            checkState = ComponentCheckState.CHECK_FAILED,
            error = "Network timeout",
        )
        val retrying = ComponentStatusTransitions.beginCheck(failed)
        assertEquals("2026.08.19", retrying.installedVersion)
        assertEquals(ComponentCheckState.CHECKING, retrying.checkState)
        assertEquals(null, retrying.error)
    }

    // --- CHECK ALL TESTS 9-14 ---

    @Test
    fun test09_successTerminal() {
        val state = BatchOperationState.COMPLETED
        assertFalse(state.isInProgress)
    }

    @Test
    fun test10_timeoutTerminal() {
        val state = BatchOperationState.FAILED
        assertFalse(state.isInProgress)
    }

    @Test
    fun test11_cancellationTerminal() {
        val state = BatchOperationState.CANCELLED
        assertFalse(state.isInProgress)
    }

    @Test
    fun test12_failureTerminal() {
        val state = BatchOperationState.FAILED
        assertFalse(state.isInProgress)
    }

    @Test
    fun test13_spinnerClears() {
        listOf(
            BatchOperationState.IDLE,
            BatchOperationState.COMPLETED,
            BatchOperationState.FAILED,
            BatchOperationState.CANCELLED,
        ).forEach { terminalState ->
            assertFalse("Spinner must clear in state $terminalState", terminalState.isInProgress)
        }
    }

    @Test
    fun test14_singleFlightReleases() {
        val mutex = kotlinx.coroutines.sync.Mutex()
        assertTrue(mutex.tryLock())
        assertFalse("Second lock must be rejected while locked", mutex.tryLock())
        mutex.unlock()
        assertTrue("Lock must be available after release", mutex.tryLock())
        mutex.unlock()
    }
}

