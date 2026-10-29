package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun mergedExactComponentsAreSummedAndReportedAsExact() {
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

        assertEquals(SizeEstimateKind.EXACT, estimate.kind)
        assertEquals(1_250_000L, estimate.bytes)
        assertEquals("Merged sample", estimate.title)
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
    fun preflightUsesTheSameFormatSelectorAsTheTransfer() {
        val requests = listOf(
            DownloadRequest(url = URL, quality = VideoQuality.P1080, prefer60Fps = true),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3, mp3Bitrate = 192),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
            DownloadRequest(url = URL, mode = MediaMode.AUDIO, audioFormat = AudioFormat.WAV),
        )

        requests.forEach { request ->
            val transfer = DownloadRequestFactory.download(
                DownloadJob(id = "test-job", request = request),
                staging,
            )
            val preflight = DownloadRequestFactory.sizeEstimate(request)

            assertEquals(transfer.getOption("-f"), preflight.getOption("-f"))
            assertTrue(preflight.hasOption("--dump-single-json"))
            assertTrue(preflight.hasOption("--simulate"))
            assertTrue(preflight.hasOption("--no-playlist"))
            assertFalse(preflight.hasOption("--write-subs"))
            assertFalse(preflight.hasOption("--extract-audio"))
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

    private fun estimate(request: DownloadRequest, json: String): SizeEstimate =
        SizeEstimateCalculator.fromMetadataJson(json, request)

    private companion object {
        const val URL = "https://example.test/video"
    }
}
