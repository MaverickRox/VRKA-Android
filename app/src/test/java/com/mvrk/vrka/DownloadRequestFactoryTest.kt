package com.mvrk.vrka

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DownloadRequestFactoryTest {
    private val staging = File("build/request-factory-test")

    @Before
    fun configureCacheDir() {
        DownloadRequestFactory.initCacheDir(CACHE_DIR)
    }

    @After
    fun resetCacheDir() {
        DownloadRequestFactory.initCacheDir(null)
    }

    @Test
    fun pipelineRequestsReuseTheAppPrivateCacheDirectory() {
        val requests = listOf(
            DownloadRequestFactory.info(DownloadRequest(url = URL)),
            DownloadRequestFactory.sizeEstimate(DownloadRequest(url = URL)),
            built(DownloadRequest(url = URL)),
        )

        requests.forEach { request ->
            // ytdl-android only keeps its forced --no-cache-dir when no cache path is supplied.
            assertEquals(CACHE_DIR, request.getOption("--cache-dir"))
            assertFalse(request.hasOption("--remote-components"))
        }
    }

    @Test
    fun customTemplatesKeepTheirOwnCachePolicy() {
        val request = DownloadRequestFactory.customCommand(
            DownloadJob(
                id = "custom-job",
                request = DownloadRequest(
                    url = URL,
                    commandTemplateId = "template-1",
                    commandTemplateName = "Rate limit",
                    commandTemplateArguments = listOf("--limit-rate", "2M"),
                ),
            ),
            staging,
        )

        assertNull(request.getOption("--cache-dir"))
    }

    @Test
    fun transfersKeepBoundedFragmentConcurrency() {
        val video = built(DownloadRequest(url = URL, quality = VideoQuality.P1080))
        val audio = built(DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3))

        assertEquals("4", video.getOption("--concurrent-fragments"))
        assertEquals("4", audio.getOption("--concurrent-fragments"))
    }

    @Test
    fun audioProfilesMapToHonestFfmpegOptions() {
        val profiles = listOf(
            Triple(AudioFormat.MP3, 320, "320K"),
            Triple(AudioFormat.MP3, 192, "192K"),
            Triple(AudioFormat.WAV, 320, null),
            Triple(AudioFormat.OPUS, 320, null),
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
            if (format == AudioFormat.OPUS) {
                assertEquals("bestaudio[acodec^=opus]/bestaudio/best", request.getOption("-f"))
            } else {
                assertEquals("bestaudio/best", request.getOption("-f"))
            }
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
                    "X-Forwarded-For" to "ignored",
                ),
                resolvedMediaUrl = URL,
                resolvedHeadersOrigin = URL,
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
        assertFalse(command.any { "X-Forwarded-For" in it })
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
        assertFalse(recoveryReq.hasOption("--extractor-args"))
        assertFalse(recoveryReq.hasOption("--impersonate"))
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
        assertFalse(recovery.hasOption("--extractor-args"))
        assertEquals(URL, recovery.getOption("--referer"))
        assertTrue(recovery.getOption("--user-agent").orEmpty().contains("Chrome/"))
    }

    @Test
    fun dedicatedRefererAndOriginArePassedViaCommandLine() {
        val request = built(
            DownloadRequest(
                url = URL,
                referer = "https://specific-source.test/page",
                origin = "https://specific-source.test",
                customHeaders = mapOf("Authorization" to "Bearer token123"),
            ),
        )
        val command = request.buildCommand()
        assertTrue(command.any { it == "Referer:https://specific-source.test/page" })
        assertTrue(command.any { it == "Origin:https://specific-source.test" })
        assertFalse(command.any { it.startsWith("Authorization:", ignoreCase = true) })
    }

    @Test
    fun caseInsensitiveHeaderPrecedenceRespectsResolvedOverCustom() {
        val request = built(
            DownloadRequest(
                url = URL,
                referer = "https://typed-referer.test",
                customHeaders = mapOf("REFERER" to "https://custom-header.test"),
                resolvedHeaders = mapOf("Referer" to "https://resolved-browser.test"),
                resolvedMediaUrl = "https://cdn.test/video.m3u8",
                resolvedHeadersOrigin = "https://cdn.test/video.m3u8",
            ),
        )
        val command = request.buildCommand()
        assertTrue(command.any { it == "Referer:https://resolved-browser.test" })
        assertFalse(command.any { it.contains("typed-referer.test") })
        assertFalse(command.any { it.contains("custom-header.test") })
    }

    @Test
    fun customCommandUsesSeparateValidatedUrlAndPreservesTemplateArgv() {
        val url = "https://example.test/video?label=--exec"
        val request = DownloadRequestFactory.customCommand(
            DownloadJob(
                id = "custom-job",
                request = DownloadRequest(
                    url = url,
                    commandTemplateId = "template-1",
                    commandTemplateName = "Rate limit",
                    commandTemplateArguments = listOf("--limit-rate", "2M"),
                ),
            ),
            staging,
        )

        val argv = request.buildCommand()
        assertTrue(argv.contains(url))
        assertEquals(1, argv.count { it == url })
        assertTrue(argv.contains("--limit-rate"))
        assertTrue(argv.contains("2M"))
        assertTrue(argv.contains("--progress"))
        assertTrue(argv.any { it.startsWith("-o") || it == "-o" })
    }

    @Test
    fun eligibleFailureUsesBrowserFallbackWithCustomModeEitherWayAndHonorsCancellation() {
        val normal = DownloadRequest(url = URL)
        val custom = DownloadRequest(
            url = URL,
            commandTemplateId = "template-1",
            commandTemplateName = "Rate limit",
            commandTemplateArguments = listOf("--limit-rate", "2M"),
        )

        listOf(normal, custom).forEach { request ->
            assertTrue(DownloadRequestFactory.shouldAttemptBrowserFallback(request, hasFailure = true, cancelled = false))
            assertFalse(DownloadRequestFactory.shouldAttemptBrowserFallback(request, hasFailure = true, cancelled = true))
            assertFalse(DownloadRequestFactory.shouldAttemptBrowserFallback(request, hasFailure = false, cancelled = false))
        }
    }

    @Test
    fun browserFallbackRequestKeepsPublicPageAndDropsCustomTemplateState() {
        val privateCandidate = "https://cdn.example.test/private/session-token/stream.m3u8?sig=temporary"
        val request = DownloadRequest(
            url = URL,
            referer = "https://typed.example.test/",
            origin = "https://typed.example.test",
            customHeaders = mapOf("X-User-Header" to "must-not-leak"),
            customArguments = listOf("--limit-rate", "2M"),
            commandTemplateId = "template-1",
            commandTemplateName = "Custom template",
            commandTemplateArguments = listOf("--exec", "echo unsafe"),
        )

        val fallback = DownloadRequestFactory.browserFallbackRequest(
            request = request,
            mediaUrl = privateCandidate,
            headers = mapOf("Referer" to "https://site.example.test/watch"),
        )

        assertEquals(URL, fallback.url)
        assertEquals(privateCandidate, fallback.resolvedMediaUrl)
        assertNull(fallback.commandTemplateId)
        assertEquals(emptyList<String>(), fallback.commandTemplateArguments)
        assertEquals(emptyList<String>(), fallback.customArguments)
        assertEquals("", fallback.referer)
        assertEquals("", fallback.origin)
        assertTrue(fallback.customHeaders.isEmpty())

        val argv = built(fallback).buildCommand()
        assertTrue(argv.contains(privateCandidate))
        assertFalse(argv.contains("--exec"))
        assertFalse(argv.contains("echo unsafe"))
        assertFalse(argv.contains("--limit-rate"))
        assertFalse(argv.contains("must-not-leak"))
    }

    @Test
    fun impersonationArgumentsAreStrippedInAllModes() {
        val videoReq = built(
            DownloadRequest(
                url = URL,
                mode = MediaMode.VIDEO,
                customArguments = listOf("--impersonate", "chrome", "--impersonate=safari", "--geo-bypass"),
            ),
        )
        val videoCmd = videoReq.buildCommand()
        assertFalse(videoCmd.any { it.contains("impersonate", ignoreCase = true) })
        assertTrue(videoCmd.any { it == "--geo-bypass" })

        val audioReq = built(
            DownloadRequest(
                url = URL,
                mode = MediaMode.AUDIO,
                customArguments = listOf("--impersonate", "chrome", "--impersonate=safari", "--no-cache-dir"),
            ),
        )
        val audioCmd = audioReq.buildCommand()
        assertFalse(audioCmd.any { it.contains("impersonate", ignoreCase = true) })
        assertTrue(audioCmd.any { it == "--no-cache-dir" })
        assertFalse(audioCmd.contains("--cache-dir"))
    }

    @Test
    fun wavAudioModeOmitsEmbedThumbnailEvenIfRequested() {
        val wavRequest = built(
            DownloadRequest(
                url = URL,
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.WAV,
                embedThumbnail = true,
            ),
        )
        assertFalse(wavRequest.hasOption("--embed-thumbnail"))

        val mp3Request = built(
            DownloadRequest(
                url = URL,
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.MP3,
                embedThumbnail = true,
            ),
        )
        assertTrue(mp3Request.hasOption("--embed-thumbnail"))

        val opusRequest = built(
            DownloadRequest(
                url = URL,
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.OPUS,
                embedThumbnail = true,
            ),
        )
        assertTrue(opusRequest.hasOption("--embed-thumbnail"))
    }

    private fun built(request: DownloadRequest) =
        DownloadRequestFactory.download(
            DownloadJob(id = "test-job", request = request),
            staging,
        )

    private companion object {
        const val URL = "https://example.test/video"
        const val CACHE_DIR = "/data/user/0/com.mvrk.vrka/cache/yt-dlp"
    }
}
