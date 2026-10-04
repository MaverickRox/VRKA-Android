package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentStatusPresentationTest {
    @Test
    fun uncheckedComponentDoesNotAppearReady() {
        val status = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "Unknown",
        )

        assertEquals("Not checked", ComponentStatusPresentation.from(status).label)
        assertEquals("Unknown", ComponentStatusPresentation.versionLabel(status.installedVersion))
    }

    @Test
    fun onlySuccessfulCheckShowsUpToDate() {
        val checked = ComponentStatus(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp",
            installedVersion = "2026.10.01",
            latestVersion = "2026.10.01",
            checkState = ComponentCheckState.UP_TO_DATE,
        )

        assertEquals("Up to date", ComponentStatusPresentation.from(checked).label)
        assertEquals("v2026.10.01", ComponentStatusPresentation.versionLabel(checked.installedVersion))
    }

    @Test
    fun availableUpdateAndFailedCheckHaveDistinctLabels() {
        val available = ComponentStatus(
            id = ComponentUpdateManager.ID_UBLOCK,
            name = "uBlock Origin",
            installedVersion = "1.74.0",
            latestVersion = "1.75.0",
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
        )
        val failed = available.copy(checkState = ComponentCheckState.CHECK_FAILED, latestVersion = null)

        assertEquals("Update available", ComponentStatusPresentation.from(available).label)
        assertEquals("Error", ComponentStatusPresentation.from(failed).label)
    }
}
