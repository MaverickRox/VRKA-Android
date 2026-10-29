package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareConfigurationTest {
    @Test
    fun extractsTheFirstHttpUrlFromSharedText() {
        assertEquals(
            "https://example.test/watch?v=1",
            ShareLinkValidator.extract("Watch this: https://example.test/watch?v=1. Enjoy"),
        )
        assertEquals(
            "https://example.test/clip",
            ShareLinkValidator.extract("  https://example.test/clip  "),
        )
        assertNull(ShareLinkValidator.extract("no link here"))
        assertNull(ShareLinkValidator.extract(""))
        assertNull(ShareLinkValidator.extract(null))
    }

    @Test
    fun unsafeOrMalformedSharedInputsAreRejected() {
        assertNull(ShareLinkValidator.extract("javascript:alert(1)"))
        assertNull(ShareLinkValidator.extract("ftp://example.test/file"))
        assertFalse(ShareLinkValidator.isValid(null))
        assertFalse(ShareLinkValidator.isValid("file:///sdcard/clip.mp4"))
        assertFalse(ShareLinkValidator.isValid("https://"))
        assertFalse(ShareLinkValidator.isValid("https://user:pass@example.test/video"))
        assertTrue(ShareLinkValidator.isValid("https://example.test/video"))
        assertTrue(ShareLinkValidator.isValid("http://example.test/video?x=1"))
    }

    @Test
    fun duplicateShareIntentsAreSuppressedInsideTheWindowOnly() {
        val tracker = ShareIntentTracker(windowMs = 1_500)
        val fingerprint = ShareIntentTracker.fingerprint(
            action = "android.intent.action.SEND",
            data = null,
            text = "https://example.test/watch",
        )

        assertTrue(tracker.accept(fingerprint, 1_000))
        assertFalse(tracker.accept(fingerprint, 1_200))
        // The rejected duplicate must not extend the debounce window.
        assertFalse(tracker.accept(fingerprint, 2_400))
        assertTrue(tracker.accept(fingerprint, 3_000))
    }

    @Test
    fun aRestoredIntentIsNotReplayedButANewShareIsAccepted() {
        val tracker = ShareIntentTracker(windowMs = 1_500)
        val restored = ShareIntentTracker.fingerprint("android.intent.action.SEND", null, "https://a.test/1")
        tracker.restore(restored, at = 5_000)

        assertFalse(tracker.accept(restored, 5_100))
        assertTrue(tracker.accept(ShareIntentTracker.fingerprint("android.intent.action.SEND", null, "https://a.test/2"), 5_200))
        assertTrue(tracker.accept(restored, 7_000))
    }

    @Test
    fun sharePreferencesRoundTripWithoutUrlsOrHeaders() {
        val preferences = ShareSheetPreferences(
            mode = MediaMode.AUDIO,
            quality = VideoQuality.P720,
            audioFormat = AudioFormat.OPUS,
            mp3Bitrate = 192,
            downloadSubtitles = true,
            embedThumbnail = false,
            useCommands = true,
        )

        val encoded = SharePreferencesCodec.encode(preferences)
        assertEquals(preferences, SharePreferencesCodec.decode(encoded))
        assertFalse(encoded.contains("http", ignoreCase = true))
        assertFalse(encoded.contains("cookie", ignoreCase = true))
    }

    @Test
    fun sharePreferencesFallBackSafelyWhenValuesAreInvalidOrCorrupt() {
        val decoded = SharePreferencesCodec.decode(
            """{"mode":"AUDIO","quality":"P1080","audioFormat":"MP3","mp3Bitrate":999,"downloadSubtitles":true,"embedThumbnail":false}""",
        )
        assertEquals(MediaMode.AUDIO, decoded?.mode)
        assertEquals(VideoQuality.P1080, decoded?.quality)
        assertEquals(AudioFormat.MP3, decoded?.audioFormat)
        assertEquals(320, decoded?.mp3Bitrate)
        assertEquals(true, decoded?.downloadSubtitles)
        assertEquals(false, decoded?.embedThumbnail)
        assertEquals(false, decoded?.useCommands)

        val unknownEnums = SharePreferencesCodec.decode("""{"mode":"NOPE","quality":"NOPE","audioFormat":"NOPE"}""")
        assertEquals(MediaMode.VIDEO, unknownEnums?.mode)
        assertEquals(VideoQuality.BEST, unknownEnums?.quality)
        assertEquals(AudioFormat.MP3, unknownEnums?.audioFormat)

        assertNull(SharePreferencesCodec.decode("not json"))
        assertNull(SharePreferencesCodec.decode(""))
        assertNull(SharePreferencesCodec.decode(null))
    }

    @Test
    fun videoRequestsKeepTheSelectedQualityAndSubtitleEmbedding() {
        val request = ShareRequestBuilder.build(
            url = URL,
            preferences = ShareSheetPreferences(
                quality = VideoQuality.P1080,
                downloadSubtitles = true,
                embedThumbnail = true,
            ),
            type = ShareMediaType.VIDEO,
        )

        assertEquals(MediaMode.VIDEO, request.mode)
        assertEquals(VideoQuality.P1080, request.quality)
        assertTrue(request.downloadSubtitles)
        assertTrue(request.automaticCaptions)
        assertTrue(request.embedSubtitles)
        assertFalse(request.embedThumbnail)
        assertEquals(URL, request.url)
    }

    @Test
    fun audioRequestsOnlyEmbedThumbnailsForLossyFormats() {
        val mp3 = ShareRequestBuilder.build(
            URL,
            ShareSheetPreferences(audioFormat = AudioFormat.MP3, embedThumbnail = true),
            ShareMediaType.AUDIO,
        )
        assertEquals(MediaMode.AUDIO, mp3.mode)
        assertEquals(AudioFormat.MP3, mp3.audioFormat)
        assertTrue(mp3.embedThumbnail)
        assertFalse(mp3.embedSubtitles)
        assertFalse(mp3.automaticCaptions)

        val wav = ShareRequestBuilder.build(
            URL,
            ShareSheetPreferences(audioFormat = AudioFormat.WAV, embedThumbnail = true),
            ShareMediaType.AUDIO,
        )
        assertFalse(wav.embedThumbnail)

        val opus = ShareRequestBuilder.build(
            URL,
            ShareSheetPreferences(audioFormat = AudioFormat.OPUS, embedThumbnail = true),
            ShareMediaType.AUDIO,
        )
        assertTrue(opus.embedThumbnail)

        val clamped = ShareRequestBuilder.build(
            URL,
            ShareSheetPreferences(mp3Bitrate = 999),
            ShareMediaType.AUDIO,
        )
        assertEquals(320, clamped.mp3Bitrate)
    }

    @Test
    fun commandRequestsDoNotInheritMediaOnlyOptions() {
        val template = YtDlpCommandTemplate(
            id = "template-1",
            name = "Rate limit",
            arguments = listOf("--limit-rate", "2M"),
        )
        val request = ShareRequestBuilder.build(
            url = URL,
            preferences = ShareSheetPreferences(
                quality = VideoQuality.P360,
                downloadSubtitles = true,
                embedThumbnail = true,
                useCommands = true,
            ),
            type = ShareMediaType.COMMANDS,
            customTemplate = template,
        )

        assertEquals("template-1", request.commandTemplateId)
        assertEquals("Rate limit", request.commandTemplateName)
        assertEquals(listOf("--limit-rate", "2M"), request.commandTemplateArguments)
        assertFalse(request.downloadSubtitles)
        assertFalse(request.embedSubtitles)
        assertFalse(request.embedThumbnail)
    }

    @Test
    fun hostLabelDropsOnlyTheWwwPrefix() {
        assertEquals("example.test", "https://www.example.test/watch?v=1".hostLabel())
        assertEquals("cdn.example.test", "https://cdn.example.test/a".hostLabel())
        assertEquals("not a url", "not a url".hostLabel())
    }

    private companion object {
        const val URL = "https://example.test/watch?v=aqz"
    }
}
