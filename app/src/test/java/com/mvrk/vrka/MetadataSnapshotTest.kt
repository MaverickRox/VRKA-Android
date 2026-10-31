package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * One deterministic yt-dlp `--dump-single-json` document stands in for every site: separate
 * video/audio streams, a progressive stream, exact and approximate sizes, a missing size and
 * both manual and automatic subtitle tracks. These are contract tests for the local
 * calculation; they are not evidence of a live site extraction.
 */
class MetadataSnapshotTest {
    private val request = DownloadRequest(url = URL)

    @Test
    fun oneDocumentExposesEveryResolutionWithoutDoubleCounting() {
        val snapshot = snapshot()
        assertNotNull(snapshot)

        assertEquals(listOf(1080, 720, 480), snapshot!!.videoOptions.map { it.height })
        // 1080p merged pair: exact video + exact audio summed once.
        assertEquals(53000000L, snapshot.sizeFor(1080)?.bytes)
        // 720p merged pair uses the approximate video size plus the best audio stream.
        assertEquals(21000000L, snapshot.sizeFor(720)?.bytes)
        // 480p is progressive: the audio stream must not be added again.
        assertEquals(5000000L, snapshot.sizeFor(480)?.bytes)
        assertNull(snapshot.sizeFor(360))
    }

    @Test
    fun localOptionSizesMatchTheSelectedEstimate() {
        val p1080 = SizeEstimateCalculator.fromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, quality = VideoQuality.P1080),
            recalculateSelection = true,
        )
        val p720 = SizeEstimateCalculator.fromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, quality = VideoQuality.P720),
            recalculateSelection = true,
        )

        assertEquals(53000000L, p1080.bytes)
        assertEquals(snapshot()!!.sizeFor(720)?.bytes, p720.bytes)
        assertEquals(21000000L, p720.bytes)
    }

    @Test
    fun sixtyFpsPreferenceStaysWithinEachHeight() {
        val fpsDocument = """
            {
              "title": "Fps",
              "duration": 100,
              "formats": [
                {"format_id": "v1080-30", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "filesize": 40000000},
                {"format_id": "v720-60", "vcodec": "avc1", "acodec": "none", "height": 720, "fps": 60, "filesize": 20000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 3000000}
              ]
            }
        """.trimIndent()

        val standard = SizeEstimateCalculator.snapshotFromMetadataJson(fpsDocument, request)
        val sixty = SizeEstimateCalculator.snapshotFromMetadataJson(
            fpsDocument,
            request.copy(prefer60Fps = true),
        )

        // Each height keeps its own streams; the 60fps preference never makes a 1080p chip
        // report the size of a 720p stream.
        assertEquals(43000000L, standard?.sizeFor(1080)?.bytes)
        assertEquals(43000000L, sixty?.sizeFor(1080)?.bytes)
        assertEquals(23000000L, sixty?.sizeFor(720)?.bytes)
        assertEquals(60.0, sixty?.sizeFor(720)?.fps ?: 0.0, 0.01)

        // The transfer selector itself may prefer a lower resolution for 60fps, and the
        // estimate must follow that selection.
        val estimate = SizeEstimateCalculator.fromMetadataJson(
            fpsDocument,
            DownloadRequest(url = URL, quality = VideoQuality.P1080, prefer60Fps = true),
            recalculateSelection = true,
        )
        assertEquals(23000000L, estimate.bytes)
    }

    @Test
    fun missingSizesLeaveThatHeightUnavailableInsteadOfGuessingFromAnotherStream() {
        val partial = """
            {
              "title": "Partial",
              "duration": 60,
              "formats": [
                {"format_id": "v2160", "vcodec": "avc1", "acodec": "none", "height": 2160, "tbr": 8000},
                {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 20000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 2000000}
              ]
            }
        """.trimIndent()

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(partial, request)
        // 2160p has a bitrate, so it is estimated, but never from the 1080p filesize.
        assertEquals(62000000L, snapshot?.sizeFor(2160)?.bytes)
        assertEquals(22000000L, snapshot?.sizeFor(1080)?.bytes)
    }

    @Test
    fun trimScalesEveryOptionByTheSelectedRange() {
        val trimmed = SizeEstimateCalculator.snapshotFromMetadataJson(
            DOCUMENT,
            request.copy(trimStart = "00:00:00", trimEnd = "00:00:30"),
        )

        // The document is 60 seconds long, so the trimmed option is half the full size.
        assertEquals(26500000L, trimmed?.sizeFor(1080)?.bytes)
    }

    @Test
    fun subtitlesCombineManualAndAutomaticTracksAndIgnoreLiveChat() {
        val snapshot = snapshot()!!

        assertEquals(listOf("de", "en", "es", "fr", "hi"), snapshot.subtitleLanguages)
        assertEquals(listOf("de", "en"), snapshot.automaticCaptionLanguages)
        assertTrue(snapshot.hasSubtitles)
        assertEquals(
            "German, English, Spanish +2",
            subtitleSummary(snapshot, Locale.US),
        )
    }

    @Test
    fun playlistDocumentIsRecognisedWithoutInventingEntryFormats() {
        val playlist = """
            {
              "_type": "playlist",
              "title": "A list",
              "entries": [
                {"title": "One", "formats": [{"format_id": "v", "vcodec": "avc1", "acodec": "mp4a", "height": 720, "filesize": 1000000}]}
              ]
            }
        """.trimIndent()

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(playlist, request)
        assertEquals("A list", snapshot?.title)
        assertTrue(snapshot?.isPlaylist == true)
        assertTrue(snapshot?.videoOptions.isNullOrEmpty())
        assertFalse(snapshot!!.hasSubtitles)
    }

    @Test
    fun malformedDocumentProducesNoSnapshot() {
        assertNull(SizeEstimateCalculator.snapshotFromMetadataJson("not json", request))
    }

    @Test
    fun diagnosticsSummaryDescribesTheResponseWithoutPersistingTheDocument() {
        val summary = SizeEstimateCalculator.summarizeMetadataJson(DOCUMENT)

        assertTrue(summary.contains("title=Fixture"))
        assertTrue(summary.contains("duration=60.0s"))
        assertTrue(summary.contains("formats=5"))
        assertTrue(summary.contains("heights=1080,720,480"))
        assertTrue(summary.contains("video=2 audio=2 muxed=1"))
        assertTrue(summary.contains("subtitles=de,en,es,fr,hi"))
        assertTrue(summary.contains("autoCaptions=de,en"))
        assertFalse(summary.contains("live_chat"))
        assertFalse(summary.contains("50000000"))
    }

    private fun snapshot(): MetadataSnapshot? =
        SizeEstimateCalculator.snapshotFromMetadataJson(DOCUMENT, request)

    private companion object {
        const val URL = "https://example.test/watch"

        val DOCUMENT = """
            {
              "title": "Fixture",
              "duration": 60,
              "formats": [
                {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "filesize": 50000000},
                {"format_id": "v720", "vcodec": "vp9", "acodec": "none", "height": 720, "fps": 30, "filesize_approx": 18000000},
                {"format_id": "p480", "vcodec": "avc1", "acodec": "mp4a", "height": 480, "filesize": 5000000},
                {"format_id": "a128", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128, "filesize": 1000000},
                {"format_id": "a160", "vcodec": "none", "acodec": "opus", "abr": 160, "filesize": 3000000}
              ],
              "subtitles": {"en": [{"ext": "srt"}], "hi": [{"ext": "srt"}], "es": [{"ext": "srt"}], "fr": [{"ext": "srt"}], "de": [{"ext": "srt"}], "live_chat": [{"ext": "json"}]},
              "automatic_captions": {"en": [{"ext": "vtt"}], "de": [{"ext": "vtt"}]}
            }
        """.trimIndent()
    }
}
