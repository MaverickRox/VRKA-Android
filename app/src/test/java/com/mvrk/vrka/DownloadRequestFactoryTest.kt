package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadRequestFactoryTest {
    private val staging = File("build/request-factory-test")

    @Test
    fun audioProfilesMapToHonestFfmpegOptions() {
        val profiles = listOf(
            Triple(AudioFormat.MP3, 320, "320K"),
            Triple(AudioFormat.MP3, 192, "192K"),
            Triple(AudioFormat.WAV, 320, "0"),
            Triple(AudioFormat.FLAC, 320, "0"),
        )

        profiles.forEach { (format, bitrate, expectedQuality) ->
            val request = built(
                DownloadRequest(
                    url = URL,
                    mode = MediaMode.AUDIO,
                    audioFormat = format,
                    mp3Bitrate = bitrate,
                ),
            )
            assertTrue(request.hasOption("--extract-audio"))
            assertEquals(format.codec, request.getOption("--audio-format"))
            assertEquals(expectedQuality, request.getOption("--audio-quality"))
            assertEquals("bestaudio/best", request.getOption("-f"))
        }
    }

    @Test
    fun playlistRangeSubtitlesAndTrimArePreservedTogether() {
        val request = built(
            DownloadRequest(
                url = URL,
                isPlaylist = true,
                playlistStart = 2,
                playlistEnd = 4,
                downloadSubtitles = true,
                automaticCaptions = true,
                subtitleLanguages = "en.*,hi",
                trimStart = "00:00:03",
                trimEnd = "00:00:08",
            ),
        )

        assertFalse(request.hasOption("--no-playlist"))
        assertEquals("2", request.getOption("--playlist-start"))
        assertEquals("4", request.getOption("--playlist-end"))
        assertTrue(request.hasOption("--write-subs"))
        assertTrue(request.hasOption("--write-auto-subs"))
        assertTrue(request.hasOption("--embed-subs"))
        assertEquals("en.*,hi", request.getOption("--sub-langs"))
        assertEquals("*00:00:03-00:00:08", request.getOption("--download-sections"))
        assertTrue(request.hasOption("--force-keyframes-at-cuts"))
    }

    @Test
    fun videoQualitySponsorBlockAndSessionHeadersUseOneBuilder() {
        val request = built(
            DownloadRequest(
                url = URL,
                quality = VideoQuality.P1080,
                sponsorBlock = true,
                sponsorCategories = "sponsor,selfpromo",
                resolvedHeaders = mapOf(
                    "Cookie" to "session=private",
                    "Referer" to "https://example.test/watch",
                    "X-Not-Allowed" to "ignored",
                ),
            ),
        )

        assertEquals(
            "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
            request.getOption("-f"),
        )
        assertFalse(request.hasOption("-S"))
        assertEquals("mp4", request.getOption("--merge-output-format"))
        assertEquals("sponsor,selfpromo", request.getOption("--sponsorblock-remove"))
        val command = request.buildCommand()
        assertTrue(command.any { it == "Cookie:session=private" })
        assertTrue(command.any { it == "Referer:https://example.test/watch" })
        assertFalse(command.any { "X-Not-Allowed" in it })
    }

    @Test
    fun videoSelectorsRequireVideoAndTreatQualityAsAnUpperBound() {
        val best = built(DownloadRequest(url = URL, quality = VideoQuality.BEST))
        val capped = built(DownloadRequest(url = URL, quality = VideoQuality.P2160))

        assertEquals(
            "bestvideo+bestaudio/best",
            best.getOption("-f"),
        )
        assertEquals(
            "bestvideo[height<=2160]+bestaudio/best[height<=2160]/best",
            capped.getOption("-f"),
        )
        assertEquals("mp4", best.getOption("--merge-output-format"))
    }

    @Test
    fun optional60FpsSortKeepsResolutionFirst() {
        val best = built(
            DownloadRequest(url = URL, prefer60Fps = true),
        )
        val capped = built(
            DownloadRequest(
                url = URL,
                quality = VideoQuality.P1080,
                prefer60Fps = true,
            ),
        )

        assertEquals("res,fps", best.getOption("-S"))
        assertEquals("bestvideo[fps>=60]+bestaudio/bestvideo+bestaudio/best", best.getOption("-f"))
        assertEquals("res,fps", capped.getOption("-S"))
        assertEquals("bestvideo[height<=1080][fps>=60]+bestaudio/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", capped.getOption("-f"))
    }

    @Test
    fun youtubeRequestsDoNotForcePlayerClients() {
        val ytUrl = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val infoReq = DownloadRequestFactory.info(DownloadRequest(url = ytUrl))
        assertFalse(infoReq.hasOption("--extractor-args"))

        val dlReq = built(DownloadRequest(url = ytUrl))
        assertFalse(dlReq.hasOption("--extractor-args"))

        val recoveryReq = DownloadRequestFactory.download(
            DownloadJob(id = "test-job", request = DownloadRequest(url = ytUrl)),
            staging,
            recoveryAttempt = true,
        )
        assertEquals("generic:impersonate", recoveryReq.getOption("--extractor-args"))
    }

    @Test
    fun buildVideoFormatHandlesAllTiersCorrectly() {
        assertEquals("bestvideo+bestaudio/best", DownloadRequestFactory.buildVideoFormat(null, prefer60Fps = false))
        assertEquals("bestvideo[fps>=60]+bestaudio/bestvideo+bestaudio/best", DownloadRequestFactory.buildVideoFormat(null, prefer60Fps = true))
        assertEquals("bestvideo[height<=720]+bestaudio/best[height<=720]/best", DownloadRequestFactory.buildVideoFormat(720, prefer60Fps = false))
        assertEquals("bestvideo[height<=1080][fps>=60]+bestaudio/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", DownloadRequestFactory.buildVideoFormat(1080, prefer60Fps = true))
    }

    @Test
    fun subtitleEmbeddingCanBeDisabledWithoutDisablingDownload() {
        val request = built(
            DownloadRequest(
                url = URL,
                downloadSubtitles = true,
                embedSubtitles = false,
            ),
        )

        assertTrue(request.hasOption("--write-subs"))
        assertFalse(request.hasOption("--embed-subs"))
    }

    @Test
    fun videoCustomArgumentsCannotOverrideVideoModeWithAudioOnly() {
        val request = built(
            DownloadRequest(
                url = URL,
                customArguments = listOf(
                    "-f", "bestaudio", "--extract-audio", "--audio-format=mp3",
                    "--limit-rate", "2M",
                ),
            ),
        )

        val command = request.buildCommand()
        assertFalse(command.any { it == "bestaudio" || it == "--extract-audio" })
        assertFalse(command.any { it == "--audio-format=mp3" })
        assertTrue(command.any { it == "--limit-rate" })
    }

    @Test
    fun stagingAndPublishedNamesStayBounded() {
        val request = built(
            DownloadRequest(
                url = URL,
                resolvedMediaUrl = "https://cdn.example.test/video.mp4?token=" + "x".repeat(2_000),
            ),
        )
        val output = request.getOption("-o").orEmpty().replace('\\', '/')
        assertTrue(output.endsWith("/media.%(ext)s"))
        assertFalse("token=" in output)

        val safe = SafeOutputNames.sanitize(
            "A".repeat(1_000) + "/signed?token=" + "z".repeat(1_000),
            "mp4",
        )
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 164)
        assertTrue(safe.endsWith(".mp4"))
        assertFalse('/' in safe)
        assertFalse('?' in safe)
    }

    @Test
    fun directRecoveryUsesAndroidCompatibleHeadersAndGenericRetry() {
        val recovery = DownloadRequestFactory.download(
            DownloadJob(id = "test-job", request = DownloadRequest(url = URL)),
            staging,
            recoveryAttempt = true,
        )

        assertEquals(null, recovery.getOption("--impersonate"))
        assertEquals("generic:impersonate", recovery.getOption("--extractor-args"))
        assertEquals(URL, recovery.getOption("--referer"))
        assertTrue(recovery.getOption("--user-agent").orEmpty().contains("Chrome/"))
    }
    private fun built(request: DownloadRequest) =
        DownloadRequestFactory.download(
            DownloadJob(id = "test-job", request = request),
            staging,
        )

    private companion object {
        const val URL = "https://example.test/video"
    }
}
