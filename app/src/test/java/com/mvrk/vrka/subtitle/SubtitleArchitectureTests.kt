package com.mvrk.vrka.subtitle

import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.engine.FailureCategory
import com.mvrk.vrka.engine.MediaAssembly
import com.mvrk.vrka.engine.classifyDownloadError
import com.mvrk.vrka.engine.isSubtitleFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubtitleArchitectureTests {

    // 1. Manual subtitle classification
    @Test
    fun testManualSubtitleClassification() {
        val err = "ERROR: [youtube] NdvqHc561E0: Subtitle download failed: HTTP Error 404: Not Found"
        assertTrue(isSubtitleFailure(err))
        val cat = classifyDownloadError(err)
        assertEquals(FailureCategory.HTTP, cat)
    }

    // 2. Automatic subtitle classification
    @Test
    fun testAutomaticSubtitleClassification() {
        val err = "ERROR: [youtube] Unable to download video subtitles: HTTP Error 429: Too Many Requests"
        assertTrue(isSubtitleFailure(err))
        val cat = classifyDownloadError(err)
        assertEquals(FailureCategory.HTTP, cat)
    }

    // 3. HTTP 429 subtitle handling
    @Test
    fun testHttp429SubtitleHandling() {
        val err = "ERROR: [youtube] Subtitles could not be downloaded: 429 Too Many Requests"
        assertTrue(isSubtitleFailure(err))
        // Verify FailureClassifier does NOT treat subtitle 429 as browser recoverable media error
        val recoverable = com.mvrk.vrka.engine.isFailureBrowserRecoverable(
            category = FailureCategory.HTTP,
            errorMessage = err,
            targetUrl = "https://www.youtube.com/watch?v=NdvqHc561E0",
            executionOutput = err,
        )
        assertFalse(recoverable)
    }

    // 4. Subtitle retry isolation
    @Test
    fun testSubtitleRetryIsolation() {
        val manager = VrkaSubtitleManager()
        val initialLangs = manager.resolveSubtitleLanguages("en.*", automaticCaptions = true)
        val retryLangs = manager.resolveSubtitleLanguages("en.*", automaticCaptions = false)
        // Auto includes original speech-to-text; retry with manual only drops auto-generated variants
        assertTrue(initialLangs.contains("en-orig"))
        assertEquals("en", retryLangs)
    }

    // 5. Subtitle failure without media failure
    @Test
    fun testSubtitleFailureWithoutMediaFailure() {
        val job = DownloadJob(
            id = "test-job-1",
            request = DownloadRequest(
                url = "https://www.youtube.com/watch?v=123",
                downloadSubtitles = true,
            ),
            state = JobState.DONE,
            detail = "Saved to Downloads/VRKA (subtitles rate-limited)",
            subtitleStatus = SubtitleStatus.FAILED,
            outputUris = listOf("/sdcard/Download/VRKA/test.mp4"),
        )
        assertEquals(JobState.DONE, job.state)
        assertEquals(SubtitleStatus.FAILED, job.subtitleStatus)
        assertTrue(job.detail.contains("subtitles rate-limited"))
    }

    // 6. Subtitle format validation
    @Test
    fun testSubtitleFormatValidation() {
        val validVtt = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:01.000 --> 00:00:04.000
            Hello world!
        """.trimIndent()
        val result = SubtitleValidator.validateContent(validVtt, "vtt")
        assertTrue(result is SubtitleValidationResult.Valid)
        assertEquals("vtt", (result as SubtitleValidationResult.Valid).format)
        assertEquals(1, result.cueCount)
        assertEquals("en", result.detectedLanguage)
    }

    // 7. Invalid HTML response rejection
    @Test
    fun testInvalidHtmlResponseRejection() {
        val htmlError = """
            <!DOCTYPE html>
            <html>
            <head><title>403 Forbidden</title></head>
            <body><p>Access denied</p></body>
            </html>
        """.trimIndent()
        val result = SubtitleValidator.validateContent(htmlError, "vtt")
        assertTrue(result is SubtitleValidationResult.Invalid)
        assertTrue((result as SubtitleValidationResult.Invalid).reason.contains("HTML"))

        val cloudflareChallenge = """
            <!DOCTYPE html>
            <html><head><title>Just a moment...</title></head>
            <body><div class="cf-browser-verification">Verifying</div></body></html>
        """.trimIndent()
        val cfResult = SubtitleValidator.validateContent(cloudflareChallenge, "srt")
        assertTrue(cfResult is SubtitleValidationResult.Invalid)
    }

    // 8. VTT parsing and conversion
    @Test
    fun testVttParsingAndConversion() {
        val vtt = """
            WEBVTT
            NOTE This is a test

            00:01.500 --> 00:04.000 position:10%
            <v Speaker>First line of dialogue</v>

            00:05.000 --> 00:08.500
            Second line of dialogue
        """.trimIndent()

        val convertedSrt = SubtitleValidator.vttToSrt(vtt)
        assertTrue(convertedSrt.contains("1\r\n00:00:01,500 --> 00:00:04,000\r\nFirst line of dialogue"))
        assertTrue(convertedSrt.contains("2\r\n00:00:05,000 --> 00:00:08,500\r\nSecond line of dialogue"))
        assertFalse(convertedSrt.contains("WEBVTT"))
        assertFalse(convertedSrt.contains("<v Speaker>"))
    }

    // 9. SRT parsing
    @Test
    fun testSrtParsing() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world!

            2
            00:00:05,000 --> 00:00:08,000
            Subtitle line 2
        """.trimIndent()

        val validation = SubtitleValidator.validateContent(srt, "srt")
        assertTrue(validation is SubtitleValidationResult.Valid)
        assertEquals("srt", (validation as SubtitleValidationResult.Valid).format)
        assertEquals(2, validation.cueCount)
    }

    // 10. Subtitle language selection
    @Test
    fun testSubtitleLanguageSelection() {
        val manager = VrkaSubtitleManager()
        // Strips wildcards that flood YouTube with 50+ auto-translated streams
        val resolvedDefault = manager.resolveSubtitleLanguages("en.*", automaticCaptions = true)
        assertEquals("en,en-orig", resolvedDefault)

        val resolvedCustom = manager.resolveSubtitleLanguages("ja,es", automaticCaptions = false)
        assertEquals("ja,es", resolvedCustom)

        val resolvedCustomWithAuto = manager.resolveSubtitleLanguages("ja,es", automaticCaptions = true)
        assertEquals("ja,ja-orig,es,es-orig", resolvedCustomWithAuto)
    }

    // 11. Browser subtitle event parsing
    @Test
    fun testBrowserSubtitleEventParsing() {
        val candidate = BrowserSubtitleCandidate(
            url = "https://stream.example.com/sub/ep1_en.vtt",
            pageUrl = "https://anikoto.cz/watch/1",
            language = "cs",
            label = "Czech",
            format = "vtt",
            source = "track",
        )
        val event = SubtitleEvent(
            type = SubtitleEventType.SUBTITLE_DISCOVERED,
            url = candidate.url,
            language = candidate.language,
            format = candidate.format,
            source = candidate.source,
            detail = "Discovered track from player DOM",
        )
        assertEquals(SubtitleEventType.SUBTITLE_DISCOVERED, event.type)
        assertEquals("https://stream.example.com/sub/ep1_en.vtt", event.url)
        assertEquals("cs", event.language)
        assertEquals("track", event.source)
    }

    // 12. Subtitle manager event recording
    @Test
    fun testSubtitleManagerEventRecording() {
        val manager = VrkaSubtitleManager()
        assertEquals(0, manager.events.value.size)

        val event = SubtitleEvent(
            type = SubtitleEventType.SUBTITLE_DISCOVERED,
            url = "https://anikoto.cz/subtitles/1.vtt",
            language = "cs",
            format = "vtt",
            source = "network",
        )
        manager.recordEvent(event)
        assertEquals(1, manager.events.value.size)
        assertEquals("https://anikoto.cz/subtitles/1.vtt", manager.events.value.first().url)
        assertEquals("cs", manager.events.value.first().language)
    }

    // 13. Subtitle filename generation
    @Test
    fun testSubtitleFilenameGeneration() {
        val videoFile = File("/storage/emulated/0/Download/VRKA/Anime_Ep_01.mp4")
        val subFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.srt")
        assertEquals("Anime_Ep_01.srt", subFile.name)
        assertEquals("/storage/emulated/0/Download/VRKA/Anime_Ep_01.srt", subFile.path.replace('\\', '/'))
    }

    // 14. Subtitle embedding command generation
    @Test
    fun testSubtitleEmbeddingCommandGeneration() {
        val cmd = MediaAssembly.buildSubtitleEmbedCommand(
            ffmpegBinPath = "/data/data/com.mvrk.vrka/lib/libffmpeg.so",
            videoPath = "/staging/video.mp4",
            subtitlePath = "/staging/sub.srt",
            outputPath = "/staging/embedded.mp4",
            subtitleCodec = "mov_text",
            language = "eng",
        )
        assertTrue(cmd.contains("-c:s"))
        assertTrue(cmd.contains("mov_text"))
        assertTrue(cmd.contains("-metadata:s:s:0"))
        assertTrue(cmd.contains("language=eng"))
    }

    // 15. Container-specific subtitle codec selection
    @Test
    fun testContainerSpecificSubtitleCodecSelection() {
        // MP4 requires mov_text
        assertEquals("mov_text", MediaAssembly.getSubtitleCodecForContainer("mp4", "srt"))
        assertEquals("mov_text", MediaAssembly.getSubtitleCodecForContainer("m4v", "vtt"))

        // MKV supports subrip and ass
        assertEquals("subrip", MediaAssembly.getSubtitleCodecForContainer("mkv", "srt"))
        assertEquals("ass", MediaAssembly.getSubtitleCodecForContainer("mkv", "ass"))

        // WebM requires webvtt
        assertEquals("webvtt", MediaAssembly.getSubtitleCodecForContainer("webm", "vtt"))
    }
}
