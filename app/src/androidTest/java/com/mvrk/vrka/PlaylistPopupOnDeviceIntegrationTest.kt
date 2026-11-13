package com.mvrk.vrka

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaylistPopupOnDeviceIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val compositeUrl = "https://www.youtube.com/watch?v=kYJ3s4f_N74&list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"
    private val compositeShortUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"
    private val purePlaylistUrl = "https://youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI"
    private val ordinaryVideoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Before
    fun setUp(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val currentJobs = manager.jobs.first()
        currentJobs.forEach { job ->
            runCatching { manager.deleteJobWithFiles(job.id) }
        }
        withTimeoutOrNull(5_000) {
            while (manager.jobs.first().any { !it.state.isTerminal }) {
                kotlinx.coroutines.delay(200)
            }
        }
    }

    @Test
    fun test1_popupAppearsOnDirectTextFieldPasteAndPasteButton(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val repository = app.downloads.settingsRepository
        val settings = repository.settings.value.copy(
            showDownloadSizeConfirmation = false,
            isDownloadLocationConfigured = true,
            saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
        )

        composeRule.setContent {
            VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = settings,
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { _, _ -> },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        // --- Part 1A: Direct Text-Field Paste ---
        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeUrl)
        composeRule.waitForIdle()

        // Popup must appear immediately on paste
        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()
        composeRule.onNodeWithText("Entire Playlist").assertIsDisplayed()
        composeRule.onNodeWithText("This Video Only").assertIsDisplayed()

        // Dismiss popup
        composeRule.onNodeWithText("This Video Only").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)

        // Clear text field to test Paste button
        composeRule.onNodeWithTag("url_input_field").performTextClearance()
        composeRule.waitForIdle()

        // --- Part 1B: In-App Paste Button ---
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("composite", compositeUrl))
        }

        composeRule.onNodeWithTag("paste_button").performClick()
        composeRule.waitForIdle()

        // Popup must appear immediately after tapping Paste button
        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()
        composeRule.onNodeWithText("Entire Playlist").assertIsDisplayed()
        composeRule.onNodeWithText("This Video Only").assertIsDisplayed()
    }

    @Test
    fun test2_addToQueueDoesNotProduceDuplicatePopup(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val repository = app.downloads.settingsRepository
        val settings = repository.settings.value.copy(
            showDownloadSizeConfirmation = false,
            isDownloadLocationConfigured = true,
            saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
        )

        val enqueuedRequestRef = AtomicReference<DownloadRequest?>(null)

        composeRule.setContent {
            VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = settings,
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { req, _ -> enqueuedRequestRef.set(req) },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        // 1. Enter composite URL -> Initial popup appears
        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeUrl)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()

        // 2. Select "This Video Only" -> Dialog dismisses and enqueues single video
        composeRule.onNodeWithText("This Video Only").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)

        val firstReq = enqueuedRequestRef.get()
        assertNotNull("Request must be enqueued directly on selection", firstReq)
        assertFalse("Request must be for single video", firstReq!!.isPlaylist)
        assertEquals("https://www.youtube.com/watch?v=kYJ3s4f_N74", firstReq.url)

        // 3. User now enters the resolved single video URL and clicks "Add to Queue" -> Must NOT produce any popup
        enqueuedRequestRef.set(null)
        composeRule.onNodeWithTag("url_input_field").performTextClearance()
        composeRule.onNodeWithTag("url_input_field").performTextInput("https://www.youtube.com/watch?v=kYJ3s4f_N74")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)
        composeRule.onNodeWithTag("download_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Must NOT produce a duplicate popup
        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)
        val secondReq = enqueuedRequestRef.get()
        assertNotNull("Request must be enqueued directly without duplicate prompt", secondReq)
        assertFalse("Request must be for single video", secondReq!!.isPlaylist)
        assertEquals("https://www.youtube.com/watch?v=kYJ3s4f_N74", secondReq.url)
    }

    @Test
    fun test3A_selectEntirePlaylist_enqueuesCanonicalPlaylistAndCreatesIndependentJobs(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val repository = manager.settingsRepository

        val initialSettings = repository.settings.value
        val testSettings = initialSettings.copy(
            showDownloadSizeConfirmation = false,
            isDownloadLocationConfigured = true,
            saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
            concurrentDownloadsEnabled = true,
            maxConcurrentDownloads = 4,
        )

        val enqueuedRequestRef = AtomicReference<DownloadRequest?>(null)

        composeRule.setContent {
            VrkaTheme(themeMode = testSettings.themeMode, amoled = testSettings.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = testSettings,
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { req, _ -> enqueuedRequestRef.set(req) },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeUrl)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("download_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()
        composeRule.onNodeWithText("Entire Playlist").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)

        val enqueuedReq = enqueuedRequestRef.get()
        assertNotNull("Enqueue callback must receive the request", enqueuedReq)
        assertTrue("Enqueued request must have isPlaylist == true", enqueuedReq!!.isPlaylist)
        assertEquals(
            "Canonical playlist URL must be extracted",
            "https://www.youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI",
            enqueuedReq.url,
        )

        val items = manager.expandPlaylist(enqueuedReq, maxItems = 25, forceRefresh = true)
        assertNotNull("Playlist must expand successfully", items)
        assertEquals("Target playlist must expand to 9 items", 9, items!!.size)

        val playlistGroupId = UUID.randomUUID().toString()
        val batchItems = items.map { item ->
            enqueuedReq.copy(
                url = item.url,
                isPlaylist = false,
                playlistItemIndex = item.playlistItemIndex,
                playlistItemCount = item.playlistItemCount,
                playlistItemId = item.playlistItemId,
                playlistGroupId = playlistGroupId,
            ) to item.estimate
        }

        val batchResult = manager.enqueueBatch(batchItems, playlistGroupId)
        assertTrue("Batch enqueue must be accepted", batchResult is BatchEnqueueResult.Accepted)

        val createdJobs = manager.jobs.first()
            .filter { it.playlistGroupId == playlistGroupId }
            .sortedBy { it.playlistItemIndex }

        assertEquals("9 independent jobs must be created in manager", 9, createdJobs.size)
        val distinctJobIds = createdJobs.map { it.id }.toSet()
        assertEquals("Each job must have a unique ID", 9, distinctJobIds.size)
        createdJobs.forEachIndexed { idx, job ->
            assertFalse("Child job must have isPlaylist == false", job.request.isPlaylist)
            assertEquals("Index must match 1-based order", idx + 1, job.playlistItemIndex)
            assertEquals(9, job.playlistItemCount)
        }

        createdJobs.forEach { job ->
            runCatching { manager.deleteJobWithFiles(job.id) }
        }

        assertEquals("Settings in repository must remain unchanged", initialSettings, repository.settings.value)
    }

    @Test
    fun test3B_selectThisVideoOnly_downloadsOnlyOriginalVideo(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val repository = manager.settingsRepository
        val publisher = manager.publisher

        val initialSettings = repository.settings.value
        val testSettings = initialSettings.copy(
            showDownloadSizeConfirmation = false,
            isDownloadLocationConfigured = true,
            saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
        )

        val enqueuedRequestRef = AtomicReference<DownloadRequest?>(null)

        composeRule.setContent {
            VrkaTheme(themeMode = testSettings.themeMode, amoled = testSettings.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = testSettings,
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { req, _ -> enqueuedRequestRef.set(req) },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeShortUrl)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("download_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()
        composeRule.onNodeWithText("This Video Only").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)

        val enqueuedReq = enqueuedRequestRef.get()
        assertNotNull("Enqueue callback must receive the request", enqueuedReq)
        assertFalse("Request must have isPlaylist == false", enqueuedReq!!.isPlaylist)
        assertEquals(
            "URL must have playlist parameters stripped",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            enqueuedReq.url,
        )

        val audioRequest = enqueuedReq.copy(mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3)
        val enqueueResult = manager.enqueue(audioRequest)
        assertTrue("Single video enqueue must be accepted", enqueueResult is EnqueueResult.Accepted)
        val singleJobId = (enqueueResult as EnqueueResult.Accepted).jobId

        val jobs = manager.jobs.first()
        val job = jobs.firstOrNull { it.id == singleJobId }
        assertNotNull("Single video job must exist in queue", job)
        assertFalse("Job must not be marked as playlist", job!!.request.isPlaylist)
        assertNull("Job must not belong to any playlist group", job.playlistGroupId)
        assertEquals("Job URL must be the canonical single video", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", job.request.url)

        assertEquals("Settings in repository must remain unchanged", initialSettings, repository.settings.value)
    }

    @Test
    fun test4_shareSheetInput_behavesCorrectly(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val repository = app.downloads.settingsRepository
        val settings = repository.settings.value

        // Test Share-sheet composite link routing logic directly
        val rawRequest = DownloadRequest(
            url = compositeUrl,
            isPlaylist = false,
        )

        // When not marked as playlist and composite link is shared -> triggers playlist choice
        assertTrue(
            "Share-sheet input with composite link must trigger playlist choice",
            !rawRequest.isPlaylist && PlaylistRouting.isVideoWithPlaylistContext(rawRequest.url),
        )

        val playlistUrl = PlaylistRouting.extractPlaylistUrl(rawRequest.url)
        assertEquals(
            "Share-sheet must extract canonical playlist URL on Entire Playlist selection",
            "https://www.youtube.com/playlist?list=OLAK5uy_lj1KcaPJYjA1n29DoIF68QMbDehOplyEI",
            playlistUrl,
        )

        val singleUrl = PlaylistRouting.stripPlaylistContext(rawRequest.url)
        assertEquals(
            "Share-sheet must strip playlist context on This Video Only selection",
            "https://www.youtube.com/watch?v=kYJ3s4f_N74",
            singleUrl,
        )

        // When shared request has isPlaylist = true -> choice is bypassed
        val playlistRequest = rawRequest.copy(isPlaylist = true)
        assertFalse(
            "Share-sheet input already set to playlist must bypass choice dialog",
            !playlistRequest.isPlaylist && PlaylistRouting.isVideoWithPlaylistContext(playlistRequest.url),
        )
    }

    @Test
    fun test5_existingAdvancedSettingsRemainUnchanged(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val manager = app.downloads
        val repository = manager.settingsRepository

        repository.setConcurrentDownloadsEnabled(true)
        repository.setMaxConcurrentDownloads(3)
        repository.setAmoled(false)
        repository.setH264CompatibilityMode(true)

        val beforeInteraction = repository.settings.first { it.h264CompatibilityMode && !it.amoled }

        val enqueuedRef = AtomicReference<DownloadRequest?>(null)
        composeRule.setContent {
            VrkaTheme(themeMode = beforeInteraction.themeMode, amoled = beforeInteraction.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = beforeInteraction.copy(showDownloadSizeConfirmation = false),
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { req, _ -> enqueuedRef.set(req) },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeUrl)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("download_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Playlist Detected").assertIsDisplayed()

        composeRule.onNodeWithText("Entire Playlist").performClick()
        composeRule.waitForIdle()

        val afterEntirePlaylist = repository.settings.value
        assertEquals("Concurrent downloads enabled must be preserved", beforeInteraction.concurrentDownloadsEnabled, afterEntirePlaylist.concurrentDownloadsEnabled)
        assertEquals("Max concurrent downloads must be preserved", beforeInteraction.maxConcurrentDownloads, afterEntirePlaylist.maxConcurrentDownloads)
        assertEquals("Amoled setting must be preserved", beforeInteraction.amoled, afterEntirePlaylist.amoled)
        assertEquals("H264 compatibility setting must be preserved", beforeInteraction.h264CompatibilityMode, afterEntirePlaylist.h264CompatibilityMode)
        assertEquals("Full settings equality must hold", beforeInteraction, afterEntirePlaylist)
    }

    @Test
    fun test6_advancedPlaylistToggleEnabled_intentionallySuppressesPopup(): Unit = runBlocking {
        assumeTrue(
            "ARM64 device required for native execution on OnePlus 11",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
        )
        val app = ApplicationProvider.getApplicationContext<VrkaApplication>()
        val repository = app.downloads.settingsRepository
        val settings = repository.settings.value.copy(
            showDownloadSizeConfirmation = false,
            isDownloadLocationConfigured = true,
            saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
        )

        val enqueuedRequestRef = AtomicReference<DownloadRequest?>(null)

        composeRule.setContent {
            VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        settings = settings,
                        repository = repository,
                        runtime = RuntimeStatus(),
                        onEnqueue = { req, _ -> enqueuedRequestRef.set(req) },
                        onEstimateSize = { _, _ -> SizeEstimate(kind = SizeEstimateKind.EXACT, bytes = 1000L) },
                    )
                }
            }
        }

        // 1. Expand Advanced Options
        composeRule.onNodeWithText("Advanced Options").performClick()
        composeRule.waitForIdle()

        // 2. Enable "Playlist or range" switch
        composeRule.onNodeWithText("Playlist or range").performClick()
        composeRule.waitForIdle()

        // 3. Direct text-field paste with composite URL
        composeRule.onNodeWithTag("url_input_field").performTextInput(compositeUrl)
        composeRule.waitForIdle()

        // Popup MUST NOT appear because user already explicitly enabled the playlist toggle
        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)

        // 4. Click Download -> initiates directly as playlist without popup
        composeRule.onNodeWithTag("download_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)
        val enqueuedReq = enqueuedRequestRef.get()
        assertNotNull("Download request must be enqueued directly", enqueuedReq)
        assertTrue("Request must be marked as playlist", enqueuedReq!!.isPlaylist)

        // 5. Clear text field to test Paste button with playlist toggle still enabled
        composeRule.onNodeWithTag("url_input_field").performTextClearance()
        composeRule.waitForIdle()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("composite", compositeUrl))
        }

        composeRule.onNodeWithTag("paste_button").performClick()
        composeRule.waitForIdle()

        // Popup MUST NOT appear on Paste button when playlist toggle is already enabled
        composeRule.onAllNodesWithText("Playlist Detected").assertCountEquals(0)
    }
}
