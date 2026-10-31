package com.mvrk.vrka

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The share card and the home screen must resolve the same URL, document and option to the
 * same format IDs and the same byte total. These fixtures stand in for a yt-dlp document;
 * they are contract tests for the canonical selection, not evidence of a live extraction.
 */
class FormatSelectionConsistencyTest {

    @Test
    fun shareAndHomeResolveEveryVideoOptionIdentically() {
        for (quality in VideoQuality.entries) {
            val home = DownloadRequest(url = URL, quality = quality)
            val share = ShareRequestBuilder.build(
                URL,
                ShareSheetPreferences(quality = quality),
                ShareMediaType.VIDEO,
            )

            val homePlan = SizeEstimateCalculator.planFromMetadataJson(DOCUMENT, home)
            val sharePlan = SizeEstimateCalculator.planFromMetadataJson(DOCUMENT, share)

            assertEquals("quality $quality", homePlan, sharePlan)
            assertEquals(
                "quality $quality estimate",
                SizeEstimateCalculator.fromMetadataJson(DOCUMENT, home, recalculateSelection = true),
                SizeEstimateCalculator.fromMetadataJson(DOCUMENT, share, recalculateSelection = true),
            )
        }
    }

    @Test
    fun bestAvailableUsesTheResolvedStreamsOnce() {
        val plan = SizeEstimateCalculator.planFromMetadataJson(DOCUMENT, DownloadRequest(url = URL))

        // 2160p60 HDR is the yt-dlp best selection; the best audio stream is added once.
        assertEquals("401", plan?.videoFormatId)
        assertEquals("251", plan?.audioFormatId)
        assertEquals(2160, plan?.height)
        assertEquals(60.0, plan?.fps ?: 0.0, 0.01)
        assertEquals(4_980_000_000L, plan?.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, plan?.kind)
    }

    @Test
    fun eachResolutionOptionResolvesItsOwnStreams() {
        val p2160 = plan(VideoQuality.P2160)
        val p1440 = plan(VideoQuality.P1440)
        val p1080 = plan(VideoQuality.P1080)

        assertEquals("401", p2160?.videoFormatId)
        assertEquals("308", p1440?.videoFormatId)
        assertEquals("399", p1080?.videoFormatId)
        assertEquals(2_550_000_000L, p1440?.bytes)
        assertEquals(1_990_000_000L, p1080?.bytes)
        assertEquals("251", p1440?.audioFormatId)
    }

