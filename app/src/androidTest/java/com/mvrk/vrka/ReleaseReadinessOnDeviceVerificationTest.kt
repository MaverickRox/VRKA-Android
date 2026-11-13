package com.mvrk.vrka

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvrk.vrka.update.AppUpdateCheckState
import com.mvrk.vrka.update.AppUpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseReadinessOnDeviceVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testAppUpdaterResolvesUpToDateWithoutDowngradeError(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val updateManager = AppUpdateManager.getInstance(app, app.downloads.settingsRepository)

        // Clear any previous state
        updateManager.resetState()

        // Trigger manual update check
        updateManager.checkForUpdate(isManual = true)

        // Await terminal check state within 25 seconds
        val finalState = withTimeout(25_000L) {
            updateManager.checkState.first { state ->
                state is AppUpdateCheckState.UpToDate ||
                    state is AppUpdateCheckState.UpdateAvailable ||
                    state is AppUpdateCheckState.Error
            }
        }

        // On v5.1.5 (versionCode 50105), remote repository holding v5.1.4 (50104) must resolve to UpToDate
        // It must NOT fail with "Version code downgrade rejected: 50104 < installed 50105"
        if (finalState is AppUpdateCheckState.Error) {
            assertFalse(
                "In-app update check must not fail with downgrade error: ${finalState.message}",
                finalState.message.contains("downgrade rejected") || finalState.message.contains("50104 < installed 50105"),
            )
        }
        assertTrue(
            "Expected UpToDate state for current installed release v5.1.5, got: $finalState",
            finalState is AppUpdateCheckState.UpToDate,
        )
        val upToDate = finalState as AppUpdateCheckState.UpToDate
        assertEquals("5.1.5", upToDate.currentVersion)
    }

    @Test
    fun testPlaylistDiagnosticNotRecordedAsFailure(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        manager.clearDiagnostics()

        // Create a playlist download request
        val request = DownloadRequest(
            url = "https://youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI",
            isPlaylist = true,
        )

        // Preflight estimate for flat playlist produces unavailable with detail starting with "playlist"
        val playlistEstimate = SizeEstimate.unavailable("playlist-entry")

        // Render DownloadConfirmationDialog directly with the playlist request and estimate
        composeRule.setContent {
            VrkaTheme(amoled = false) {
                DownloadConfirmationDialog(
                    request = request,
                    estimate = playlistEstimate,
                    estimatePending = false,
                    showEstimate = true,
                    needsLocation = false,
                    destinationUri = "",
                    setAsDefault = false,
                    skipFutureEstimates = false,
                    onToggleSkipFuture = {},
                    onChooseLocation = {},
                    onToggleSetDefault = {},
                    onRetryEstimate = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // Verify informational text is displayed instead of "Size unavailable" or retry button
        composeRule.onNodeWithText("Estimated per item").assertIsDisplayed()
        composeRule.onNodeWithText("Playlist items will be estimated and downloaded individually in queue.").assertIsDisplayed()

        // Verify diagnostic store remains clean (0 recorded failures)
        val finalDiagnostics = manager.diagnostics.first()
        assertEquals("Diagnostic store must NOT record playlist size preflight failure", 0, finalDiagnostics.size)
    }
}
