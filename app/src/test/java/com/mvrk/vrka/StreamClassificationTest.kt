package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamClassificationTest {

    @Test
    fun muxedFormatsAreNeverClassifiedAsAudioOnly() {
        val metadata = StreamMetadata(
            formatId = "22",
            extension = "mp4",
            videoCodec = "avc1.64001F",
            audioCodec = "mp4a.40.2",
        )
        assertEquals(ActiveStream.VIDEO, StreamClassification.classify(metadata, "media.mp4"))
    }

    @Test
    fun videoOnlyAndAudioOnlyFormatsAreDistinguished() {
        val video = StreamMetadata("137", "mp4", "avc1.640028", "none")
        val audio = StreamMetadata("140", "m4a", "none", "mp4a.40.2")
        assertEquals(ActiveStream.VIDEO, StreamClassification.classify(video, "video.f137.mp4"))
        assertEquals(ActiveStream.AUDIO, StreamClassification.classify(audio, "audio.f140.m4a"))
    }

    @Test
    fun audioJobIsStillAudioWithoutUsingTheRequestedMode() {
        val audio = StreamMetadata("251", "webm", "none", "opus")
        assertEquals(ActiveStream.AUDIO, StreamClassification.classify(audio, "media.webm"))
    }

    @Test
    fun destinationExtensionIsOnlyAFallbackAndNeverTurnsVideoIntoAudio() {
        // MissAV-style muxed MP4: no reliable codec fields, but the destination is a video
        // container. The previous total-change heuristic could flip the stage to audio.
        assertEquals(ActiveStream.VIDEO, StreamClassification.classify(null, "media.mp4"))
        assertEquals(ActiveStream.AUDIO, StreamClassification.classify(null, "media.m4a"))
        assertEquals(ActiveStream.MEDIA, StreamClassification.classify(null, "media.bin"))
        assertEquals(ActiveStream.MEDIA, StreamClassification.classify(null, null))
    }

    @Test
    fun changingDenominatorOnOneStreamDoesNotCreateAnotherStream() {
        val tracker = DownloadSizeTracker()
        tracker.beginStream()
        val first = tracker.update(ProgressSizeUpdate(61L * 1024 * 1024, 3_600_000_000L, false))
        assertEquals(1, first.streamIndex)

        val revised = tracker.update(ProgressSizeUpdate(2_800_000_000L, 4_660_000_000L, false))
        assertEquals(1, revised.streamIndex)
        assertEquals(4_660_000_000L, revised.currentTotalBytes)
        assertEquals(4_660_000_000L, revised.aggregateTotalBytes)
    }

    @Test
    fun missavStyleMuxedHlsMetadataWithMissingCodecsStaysVideo() {
        // Captured shape from a real yt-dlp 2026.06.09 HLS master download:
        // __VRKA_STREAM__3000|mp4|NA|NA|1080
        val metadata = StreamMarker.parse("__VRKA_STREAM__3000|mp4|NA|NA|1080")
        assertEquals(ActiveStream.VIDEO, StreamClassification.classify(metadata, "media.mp4"))
    }

    @Test
    fun missavChangingDenominatorKeepsTheVideoStage() {
        // Controlled shape of the reported MissAV transfer: a muxed MP4 download whose
        // approximate total moves from 3.61 GiB to 4.66 GiB. No second Destination exists.
        val tracker = DownloadSizeTracker()
        tracker.beginStream()
        val initialKind = StreamClassification.classify(null, "media.mp4")
        val first = tracker.update(ProgressSizeUpdate(61L * 1024 * 1024, 3_876_000_000L, false))
        val revised = tracker.update(ProgressSizeUpdate(2_800_000_000L, 5_000_000_000L, false))

        assertEquals(ActiveStream.VIDEO, initialKind)
        assertEquals(1, first.streamIndex)
        assertEquals(1, revised.streamIndex)
        assertEquals(5_000_000_000L, revised.currentTotalBytes)
        assertEquals(5_000_000_000L, revised.aggregateTotalBytes)
        assertFalse("a changed denominator must remain approximate", revised.currentIsExact)
    }

    @Test
    fun missavCapturedProcessShapeReportsVideoThroughTheWholeSingleFile() {
        val lines = listOf(
            "__VRKA_STREAM__3000|mp4|NA|NA|1080",
            "__VRKA_RES__1080",
            "[download] Destination: media.mp4",
            "[download]   1.0% of  3.61GiB at 5.16MiB/s ETA 11:56",
            "[download]  60.0% of  4.66GiB at 5.15MiB/s ETA 06:10",
        )
        val tracker = DownloadSizeTracker()
        var metadata: StreamMetadata? = null
        var kind = ActiveStream.MEDIA
        var last = ProgressSizeUpdate(0, 0, false)

        for (line in lines) {
            val marker = StreamMarker.parse(line)
            if (marker != null) {
                metadata = marker
                continue
            }
            if (line.contains("Destination:", true)) {
                tracker.beginStream()
                kind = StreamClassification.classify(metadata, line.substringAfter("Destination:"))
                metadata = null
                continue
            }
            val sample = DownloadSizeParser.parseProgressLine(line)
            if (sample != null) {
                last = tracker.update(sample)
            }
        }

        assertEquals(ActiveStream.VIDEO, kind)
        assertEquals(1, last.streamIndex)
        val expectedTotal = (4.66 * 1024.0 * 1024.0 * 1024.0).toLong()
        assertEquals(expectedTotal, last.currentTotalBytes)
        assertEquals(expectedTotal, last.aggregateTotalBytes)
    }

    @Test
    fun downloadCommandEmitsTheAuthoritativeStreamMarker() {
        val staging = java.io.File(System.getProperty("java.io.tmpdir"), "vrka-stream-marker").apply { mkdirs() }
        val command = DownloadRequestFactory.download(
            DownloadJob(
                id = "stream-marker",
                request = DownloadRequest(
                    url = "https://example.test/video",
                    mode = MediaMode.VIDEO,
                    quality = VideoQuality.P1080,
                ),
            ),
            staging,
        ).buildCommand().toList()

        assertTrue(
            command.windowed(2).any { (option, value) ->
                option == "--print" && value == "before_dl:${StreamMarker.TEMPLATE}"
            },
        )
    }

    @Test
    fun streamMarkerIsParsedFromBeforeDlOutput() {
        val metadata = StreamMarker.parse(
            "__VRKA_STREAM__137+140|mp4|avc1.640028|mp4a.40.2|1080",
        )
        assertEquals("137+140", metadata?.formatId)
        assertEquals("mp4", metadata?.extension)
        assertEquals("avc1.640028", metadata?.videoCodec)
        assertEquals("mp4a.40.2", metadata?.audioCodec)
        assertEquals(1080, metadata?.height)
        assertNull(StreamMarker.parse("[download] 12% of 1GiB"))
    }

    @Test
    fun anExplicitNewDestinationStartsANewStage() {
        val tracker = DownloadSizeTracker()
        tracker.beginStream()
        tracker.update(ProgressSizeUpdate(1_000_000, 2_000_000, true))
        tracker.beginStream()
        val audio = tracker.update(ProgressSizeUpdate(100_000, 200_000, true))

        assertEquals(2, audio.streamIndex)
        assertEquals(200_000L, audio.currentTotalBytes)
        assertEquals(2_200_000L, audio.aggregateTotalBytes)
        assertNotEquals(ActiveStream.VIDEO, StreamClassification.classify(null, "audio.m4a"))
    }
}
