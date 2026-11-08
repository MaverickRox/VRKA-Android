package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic estimation contracts for the exact shapes yt-dlp returns for progressive,
 * merged, HLS and DASH sources. Every fixture is local; no live site is contacted.
 */
class SizeEstimateAccuracyTest {

    @Test
    fun requestedFormatsAreAuthoritativeEvenWhenTheLocalAudioRankingDiffers() {
        val json = """
            {
              "title": "Audio preference",
              "duration": 18.933,
              "formats": [
                {"format_id": "395", "vcodec": "av01", "acodec": "none", "height": 240, "filesize": 223779},
                {"format_id": "140", "vcodec": "none", "acodec": "mp4a", "abr": 130, "filesize": 309288},
                {"format_id": "251", "vcodec": "none", "acodec": "opus", "abr": 106, "filesize": 252182}
              ],
              "requested_formats": [
                {"format_id": "395", "filesize": 223779},
                {"format_id": "251", "filesize": 252182}
              ]
            }
        """.trimIndent()

        val authored = SizeEstimateCalculator.fromMetadataJson(json, request())
        val local = SizeEstimateCalculator.fromMetadataJson(json, request(), recalculateSelection = true)

        // yt-dlp picked 251; a local abr ranking would pick 140. The displayed estimate must
        // follow the transfer's requested_formats, not the local ranking.
        assertEquals(475_961L, authored.bytes)
        assertEquals(533_067L, local.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, authored.kind)
        assertEquals("merged", authored.detail)
        assertEquals("395", authored.videoFormatId)
        assertEquals("251", authored.audioFormatId)
    }

