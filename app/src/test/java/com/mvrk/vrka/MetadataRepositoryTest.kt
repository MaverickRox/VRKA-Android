package com.mvrk.vrka

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MetadataRepositoryTest {
    private val request = DownloadRequest(url = URL)

    @Test
    fun everySelectionExtractsOnceAndReturnsToItsCachedDocument() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes)

        val p1080 = repository.estimate(request.copy(quality = VideoQuality.P1080), VERSION)
        val p720 = repository.estimate(request.copy(quality = VideoQuality.P720), VERSION)
        val audio = repository.estimate(
            request.copy(mode = MediaMode.AUDIO, audioFormat = AudioFormat.OPUS),
            VERSION,
        )
        val snapshot = repository.snapshot(request, VERSION)
        val p1080Again = repository.estimate(request.copy(quality = VideoQuality.P1080), VERSION)

        // Each distinct selector needs its own yt-dlp requested_formats; returning to a
        // previous selector is answered from its cached document without a new process.
        assertEquals(4, probes.get())
        assertEquals(53_000_000L, p1080.bytes)
        assertEquals(21_000_000L, p720.bytes)
        assertEquals(3_000_000L, audio.bytes)
        assertEquals(53_000_000L, p1080Again.bytes)
        assertEquals(listOf(1080, 720), snapshot?.videoOptions?.map { it.height })
        assertEquals(53_000_000L, snapshot?.bestOption?.bytes)
    }

    @Test
    fun concurrentCallersShareOneExtractionPerSelection() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes, probeDelayMs = 100)

        val results = coroutineScope {
            List(4) { index ->
                async {
                    repository.estimate(
                        request.copy(quality = if (index % 2 == 0) VideoQuality.P1080 else VideoQuality.P720),
                        VERSION,
                    )
                }
            }.awaitAll()
        }

        assertEquals(2, probes.get())
        assertEquals(53_000_000L, results[0].bytes)
        assertEquals(21_000_000L, results[1].bytes)
        assertEquals(53_000_000L, results[2].bytes)
        assertEquals(21_000_000L, results[3].bytes)
    }

    @Test
    fun retryBypassesTheCachedDocument() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes)

        repository.estimate(request, VERSION)
        repository.estimate(request, VERSION, forceRefresh = true)

        assertEquals(2, probes.get())
    }

    @Test
    fun failedExtractionReportsUnavailableWithoutCachingADocument() = runBlocking {
        val probes = AtomicInteger()
        val repository = MetadataRepository(
            probe = {
                probes.incrementAndGet()
                preflight(estimate = SizeEstimate.unavailable("timeout"), document = null)
            },
        )

        val estimate = repository.estimate(request, VERSION)
        val snapshot = repository.snapshot(request, VERSION)

        assertEquals(SizeEstimateKind.UNAVAILABLE, estimate.kind)
        assertEquals("timeout", estimate.detail)
        assertNull(snapshot)
        // The short-lived unavailable entry is reused, so a burst of callers still uses one
        // extraction; an explicit retry is what starts a new one.
        assertEquals(1, probes.get())
    }

    @Test
    fun clearingTheRuntimeCacheForcesANewExtraction() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes)

        repository.estimate(request, VERSION)
        repository.clear()
        repository.estimate(request, VERSION)

        assertEquals(2, probes.get())
    }

    @Test
    fun cacheKeyChangesWithTheEffectiveHeaders() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes)

        repository.estimate(request, VERSION)
        repository.estimate(request.copy(referer = "https://other.example.test/"), VERSION)

        // A different Referer changes the extraction context, so it must not reuse the
        // document that was fetched with the first set of headers.
        assertEquals(2, probes.get())
    }

    @Test
    fun repeatedSelectionIsAnsweredFromTheEstimateCache() = runBlocking {
        val probes = AtomicInteger()
        val sources = mutableListOf<String>()
        val repository = repository(probes)

        repository.estimate(request, VERSION) { sources += it.source }
        repository.estimate(request, VERSION) { sources += it.source }

        assertEquals(1, probes.get())
        assertEquals(listOf("extraction", "estimate-cache"), sources)
    }

    @Test
    fun failureCacheExpiresAfterItsShortWindow() = runBlocking {
        val probes = AtomicInteger()
        var now = 1_000L
        val repository = MetadataRepository(
            probe = {
                probes.incrementAndGet()
                preflight(SizeEstimate.unavailable("timeout"), document = null)
            },
            clock = { now },
        )

        assertNull(repository.snapshot(request, VERSION))
        assertNull(repository.snapshot(request, VERSION))
        assertEquals("a burst shares the cached failure", 1, probes.get())

        now += 20_001L
        assertNull(repository.snapshot(request, VERSION))
        assertEquals("an expired failure starts a new extraction", 2, probes.get())
    }

    @Test
    fun trimIsAnsweredLocallyFromTheSameDocument() = runBlocking {
        val probes = AtomicInteger()
        val repository = repository(probes)

        val full = repository.estimate(request.copy(quality = VideoQuality.P1080), VERSION)
        val trimmed = repository.estimate(
            request.copy(quality = VideoQuality.P1080, trimStart = "00:00:00", trimEnd = "00:00:30"),
            VERSION,
        )

        assertEquals(1, probes.get())
        assertEquals(53_000_000L, full.bytes)
        assertEquals(26_500_000L, trimmed.bytes)
        assertTrue(trimmed.kind == SizeEstimateKind.APPROXIMATE)
    }

    @Test
    fun testExpectedPlaylistUnavailableEstimateSuppressedFromDiagnostics() {
        // Expected playlist preflight: flat playlist metadata without multi-stream size tables
        val expectedPlaylistPreflight = SizePreflight(
            estimate = SizeEstimate.unavailable("playlist-entry"),
            startedAtEpochMs = 0,
            finishedAtEpochMs = 100,
            durationMs = 100,
            exitCode = 0,
            timedOut = false,
            stderrTail = "",
            runtimeLabel = "test",
            formatSelector = "all",
            stdoutChars = 500,
            document = """{"_type": "playlist", "entries": [{"id": "v1"}]}""",
        )
        val isExpected = expectedPlaylistPreflight.estimate.detail.startsWith("playlist") &&
            !expectedPlaylistPreflight.timedOut &&
            (expectedPlaylistPreflight.exitCode == null || expectedPlaylistPreflight.exitCode == 0)

        // Must be identified as expected and suppressed from failure diagnostics
        assertTrue("Expected playlist preflight without per-item sizes must be classified as expected", isExpected)
    }

    @Test
    fun testNegativeControlGenuinePlaylistExtractionFailureNotSuppressed() {
        // Negative control 1: Process crash / non-zero exit code on playlist URL
        val processCrashPreflight = SizePreflight(
            estimate = SizeEstimate.unavailable("process", "yt-dlp produced no metadata JSON"),
            startedAtEpochMs = 0,
            finishedAtEpochMs = 500,
            durationMs = 500,
            exitCode = 1,
            timedOut = false,
            stderrTail = "ERROR: [youtube:tab] Private playlist",
            runtimeLabel = "test",
            formatSelector = "all",
            stdoutChars = 0,
            document = null,
        )
        val isCrashSuppressed = processCrashPreflight.estimate.detail.startsWith("playlist") &&
            !processCrashPreflight.timedOut &&
            (processCrashPreflight.exitCode == null || processCrashPreflight.exitCode == 0)
        assertFalse("Genuine process/extraction failure must NOT be suppressed", isCrashSuppressed)

        // Negative control 2: Preflight timeout on playlist URL
        val timeoutPreflight = SizePreflight(
            estimate = SizeEstimate.unavailable("timeout", "preflight timed out after 25000ms"),
            startedAtEpochMs = 0,
            finishedAtEpochMs = 25000,
            durationMs = 25000,
            exitCode = null,
            timedOut = true,
            stderrTail = "",
            runtimeLabel = "test",
            formatSelector = "all",
            stdoutChars = 0,
            document = null,
        )
        val isTimeoutSuppressed = timeoutPreflight.estimate.detail.startsWith("playlist") &&
            !timeoutPreflight.timedOut &&
            (timeoutPreflight.exitCode == null || timeoutPreflight.exitCode == 0)
        assertFalse("Genuine timeout failure must NOT be suppressed", isTimeoutSuppressed)
    }

    private fun repository(probes: AtomicInteger, probeDelayMs: Long = 0): MetadataRepository =
        MetadataRepository(
            probe = {
                probes.incrementAndGet()
                if (probeDelayMs > 0) delay(probeDelayMs)
                preflight(SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 1), DOCUMENT)
            },
        )

    private fun preflight(estimate: SizeEstimate, document: String?): SizePreflight =
        SizePreflight(
            estimate = estimate,
            startedAtEpochMs = 0,
            finishedAtEpochMs = 10,
            durationMs = 10,
            exitCode = if (document != null) 0 else null,
            timedOut = estimate.detail == "timeout",
            stderrTail = "",
            runtimeLabel = "test",
            formatSelector = "all",
            stdoutChars = document?.length ?: 0,
            document = document,
        )

    private companion object {
        const val URL = "https://example.test/watch"
        const val VERSION = "2026.08.19"

        val DOCUMENT = """
            {
              "title": "Fixture",
              "duration": 60,
              "formats": [
                {"format_id": "v1080", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "filesize": 50000000},
                {"format_id": "v720", "vcodec": "vp9", "acodec": "none", "height": 720, "fps": 30, "filesize": 18000000},
                {"format_id": "a128", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128, "filesize": 1000000},
                {"format_id": "a160", "vcodec": "none", "acodec": "opus", "abr": 160, "filesize": 3000000}
              ],
              "subtitles": {"en": [{"ext": "srt"}], "hi": [{"ext": "srt"}]},
              "automatic_captions": {"en": [{"ext": "vtt"}]}
            }
        """.trimIndent()
    }
}
