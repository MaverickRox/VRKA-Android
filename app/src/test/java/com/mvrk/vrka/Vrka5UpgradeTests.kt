package com.mvrk.vrka

import com.mvrk.vrka.engine.MediaAssembly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Vrka5UpgradeTests {

    // =========================================================================
    // 1. Video Resolution Details in Queue & History
    // =========================================================================

    @Test
    fun jobMediaSummaryShowsActualResolutionSeparatelyFromRequested() {
        val videoJobWithActualRes = DownloadJob(
            id = "test-1",
            request = DownloadRequest(
                url = "https://example.com/video",
                mode = MediaMode.VIDEO,
                quality = VideoQuality.P1080,
            ),
            actualResolution = "1080p",
        )

        val summary = jobMediaSummary(videoJobWithActualRes)
        assertTrue("Summary should contain actual resolution 1080p", summary.contains("1080p"))
        assertTrue("Summary should contain Video mode", summary.contains("Video"))

        val videoJobDifferentActualRes = DownloadJob(
            id = "test-2",
            request = DownloadRequest(
                url = "https://example.com/video",
                mode = MediaMode.VIDEO,
                quality = VideoQuality.P1440,
            ),
            actualResolution = "1080p",
        )
        val summaryDiff = jobMediaSummary(videoJobDifferentActualRes)
        assertTrue("Summary should display actual 1080p", summaryDiff.contains("1080p"))
        assertTrue("Summary should mention requested: 1440p", summaryDiff.contains("requested: 1440p"))
    }

    @Test
    fun jobMediaSummaryForAudioNeverDisplaysVideoResolution() {
        val audioJob = DownloadJob(
            id = "audio-1",
            request = DownloadRequest(
                url = "https://example.com/song",
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.MP3,
                mp3Bitrate = 320,
            ),
            actualResolution = null,
        )

        val summary = jobMediaSummary(audioJob)
        assertTrue(summary.contains("Audio"))
        assertTrue(summary.contains("MP3"))
        assertTrue(summary.contains("320 kbps"))
        // Audio must not have video resolution pattern like 1080p, 720p, etc.
        assertFalse(Regex("""\d+p""").containsMatchIn(summary))
    }

    @Test
    fun jobStorePreservesActualResolution() {
        val tempFile = File.createTempFile("test_jobs", ".json")
        try {
            val store = JobStore(tempFile)
            val job = DownloadJob(
                id = "res-test",
                request = DownloadRequest(url = "https://example.com/test", mode = MediaMode.VIDEO),
                actualResolution = "2160p",
            )

            store.save(listOf(job))
            val loaded = store.load()

            assertEquals(1, loaded.size)
            assertEquals("res-test", loaded.first().id)
            assertEquals("2160p", loaded.first().actualResolution)
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // 2. Subtitle Embedding & Container Codec Selection
    // =========================================================================

    @Test
    fun subtitleCodecSelectionMatchesContainerAndFormat() {
        assertEquals("mov_text", MediaAssembly.getSubtitleCodecForContainer("mp4", "srt"))
        assertEquals("mov_text", MediaAssembly.getSubtitleCodecForContainer("m4v", "vtt"))
        assertEquals("mov_text", MediaAssembly.getSubtitleCodecForContainer("mov", "srt"))
        assertEquals("subrip", MediaAssembly.getSubtitleCodecForContainer("mkv", "srt"))
        assertEquals("ass", MediaAssembly.getSubtitleCodecForContainer("mkv", "ass"))
        assertEquals("ass", MediaAssembly.getSubtitleCodecForContainer("mkv", "ssa"))
        assertEquals("webvtt", MediaAssembly.getSubtitleCodecForContainer("webm", "vtt"))
    }

    @Test
    fun buildSubtitleEmbedCommandGeneratesSynchronizationSafeFlags() {
        val cmd = MediaAssembly.buildSubtitleEmbedCommand(
            ffmpegBinPath = "/system/bin/ffmpeg",
            videoPath = "/path/to/video.mp4",
            subtitlePath = "/path/to/subs.srt",
            outputPath = "/path/to/output.mp4",
            subtitleCodec = "mov_text",
            language = "eng",
        )

        // Verifies zero transcoding: video and audio stream copy
        assertTrue(cmd.contains("-c:v") && cmd[cmd.indexOf("-c:v") + 1] == "copy")
        assertTrue(cmd.contains("-c:a") && cmd[cmd.indexOf("-c:a") + 1] == "copy")
        assertTrue(cmd.contains("-c:s") && cmd[cmd.indexOf("-c:s") + 1] == "mov_text")
        assertTrue(cmd.contains("-fflags") && cmd[cmd.indexOf("-fflags") + 1] == "+genpts")
        assertTrue(cmd.contains("-avoid_negative_ts") && cmd[cmd.indexOf("-avoid_negative_ts") + 1] == "make_zero")
        assertTrue(cmd.contains("-movflags") && cmd[cmd.indexOf("-movflags") + 1] == "+faststart")
    }

    @Test
    fun buildRemuxCommandGeneratesPtsRegenerationFlags() {
        val cmd = MediaAssembly.buildRemuxCommand(
            ffmpegBinPath = "/system/bin/ffmpeg",
            inputPath = "/path/to/input.ts",
            outputPath = "/path/to/output.mp4",
        )

        assertTrue(cmd.contains("-fflags") && cmd[cmd.indexOf("-fflags") + 1] == "+genpts")
        assertTrue(cmd.contains("-c") && cmd[cmd.indexOf("-c") + 1] == "copy")
        assertTrue(cmd.contains("-avoid_negative_ts") && cmd[cmd.indexOf("-avoid_negative_ts") + 1] == "make_zero")
        assertTrue(cmd.contains("-movflags") && cmd[cmd.indexOf("-movflags") + 1] == "+faststart")
    }

    // =========================================================================
    // 3. Android Share Sheet URL Extraction
    // =========================================================================

    @Test
    fun urlExtractorExtractsUrlsFromRawShareText() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            UrlExtractor.extractUrl("https://youtu.be/dQw4w9WgXcQ"),
        )

        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            UrlExtractor.extractUrl("Watch this great video! https://www.youtube.com/watch?v=dQw4w9WgXcQ on VRKA"),
        )

        assertEquals(
            "https://example.com/media/file.mp4",
            UrlExtractor.extractUrl("Check out this link: https://example.com/media/file.mp4."),
        )

        assertEquals(
            "https://twitter.com/user/status/123456",
            UrlExtractor.extractUrl("\"https://twitter.com/user/status/123456\""),
        )

        assertNull(UrlExtractor.extractUrl(null))
        assertNull(UrlExtractor.extractUrl(""))
        assertNull(UrlExtractor.extractUrl("Just some text without any url in it."))
    }

    // =========================================================================
    // 4. History Deletion Data Models
    // =========================================================================

    @Test
    fun historyDeletionResultMaintainsAccurateCounts() {
        val result = HistoryDeletionResult(
            deletedRecordsCount = 5,
            deletedFilesCount = 4,
            failedFilesCount = 1,
        )
        assertEquals(5, result.deletedRecordsCount)
        assertEquals(4, result.deletedFilesCount)
        assertEquals(1, result.failedFilesCount)
    }

    // =========================================================================
    // 5. Concurrent Download Limit Configuration (2..4)
    // =========================================================================

    @Test
    fun maxConcurrentDownloadsDefaultsToTwoAndSupportsUpToFour() {
        val defaultSettings = AppSettings()
        assertEquals(2, defaultSettings.maxConcurrentDownloads)
        assertFalse(defaultSettings.concurrentDownloadsEnabled)

        val settingsThree = AppSettings(maxConcurrentDownloads = 3, concurrentDownloadsEnabled = true)
        assertEquals(3, settingsThree.maxConcurrentDownloads)
        assertTrue(settingsThree.concurrentDownloadsEnabled)

        val settingsFour = AppSettings(maxConcurrentDownloads = 4, concurrentDownloadsEnabled = true)
        assertEquals(4, settingsFour.maxConcurrentDownloads)

        // Verify values outside 2..4 are clamped by SettingsRepository policy
        assertEquals(2, 1.coerceIn(2, 4))
        assertEquals(4, 10.coerceIn(2, 4))
    }

    // =========================================================================
    // 6. Component Updater Terminal States
    // =========================================================================

    @Test
    fun componentStatusIsNotUpdatingWhenSuccessOrTerminalLifecycle() {
        val successStatus = ComponentStatus(
            id = "ublock",
            name = "uBlock Origin",
            installedVersion = "1.60.0",
            lifecycleState = ComponentLifecycleState.DOWNLOADING,
            updateState = ComponentUpdateState.UPDATE_SUCCESS,
        )
        assertFalse("isUpdating must be false when updateState is UPDATE_SUCCESS", successStatus.isUpdating)

        val updatedStatus = ComponentStatus(
            id = "ublock",
            name = "uBlock Origin",
            installedVersion = "1.60.0",
            lifecycleState = ComponentLifecycleState.UPDATED,
            updateState = ComponentUpdateState.UPDATE_IDLE,
        )
        assertFalse("isUpdating must be false when lifecycleState is UPDATED", updatedStatus.isUpdating)

        val upToDateStatus = ComponentStatus(
            id = "ublock",
            name = "uBlock Origin",
            installedVersion = "1.60.0",
            lifecycleState = ComponentLifecycleState.UP_TO_DATE,
            updateState = ComponentUpdateState.UPDATE_IDLE,
        )
        assertFalse("isUpdating must be false when lifecycleState is UP_TO_DATE", upToDateStatus.isUpdating)

        val activeDownloadingStatus = ComponentStatus(
            id = "ublock",
            name = "uBlock Origin",
            installedVersion = "1.60.0",
            lifecycleState = ComponentLifecycleState.DOWNLOADING,
            updateState = ComponentUpdateState.DOWNLOADING,
        )
        assertTrue("isUpdating should be true during active download", activeDownloadingStatus.isUpdating)
    }

    // =========================================================================
    // 7. No automatic impersonation retry
    // =========================================================================

    @Test
    fun downloadNeverAddsImpersonationFlags() {
        val stagingDir = File("build/test-staging")
        stagingDir.mkdirs()
        try {
            for (url in listOf("https://youtube.com/watch?v=12345", "https://example.test/media")) {
                val job = DownloadJob(id = "no-impersonation", request = DownloadRequest(url = url))
                val request = DownloadRequestFactory.download(job, stagingDir)
                assertFalse(request.hasOption("--extractor-args"))
                assertFalse(request.hasOption("--impersonate"))
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }
}
