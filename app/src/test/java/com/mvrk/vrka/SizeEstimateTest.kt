package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SizeEstimateTest {
    private val staging = File("build/size-estimate-test")

    @Test
    fun sizeConfirmationIsEnabledByDefaultAndPersistsOptOut() {
        assertEquals(true, SizeConfirmationPreference.resolve(null))
        assertEquals(true, SizeConfirmationPreference.resolve(true))
        assertEquals(false, SizeConfirmationPreference.resolve(false))
        assertEquals(true, AppSettings().showDownloadSizeConfirmation)
    }

    @Test
    fun staleResponsesAreRejectedAfterANewerSelection() {
        val gate = SizeEstimateGate()
        val first = gate.begin()
        assertTrue(gate.accepts(first))

        val second = gate.begin()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))

        gate.invalidate()
        assertFalse(gate.accepts(second))
    }

    @Test
    fun mergedExactComponentsAreSummedButNeverClaimedAsExact() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            {
              "title": "Merged sample",
              "duration": 100,
              "requested_formats": [
                {"format_id": "137", "filesize": 1000000},
                {"format_id": "140", "filesize": 250000}
              ]
            }
            """,
        )

        // Muxing into a new container changes the final byte count, so only the sum is known.
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(1_250_000L, estimate.bytes)
        assertEquals("merged", estimate.detail)
        assertEquals("Merged sample", estimate.title)
    }

    @Test
    fun singleFormatWithExactFilesizeStaysExact() {
        val estimate = estimate(
            DownloadRequest(url = URL, quality = VideoQuality.P480),
            """{"title": "Progressive", "filesize": 900000}""",
        )

        assertEquals(SizeEstimateKind.EXACT, estimate.kind)
        assertEquals(900_000L, estimate.bytes)
    }

    @Test
    fun nullFilesizeFallsBackToFilesizeApproxAndFormatsTable() {
        // Shape captured from a real yt-dlp run: merged `filesize` is null while the
        // selected components still carry exact/approximate sizes.
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            {
              "_type": "video",
              "title": "Me at the zoo",
              "duration": 18.9,
              "filesize": null,
              "filesize_approx": 475961,
              "requested_formats": [
                {"format_id": "395", "ext": "mp4", "filesize": 223779, "filesize_approx": 223778, "tbr": 94.556},
                {"format_id": "251", "ext": "webm", "filesize": 252182, "filesize_approx": 252180, "tbr": 106.064}
              ],
              "formats": [
                {"format_id": "395", "filesize": 223779},
                {"format_id": "251", "filesize": 252182}
              ]
            }
            """,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(223_779L + 252_182L, estimate.bytes)
        assertEquals("Me at the zoo", estimate.title)
    }

    @Test
    fun realisticMergedMetadataFromYtDlpIsEstimatedFromComponents() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("ytdlp/youtube-merged.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val estimate = estimate(DownloadRequest(url = URL), json)

        // Captured shape: top-level filesize is null, the selected streams live under
        // requested_downloads[0].requested_formats, and their sum is only an approximation.
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(475_961L, estimate.bytes)
        assertEquals("merged", estimate.detail)
        assertEquals("Me at the zoo", estimate.title)
    }

    @Test
    fun singleDownloadMetadataWithoutRequestedFormatsUsesTheDownloadsEntry() {
        val estimate = estimate(
            DownloadRequest(url = URL, quality = VideoQuality.P480),
            """
            {
              "title": "Single download",
              "duration": 30,
              "filesize": null,
              "requested_downloads": [{"format_id": "18", "filesize": 500000}]
            }
            """,
        )

        assertEquals(SizeEstimateKind.EXACT, estimate.kind)
        assertEquals(500_000L, estimate.bytes)
    }

    @Test
    fun missingComponentSizesResolveThroughTheFormatsTable() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            {
              "title": "Lookup",
              "duration": 20,
              "requested_formats": [
                {"format_id": "137", "ext": "mp4"},
                {"format_id": "140", "ext": "m4a"}
              ],
              "formats": [
                {"format_id": "137", "filesize": 8000000},
                {"format_id": "140", "filesize": 320000},
                {"format_id": "137", "filesize": 1},
                {"format_id": "999", "filesize": 1}
              ]
            }
            """,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(8_320_000L, estimate.bytes)
    }

    @Test
    fun missingSizesFallBackToBitrateAndDuration() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            {
              "title": "Bitrate fallback",
              "duration": 100,
              "requested_formats": [
                {"format_id": "v", "tbr": 800},
                {"format_id": "a", "tbr": 128}
              ]
            }
            """,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals((800 + 128) * 125L * 100L, estimate.bytes)
    }

    @Test
    fun leadingProcessNoticesDoNotHideValidJson() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            [youtube] Extracting URL: https://example.test/video
            [youtube] jNQXAC9IVRw: Downloading webpage
            {"title": "Notice tolerant", "duration": 10, "filesize": 123456}
            """.trimIndent(),
        )

        assertEquals(SizeEstimateKind.EXACT, estimate.kind)
        assertEquals(123_456L, estimate.bytes)
        assertEquals("Notice tolerant", estimate.title)
    }

    @Test
    fun audioOnlyEstimateUsesTheSelectedStreamSize() {
        val estimate = estimate(
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
            """
            {
              "title": "Audio",
              "duration": 60,
              "requested_formats": [{"format_id": "251", "filesize": 750000}]
            }
            """,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(750_000L, estimate.bytes)
        assertEquals("source", estimate.detail)
    }

    @Test
    fun unavailableEstimateCarriesADiagnosticReason() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """{"title": "No sizes", "duration": 60, "requested_formats": [{"format_id": "v"}, {"format_id": "a"}]}""",
        )

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertTrue(estimate.diagnostic.contains("no filesize"))
    }

    @Test
    fun approximateComponentDowngradesTheWholeEstimate() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """
            {
              "duration": 100,
              "requested_formats": [
                {"filesize_approx": 900000},
                {"filesize": 100000}
              ]
            }
            """,
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(1_000_000L, estimate.bytes)
    }

    @Test
    fun missingMetadataReportsUnavailableInsteadOfGuessing() {
        val estimate = estimate(
            DownloadRequest(url = URL),
            """{"title": "No sizes", "duration": 60}""",
        )

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertEquals(null, estimate.bytes)
        assertFalse(estimate.isAvailable)
    }

    @Test
    fun mp3EstimateUsesTheRequestedBitrateAndDuration() {
        val estimate = estimate(
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3, mp3Bitrate = 320),
            """{"duration": 10, "filesize": 50000}""",
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(400_000L, estimate.bytes)
    }

    @Test
    fun mp3WithoutDurationFallsBackToSourceSizeAsApproximate() {
        val estimate = estimate(
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3),
            """{"filesize": 50000}""",
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(50_000L, estimate.bytes)
        assertEquals("source", estimate.detail)
    }

    @Test
    fun wavEstimateUsesPcmGeometry() {
        val estimate = estimate(
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.WAV),
            """{"duration": 10, "asr": 44100, "audio_channels": 2}""",
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(1_764_000L, estimate.bytes)
    }

    @Test
    fun trimScalesTheEstimateAndNeverClaimsExactness() {
        val estimate = estimate(
            DownloadRequest(
                url = URL,
                trimStart = "00:00:10",
                trimEnd = "00:00:30",
            ),
            """{"duration": 100, "filesize": 1000000}""",
        )

        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals(200_000L, estimate.bytes)
        assertEquals("trim", estimate.detail)
    }

    @Test
    fun playlistEntriesAreSummedWhenEveryEntryHasMetadata() {
        val exact = estimate(
            DownloadRequest(url = URL, isPlaylist = true),
            """
            {
              "_type": "playlist",
              "title": "Playlist",
              "entries": [
                {"duration": 10, "filesize": 1000},
                {"duration": 10, "filesize": 2000}
              ]
            }
            """,
        )
        assertEquals(SizeEstimateKind.EXACT, exact.kind)
        assertEquals(3_000L, exact.bytes)
        assertEquals("Playlist", exact.title)

        val approximate = estimate(
            DownloadRequest(url = URL, isPlaylist = true),
            """
            {
              "_type": "playlist",
              "title": "Playlist",
              "entries": [
                {"duration": 10, "filesize": 1000},
                {"duration": 10, "filesize_approx": 2000}
              ]
            }
            """,
        )
        assertEquals(SizeEstimateKind.APPROXIMATE, approximate.kind)
        assertEquals(3_000L, approximate.bytes)
    }

    @Test
    fun partialPlaylistMetadataIsNotReportedAsACompleteSize() {
        val estimate = estimate(
            DownloadRequest(url = URL, isPlaylist = true),
            """
            {
              "_type": "playlist",
              "entries": [
                {"duration": 10, "filesize": 1000},
                {"duration": 10}
              ]
            }
            """,
        )

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertEquals(null, estimate.bytes)
    }

    @Test
    fun malformedMetadataIsUnavailable() {
        assertEquals(
            SizeEstimateKind.UNAVAILABLE,
            estimate(DownloadRequest(url = URL), "not json").kind,
        )
    }

    @Test
    fun preflightFetchesEveryFormatInOnePassWithoutASelector() {
        val requests = listOf(
            DownloadRequest(url = URL, quality = VideoQuality.P1080, prefer60Fps = true),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3, mp3Bitrate = 192),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.WAV),
        )

        requests.forEach { request ->
            val preflight = DownloadRequestFactory.sizeEstimate(request)

            // No `-f`: the document must describe every resolution, codec and subtitle so one
            // extraction can answer all later selections locally.
            assertNull(preflight.getOption("-f"))
            assertTrue(preflight.hasOption("--dump-single-json"))
            assertTrue(preflight.hasOption("--simulate"))
            assertTrue(preflight.hasOption("--no-playlist"))
            assertFalse(preflight.hasOption("--write-subs"))
            assertFalse(preflight.hasOption("--extract-audio"))
            assertEquals("10", preflight.getOption("--socket-timeout"))
            assertEquals("2", preflight.getOption("--retries"))
            assertFalse(preflight.hasOption("--legacy-server-connect"))
        }
    }

    @Test
    fun preflightHonorsPlaylistRangeWithoutDownloading() {
        val request = DownloadRequestFactory.sizeEstimate(
            DownloadRequest(
                url = URL,
                isPlaylist = true,
                playlistStart = 2,
                playlistEnd = 5,
            ),
        )

        assertEquals("2", request.getOption("--playlist-start"))
        assertEquals("5", request.getOption("--playlist-end"))
        assertFalse(request.hasOption("--no-playlist"))
        assertTrue(request.hasOption("--dump-single-json"))
    }

    @Test
    fun localRecalculationSelectsCachedFormatsForANewQuality() {
        val request = DownloadRequest(url = URL, quality = VideoQuality.P720)
        val estimate = localEstimate(request)

        // 720p video + best audio, from the cached table: no second extraction.
        assertEquals(9_000_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals("local-merged", estimate.detail)

        // Without local recalculation the document still describes the old 2160p selection.
        val stale = SizeEstimateCalculator.fromMetadataJson(FORMAT_DOCUMENT, request)
        assertEquals(101_000_000L, stale.bytes)
    }

    @Test
    fun localRecalculationPrefersSixtyFpsWhenRequested() {
        // yt-dlp's `bestvideo[height<=H][fps>=60]` accepts a lower resolution to get 60fps.
        val document = """
            {
              "title": "Fps preference",
              "duration": 100,
              "formats": [
                {"format_id": "v1080-30", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "filesize": 20000000},
                {"format_id": "v720-60", "vcodec": "avc1", "acodec": "none", "height": 720, "fps": 60, "filesize": 8000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 1000000}
              ]
            }
        """
        val base = DownloadRequest(url = URL, quality = VideoQuality.P1080, prefer60Fps = true)
        assertEquals(9_000_000L, localEstimate(base, document).bytes)
        assertEquals(21_000_000L, localEstimate(base.copy(prefer60Fps = false), document).bytes)
    }

    @Test
    fun localRecalculationNeverClaimsExactness() {
        val estimate = localEstimate(
            DownloadRequest(url = URL, quality = VideoQuality.P720),
            """
            {
              "title": "Progressive",
              "duration": 60,
              "formats": [
                {"format_id": "22", "vcodec": "avc1", "acodec": "mp4a", "height": 720, "filesize": 5000000}
              ]
            }
            """,
        )

        assertEquals(5_000_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals("local", estimate.detail)
    }

    @Test
    fun localRecalculationUsesDirectMediaSizeWithoutAFormatTable() {
        val estimate = localEstimate(
            DownloadRequest(url = URL, quality = VideoQuality.P480),
            """{"title": "Direct", "duration": 10, "filesize": 4000000}""",
        )

        assertEquals(4_000_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun localRecalculationSelectsBestAudioStream() {
        val estimate = localEstimate(
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
            """
            {
              "title": "Audio",
              "duration": 100,
              "formats": [
                {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 9000000},
                {"format_id": "a128", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128, "filesize": 1000000},
                {"format_id": "aopus", "vcodec": "none", "acodec": "opus", "abr": 160, "filesize": 1500000}
              ]
            }
            """,
        )

        assertEquals(1_500_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
    }

    @Test
    fun localRecalculationOfPlaylistEntriesUsesEachEntryTable() {
        val estimate = localEstimate(
            DownloadRequest(url = URL, quality = VideoQuality.P1080, isPlaylist = true),
            """
            {
              "_type": "playlist",
              "title": "List",
              "entries": [
                {
                  "title": "One",
                  "duration": 10,
                  "formats": [
                    {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 2000000},
                    {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 500000}
                  ]
                }
              ]
            }
            """,
        )

        assertEquals(2_500_000L, estimate.bytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, estimate.kind)
        assertEquals("playlist", estimate.detail)
    }

    private fun estimate(request: DownloadRequest, json: String): SizeEstimate =
        SizeEstimateCalculator.fromMetadataJson(json, request)

    private fun localEstimate(
        request: DownloadRequest,
        json: String = FORMAT_DOCUMENT,
    ): SizeEstimate = SizeEstimateCalculator.fromMetadataJson(json, request, recalculateSelection = true)

    private companion object {
        const val URL = "https://example.test/video"

        val FORMAT_DOCUMENT = """
            {
              "title": "Cached formats",
              "duration": 100,
              "formats": [
                {"format_id": "v2160", "vcodec": "avc1", "acodec": "none", "height": 2160, "fps": 30, "filesize": 100000000},
                {"format_id": "v1080-30", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "filesize": 20000000},
                {"format_id": "v1080-60", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 60, "filesize": 30000000},
                {"format_id": "v720", "vcodec": "avc1", "acodec": "none", "height": 720, "fps": 30, "filesize": 8000000},
                {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "abr": 128, "filesize": 1000000}
              ],
              "requested_formats": [
                {"format_id": "v2160", "filesize": 100000000},
                {"format_id": "a", "filesize": 1000000}
              ]
            }
        """
    }
}