    @Test
    fun eachResolutionReportsItsOwnAuthoritativeSize() {
        for ((quality, expected) in mapOf(
            VideoQuality.P1080 to 41_600_000L,
            VideoQuality.P720 to 21_600_000L,
            VideoQuality.P480 to 5_000_000L,
        )) {
            val json = documentFor(quality)
            val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(quality))

            assertEquals("quality $quality", expected, estimate.bytes)
        }
    }

    @Test
    fun hlsSelectsOneVariantAndOneAudioRendition() {
        val json = """
            {
              "title": "Master",
              "duration": 100,
              "formats": [
                {"format_id": "hls-1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "protocol": "m3u8_native", "tbr": 4500},
                {"format_id": "hls-720", "vcodec": "avc1", "acodec": "none", "height": 720, "protocol": "m3u8_native", "tbr": 2000},
                {"format_id": "hls-a128", "vcodec": "none", "acodec": "mp4a", "protocol": "m3u8_native", "abr": 128}
              ],
              "requested_formats": [
                {"format_id": "hls-720"},
                {"format_id": "hls-a128"}
              ]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(VideoQuality.P720),
        )

        // 2000 kbit/s * 100 s + 128 kbit/s * 100 s; the 1080p variant is not included.
        assertEquals((2000L + 128L) * 125L * 100L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertFalse(estimate.bytes!! > 30_000_000L)
    }

    @Test
    fun hlsMasterIsNeverSummedAcrossEveryVariant() {
        val json = """
            {
              "title": "Variant list",
              "duration": 100,
              "formats": [
                {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "protocol": "m3u8_native", "tbr": 4500},
                {"format_id": "v720", "vcodec": "avc1", "acodec": "none", "height": 720, "protocol": "m3u8_native", "tbr": 2000},
                {"format_id": "v480", "vcodec": "avc1", "acodec": "none", "height": 480, "protocol": "m3u8_native", "tbr": 900},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "protocol": "m3u8_native", "abr": 128}
              ],
              "requested_formats": [{"format_id": "v720"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(26_600_000L, estimate.bytes)
        // All variants would be more than 90 MB; only the selected 720p pair is counted.
        assertTrue(estimate.bytes!! < 50_000_000L)
    }

    @Test
    fun dashSelectedRepresentationUsesTheSelectedVideoAndAudioPair() {
        val json = """
            {
              "title": "DASH",
              "duration": 60,
              "formats": [
                {"format_id": "dash-1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "protocol": "http_dash_segments", "filesize_approx": 40000000},
                {"format_id": "dash-720", "vcodec": "avc1", "acodec": "none", "height": 720, "protocol": "http_dash_segments", "filesize_approx": 18000000},
                {"format_id": "dash-a", "vcodec": "none", "acodec": "mp4a", "protocol": "http_dash_segments", "filesize_approx": 2000000}
              ],
              "requested_formats": [
                {"format_id": "dash-720"},
                {"format_id": "dash-a"}
              ]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(20_000_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun fragmentEstimateUsesOnlyTheSelectedStreamsApproximateSize() {
        val json = """
            {
              "title": "Fragments",
              "duration": 30,
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "protocol": "m3u8_native", "filesize_approx": 9000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "protocol": "m3u8_native", "filesize_approx": 1500000}
              ],
              "requested_formats": [{"format_id": "v"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(10_500_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun audioOnlyUsesAbrWhenTbrIsMissing() {
        val json = """
            {
              "title": "Audio bitrate",
              "duration": 100,
              "formats": [
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "abr": 160}
              ],
              "requested_formats": [{"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
        )

        // 160 kbit/s for 100 seconds = 2,000,000 bytes.
        assertEquals(2_000_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun invalidCodecNullDoesNotTurnAStreamIntoAProgressiveOne() {
        val json = """
            {
              "title": "Null codecs",
              "duration": 10,
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": null, "height": 720, "filesize": 5000000},
                {"format_id": "a", "vcodec": null, "acodec": "mp4a", "filesize": 1000000}
              ]
            }
        """.trimIndent()

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            json,
            request(VideoQuality.P720),
        )

        assertEquals(6_000_000L, snapshot?.sizeFor(720)?.bytes)
    }

    @Test
    fun unknownDurationWithOnlyBitrateIsUnavailable() {
        val json = """
            {
              "title": "Unknown duration",
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "tbr": 2000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "tbr": 128}
              ],
              "requested_formats": [{"format_id": "v"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertNull(estimate.bytes)
    }

    @Test
    fun zeroOrNegativeBitrateIsNeverTurnedIntoASize() {
        val json = """
            {
              "title": "Invalid bitrate",
              "duration": 10,
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "tbr": 0},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "tbr": -128}
              ],
              "requested_formats": [{"format_id": "v"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
    }

    @Test
    fun byteOverflowIsReportedAsUnavailableInsteadOfANegativeSize() {
        val json = """
            {
              "title": "Too large",
              "duration": 10,
              "requested_formats": [
                {"format_id": "v", "filesize": 9223372036854775807},
                {"format_id": "a", "filesize": 10}
              ]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request())

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertEquals("overflow", estimate.detail)
        assertNull(estimate.bytes)
    }

    @Test
    fun bitrateUnitsAreKilobitsPerSecond() {
        val json = """
            {
              "title": "Units",
              "duration": 8,
              "requested_formats": [
                {"format_id": "a", "filesize": null, "tbr": 1000}
              ]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
        )

        // 1000 kbit/s for 8 s is 1,000,000 bytes, not 8,000,000.
        assertEquals(1_000_000L, estimate.bytes)
    }

    @Test
    fun h264CompatibilityModeNeverClaimsAnExactFinalSize() {
        val estimate = SizeEstimateCalculator.fromMetadataJson(
            """{"title":"Progressive","filesize":900000}""",
            request().copy(h264CompatibilityMode = true),
        )

        assertEquals(900_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun metadataSubtitleAndSponsorProcessingDowngradeExactness() {
        val plain = SizeEstimateCalculator.fromMetadataJson(
            """{"title":"Progressive","filesize":900000}""",
            request().copy(embedMetadata = false, embedThumbnail = false),
        )
        assertEquals(SizeEstimateKind.EXACT, plain.kind)

        val embedded = SizeEstimateCalculator.fromMetadataJson(
            """{"title":"Progressive","filesize":900000}""",
            request().copy(embedMetadata = true, embedThumbnail = false),
        )
        assertEquals(SizeEstimateKind.APPROXIMATE, embedded.kind)
        assertEquals("postprocessed", embedded.detail)

        val sponsor = SizeEstimateCalculator.fromMetadataJson(
            """{"title":"Progressive","filesize":900000}""",
            request().copy(embedMetadata = false, sponsorBlock = true),
        )
        assertEquals(SizeEstimateKind.APPROXIMATE, sponsor.kind)
    }

    @Test
    fun playlistEntriesWithoutRequestedFormatsFallBackToTheirOwnTable() {
        val json = """
            {
              "_type": "playlist",
              "title": "No requested formats",
              "entries": [
                {
                  "title": "One",
                  "duration": 10,
                  "formats": [
                    {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 2000000},
                    {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 500000}
                  ]
                },
                {
                  "title": "Two",
                  "duration": 10,
                  "formats": [
                    {"format_id": "p", "vcodec": "avc1", "acodec": "mp4a", "height": 480, "filesize": 1000000}
                  ]
                }
              ]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(VideoQuality.P720, isPlaylist = true),
        )

        assertEquals(3_500_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun duplicateFormatEntriesAreNeverCountedTwice() {
        val json = """
            {
              "title": "Duplicates",
              "duration": 10,
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 5000000},
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 5000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 1000000}
              ],
              "requested_formats": [{"format_id": "v"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(json, request(VideoQuality.P720))

        assertEquals(6_000_000L, estimate.bytes)
    }

    @Test
    fun snapshotBestOptionIsIndependentOfTheSelectedQualityChip() {
        val document = documentFor(VideoQuality.P720)
        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            document,
            request(VideoQuality.P720),
        )

        assertEquals(21_600_000L, snapshot?.sizeFor(720)?.bytes)
        assertEquals(41_600_000L, snapshot?.bestOption?.bytes)
        assertEquals(1080, snapshot?.bestOption?.height)
    }

    @Test
    fun staleResolutionResponseCannotReplaceANewerOne() {
        // Mirrors the UI contract: each request takes a monotonic token, and a token that is
        // no longer current is discarded even if its process finishes last.
        val gate = SizeEstimateGate()
        var displayed: SizeEstimate? = null
        var displayedRequest: DownloadRequest? = null
        val request360 = request(VideoQuality.P360)
        val request720 = request(VideoQuality.P720)

        val p360Token = gate.begin()
        val p720Token = gate.begin()
        val p720 = SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 22_600_000L)
        val p360 = SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 9_000_000L)

        if (gate.accepts(p720Token)) {
            displayed = p720
            displayedRequest = request720
        }
        if (gate.accepts(p360Token)) {
            displayed = p360
            displayedRequest = request360
        }

        assertEquals(p720, displayed)
        assertEquals(request720, displayedRequest)
    }

    @Test
    fun rapidSwitchingOnlyAcceptsTheFinalSelection() {
        val gate = SizeEstimateGate()
        val tokens = mutableMapOf<VideoQuality, Int>()
        for (quality in listOf(VideoQuality.P360, VideoQuality.P480, VideoQuality.P720, VideoQuality.P1080)) {
            tokens[quality] = gate.begin()
        }

        var displayed: SizeEstimate? = null
        // Completion order is deliberately reversed: the oldest request finishes last.
        for (quality in listOf(VideoQuality.P1080, VideoQuality.P720, VideoQuality.P480, VideoQuality.P360)) {
            val token = tokens.getValue(quality)
            if (gate.accepts(token)) {
                displayed = SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = quality.ordinal.toLong())
            }
        }

        assertEquals(VideoQuality.P1080.ordinal.toLong(), displayed?.bytes)
    }

    @Test
    fun resolutionChipUsesYtDlpCodecPreferenceNotMaximumBitrate() {
        val json = """
            {
              "title": "Codec preference",
              "duration": 600,
              "formats": [
                {"format_id": "401", "vcodec": "av01.0.13M.08", "acodec": "none", "height": 2160, "fps": 60, "filesize": 54000000},
                {"format_id": "315", "vcodec": "vp9", "acodec": "none", "height": 2160, "fps": 60, "filesize": 97000000},
                {"format_id": "400", "vcodec": "av01.0.11M.08", "acodec": "none", "height": 1440, "fps": 60, "filesize": 27000000},
                {"format_id": "308", "vcodec": "vp9", "acodec": "none", "height": 1440, "fps": 60, "filesize": 47000000},
                {"format_id": "251", "vcodec": "none", "acodec": "opus", "abr": 128, "filesize": 3000000}
              ]
            }
        """.trimIndent()

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            json,
            request(VideoQuality.P2160),
        )
        val fullPlan = SizeEstimateCalculator.planFromMetadataJson(
            json,
            request(VideoQuality.P2160),
        )
        val fullEstimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(VideoQuality.P2160),
            recalculateSelection = true,
        )

        // The VP9 stream has the higher bitrate, but yt-dlp's codec-order preference selects
        // AV1. The chip, the canonical plan and the selected estimate must all agree.
        assertEquals("401", fullPlan?.videoFormatId)
        assertEquals(54_000_000L + 3_000_000L, snapshot?.sizeFor(2160)?.bytes)
        assertEquals(fullPlan?.bytes, snapshot?.sizeFor(2160)?.bytes)
        assertEquals(fullPlan?.bytes, fullEstimate.bytes)

        assertEquals("400", SizeEstimateCalculator.planFromMetadataJson(
            json,
            request(VideoQuality.P1440),
        )?.videoFormatId)
        assertEquals(27_000_000L + 3_000_000L, snapshot?.sizeFor(1440)?.bytes)

        // Audio is counted once in every path.
        assertEquals(3_000_000L, (snapshot?.sizeFor(2160)?.bytes ?: 0L) - 54_000_000L)
    }

    @Test
    fun aDifferentCodecIsChosenOnlyWhenItIsTheSelectedFormat() {
        val vp9Only = """
            {
              "title": "VP9 only",
              "duration": 60,
              "formats": [
                {"format_id": "308", "vcodec": "vp9", "acodec": "none", "height": 1440, "filesize": 47000000},
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "filesize": 3000000}
              ],
              "requested_formats": [{"format_id": "308"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            vp9Only,
            request(VideoQuality.P1440),
        )
        assertEquals(50_000_000L, estimate.bytes)
        assertEquals("308", estimate.videoFormatId)

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            vp9Only,
            request(VideoQuality.P1440),
        )
        assertEquals(50_000_000L, snapshot?.sizeFor(1440)?.bytes)
    }

    @Test
    fun authoritativeVp9ChoiceIsNotReplacedByALocalAv1Preference() {
        val json = """
            {
              "title": "Revealed codec",
              "duration": 60,
              "formats": [
                {"format_id": "av1-1080", "vcodec": "av01", "acodec": "none", "height": 1080, "filesize": 10000000},
                {"format_id": "vp9-1080", "vcodec": "vp9", "acodec": "none", "height": 1080, "filesize": 20000000},
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "filesize": 3000000}
              ],
              "requested_formats": [{"format_id": "vp9-1080"}, {"format_id": "a"}]
            }
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(
            json,
            request(VideoQuality.P1080),
        )
        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            json,
            request(VideoQuality.P1080),
        )

        assertEquals("vp9-1080", estimate.videoFormatId)
        assertEquals(23_000_000L, estimate.bytes)
        assertEquals(23_000_000L, snapshot?.sizeFor(1080)?.bytes)
    }

    @Test
    fun resolutionChipsUseTheTransferAudioRendition() {
        val json = """
            {
              "title": "Chip audio",
              "duration": 100,
              "formats": [
                {"format_id": "v1080", "vcodec": "av01", "acodec": "none", "height": 1080, "filesize": 40000000},
                {"format_id": "v720", "vcodec": "av01", "acodec": "none", "height": 720, "filesize": 20000000},
                {"format_id": "140", "vcodec": "none", "acodec": "mp4a", "abr": 130, "filesize": 309288},
                {"format_id": "251", "vcodec": "none", "acodec": "opus", "abr": 106, "filesize": 252182}
              ],
              "requested_formats": [{"format_id": "v720"}, {"format_id": "251"}]
            }
        """.trimIndent()

        val snapshot = SizeEstimateCalculator.snapshotFromMetadataJson(
            json,
            request(VideoQuality.P720),
        )

        // A local abr ranking would choose 140; the chips must use yt-dlp's 251.
        assertEquals(20_000_000L + 252_182L, snapshot?.sizeFor(720)?.bytes)
        assertEquals(40_000_000L + 252_182L, snapshot?.bestOption?.bytes)
    }

    @Test
    fun browserFallbackCandidateCannotReuseThePageLevelEstimate() {
        val pageRequest = request()
        val candidateRequest = DownloadRequestFactory.browserFallbackRequest(
            request = pageRequest,
            mediaUrl = "https://cdn.example.test/hls/master.m3u8",
            headers = mapOf("Referer" to "https://example.test/watch"),
        )

        assertEquals("https://cdn.example.test/hls/master.m3u8", candidateRequest.resolvedMediaUrl)
        assertTrue(
            "The candidate must have its own metadata key",
            MetadataEstimateCache.keyFor(pageRequest, VERSION) !=
                MetadataEstimateCache.keyFor(candidateRequest, VERSION),
        )
        // The candidate estimate resolves the selected manifest representation, not the page.
        val candidateEstimate = SizeEstimateCalculator.fromMetadataJson(
            """
            {
              "title": "Candidate",
              "duration": 100,
              "formats": [
                {"format_id": "720", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 20000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 2000000}
              ],
              "requested_formats": [{"format_id": "720"}, {"format_id": "a"}]
            }
            """.trimIndent(),
            candidateRequest,
        )
        assertEquals(22_000_000L, candidateEstimate.bytes)
    }

    private fun request(
        quality: VideoQuality = VideoQuality.BEST,
        mode: MediaMode = MediaMode.VIDEO,
        audioFormat: AudioFormat = AudioFormat.OPUS,
        isPlaylist: Boolean = false,
    ) = DownloadRequest(
        url = URL,
        quality = quality,
        mode = mode,
        audioFormat = audioFormat,
        isPlaylist = isPlaylist,
    )

    /** A fixture in which yt-dlp's requested_formats are different for every quality. */
    private fun documentFor(quality: VideoQuality): String {
        if (quality == VideoQuality.P480) {
            return """
                {
                  "title": "Resolution fixture",
                  "duration": 60,
                  "formats": [
                    {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 40000000},
                    {"format_id": "v720", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 20000000},
                    {"format_id": "p480", "vcodec": "avc1", "acodec": "mp4a", "height": 480, "filesize": 5000000},
                    {"format_id": "a", "vcodec": "none", "acodec": "opus", "filesize": 1600000}
                  ]
                }
            """.trimIndent()
        }
        val videoId = if (quality == VideoQuality.P1080) "v1080" else "v720"
        return """
            {
              "title": "Resolution fixture",
              "duration": 60,
              "formats": [
                {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 40000000},
                {"format_id": "v720", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 20000000},
                {"format_id": "p480", "vcodec": "avc1", "acodec": "mp4a", "height": 480, "filesize": 5000000},
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "filesize": 1600000}
              ],
              "requested_formats": [{"format_id": "$videoId"}, {"format_id": "a"}]
            }
        """.trimIndent()
    }

    private companion object {
        const val URL = "https://example.test/watch?v=fixture"
        const val VERSION = "2026.11.01"
    }
}