    @Test
    fun sixtyFpsTierMatchesTheTransferSelector() {
        val standard = plan(VideoQuality.P2160)
        val sixty = SizeEstimateCalculator.planFromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, quality = VideoQuality.P2160, prefer60Fps = true),
        )

        assertEquals("401", standard?.videoFormatId)
        assertEquals("401", sixty?.videoFormatId)
        assertTrue((sixty?.fps ?: 0.0) >= 60.0)
        assertEquals(
            "bestvideo[height<=2160][fps>=60]+bestaudio/bestvideo[height<=2160]+bestaudio/best[height<=2160]/best",
            DownloadRequestFactory.formatSelectorFor(
                DownloadRequest(url = URL, quality = VideoQuality.P2160, prefer60Fps = true),
            ),
        )
    }

    @Test
    fun progressiveFallbackIsCountedOnce() {
        val plan = plan(VideoQuality.P360)

        assertEquals("18", plan?.videoFormatId)
        assertNull(plan?.audioFormatId)
        assertEquals(50_000_000L, plan?.bytes)
        // A local re-selection stays approximate even for a single exact stream.
        assertEquals(SizeEstimateKind.APPROXIMATE, plan?.kind)
    }

    @Test
    fun mergedSelectionIsAlwaysApproximateEvenWithExactComponents() {
        val estimate = SizeEstimateCalculator.fromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, quality = VideoQuality.P1440),
            recalculateSelection = true,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals("local-merged", estimate.detail)
        assertEquals("308", estimate.videoFormatId)
        assertEquals("251", estimate.audioFormatId)
    }

    @Test
    fun audioOnlySelectionUsesTheBestAudioStream() {
        val opus = SizeEstimateCalculator.planFromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
        )

        assertEquals("251", opus?.audioFormatId)
        assertNull(opus?.videoFormatId)
        assertEquals(100_000_000L, opus?.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, opus?.kind)
    }

    @Test
    fun missingSizeIsReportedInsteadOfBorrowedFromAnotherHeight() {
        val missing = """
            {
              "title": "Missing",
              "duration": 60,
              "formats": [
                {"format_id": "v2160", "vcodec": "av01", "acodec": "none", "height": 2160},
                {"format_id": "v1080", "vcodec": "av01", "acodec": "none", "height": 1080, "filesize": 20000000},
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "filesize": 2000000}
              ]
            }
        """.trimIndent()

        val plan = SizeEstimateCalculator.planFromMetadataJson(missing, DownloadRequest(url = URL))
        val estimate = SizeEstimateCalculator.fromMetadataJson(
            missing,
            DownloadRequest(url = URL),
            recalculateSelection = true,
        )

        assertNull(plan?.bytes)
        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertEquals("size-missing", estimate.detail)
    }

    @Test
    fun cachedAndRefreshedDocumentsProduceTheSameSelection() = runBlocking {
        val probes = AtomicInteger()
        val repository = MetadataRepository(
            probe = {
                probes.incrementAndGet()
                SizePreflight(
                    estimate = SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 1),
                    startedAtEpochMs = 0,
                    finishedAtEpochMs = 10,
                    durationMs = 10,
                    exitCode = 0,
                    timedOut = false,
                    stderrTail = "",
                    runtimeLabel = "test",
                    formatSelector = "all",
                    stdoutChars = DOCUMENT.length,
                    document = DOCUMENT,
                )
            },
        )

        val first = repository.estimate(DownloadRequest(url = URL, quality = VideoQuality.P1440), VERSION)
        val cached = repository.estimate(DownloadRequest(url = URL, quality = VideoQuality.P1440), VERSION)
        val refreshed = repository.estimate(
            DownloadRequest(url = URL, quality = VideoQuality.P1440),
            VERSION,
            forceRefresh = true,
        )

        val fresh = SizeEstimateCalculator.fromMetadataJson(
            DOCUMENT,
            DownloadRequest(url = URL, quality = VideoQuality.P1440),
            recalculateSelection = true,
        )
        assertEquals(fresh, first)
        assertEquals(first, cached)
        assertEquals(first, refreshed)
        assertEquals(2, probes.get())
    }

    @Test
    fun bestAvailableIsNotSimplyTheFirstFormatInTheDocument() {
        val reordered = DOCUMENT.replace(
            """{"format_id": "401",""",
            """{"format_id": "999","vcodec": "avc1","acodec": "none","height": 360,"fps": 30,"filesize": 1000},
             {"format_id": "401",""",
        )

        val plan = SizeEstimateCalculator.planFromMetadataJson(reordered, DownloadRequest(url = URL))
        assertEquals("401", plan?.videoFormatId)
        assertNotEquals("999", plan?.videoFormatId)
    }

    private fun plan(quality: VideoQuality): FormatPlan? =
        SizeEstimateCalculator.planFromMetadataJson(DOCUMENT, DownloadRequest(url = URL, quality = quality))

    private companion object {
        const val URL = "https://example.test/watch?v=fixture"
        const val VERSION = "2026.08.19"

        val DOCUMENT = """
            {
              "title": "Fixture",
              "duration": 600,
              "formats": [
                {"format_id": "401", "vcodec": "av01.0.13M.08", "acodec": "none", "height": 2160, "fps": 60, "hdr": "HDR10", "filesize": 4880000000},
                {"format_id": "313", "vcodec": "vp9", "acodec": "none", "height": 2160, "fps": 30, "filesize": 3900000000},
                {"format_id": "400", "vcodec": "vp9", "acodec": "none", "height": 2160, "fps": 60, "filesize": 4500000000},
                {"format_id": "308", "vcodec": "vp9", "acodec": "none", "height": 1440, "fps": 60, "filesize": 2450000000},
                {"format_id": "399", "vcodec": "av01.0.08M.08", "acodec": "none", "height": 1080, "fps": 60, "filesize": 1890000000},
                {"format_id": "251", "vcodec": "none", "acodec": "opus", "abr": 160, "filesize": 100000000},
                {"format_id": "140", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128, "filesize": 80000000},
                {"format_id": "18", "vcodec": "avc1", "acodec": "mp4a.40.2", "height": 360, "filesize": 50000000}
              ]
            }
        """.trimIndent()
    }
}
