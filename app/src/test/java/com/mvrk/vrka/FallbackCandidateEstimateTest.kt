package com.mvrk.vrka

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FallbackCandidateEstimateTest {

    @Test
    fun candidateRequestCarriesTheSameUrlHeadersAndOptionsIntoEstimateAndTransfer() {
        val page = DownloadRequest(
            url = "https://page.example.test/watch?v=1",
            quality = VideoQuality.P1080,
            prefer60Fps = true,
            mode = MediaMode.VIDEO,
        )
        val candidate = DownloadRequestFactory.browserFallbackRequest(
            request = page,
            mediaUrl = "https://cdn.example.test/hls/master.m3u8?token=abc",
            headers = mapOf(
                "Referer" to "https://page.example.test/watch?v=1",
                "Origin" to "https://page.example.test",
                "User-Agent" to "VRKA-Test",
                "Cookie" to "session=candidate",
            ),
        )

        assertEquals("https://cdn.example.test/hls/master.m3u8?token=abc", candidate.resolvedMediaUrl)
        assertEquals(VideoQuality.P1080, candidate.quality)
        assertEquals(MediaMode.VIDEO, candidate.mode)
        assertTrue(candidate.prefer60Fps)
        assertTrue(candidate.customArguments.isEmpty())
        assertNull(candidate.commandTemplateId)

        val effective = HeaderValidation.resolveEffectiveHeaders(candidate)
        assertEquals("https://page.example.test/watch?v=1", effective["Referer"])
        assertEquals("https://page.example.test", effective["Origin"])
        assertEquals("VRKA-Test", effective["User-Agent"])
        assertEquals("session=candidate", effective["Cookie"])

        val estimateRequest = DownloadRequestFactory.sizeEstimate(candidate)
        val transferRequest = DownloadRequestFactory.download(
            DownloadJob(id = "candidate", request = candidate),
            File(System.getProperty("java.io.tmpdir"), "vrka-fallback-consistency").apply { mkdirs() },
        )
        assertEquals(estimateRequest.getOption("-f"), transferRequest.getOption("-f"))
        val estimateCommand = estimateRequest.buildCommand().toList()
        val transferCommand = transferRequest.buildCommand().toList()
        assertTrue(estimateCommand.any { it.contains("cdn.example.test/hls/master.m3u8") })
        assertTrue(transferCommand.any { it.contains("cdn.example.test/hls/master.m3u8") })
        listOf("Referer:", "Origin:", "User-Agent:", "Cookie:").forEach { header ->
            assertTrue(
                "estimate must carry $header",
                estimateCommand.windowed(2).any { (key, value) -> key == "--add-header" && value.startsWith(header, ignoreCase = true) },
            )
            assertTrue(
                "transfer must carry $header",
                transferCommand.windowed(2).any { (key, value) -> key == "--add-header" && value.startsWith(header, ignoreCase = true) },
            )
        }
    }

    @Test
    fun estimateAndTransferUseTheSameDefaultAgentAndRefererWhenTheCandidateHasNone() {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidate = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/direct.mp4",
            emptyMap(),
        )
        val estimate = DownloadRequestFactory.sizeEstimate(candidate).buildCommand().toList()
        val transfer = DownloadRequestFactory.download(
            DownloadJob(id = "defaults", request = candidate),
            File(System.getProperty("java.io.tmpdir"), "vrka-fallback-defaults").apply { mkdirs() },
        ).buildCommand().toList()

        for (command in listOf(estimate, transfer)) {
            assertTrue(command.windowed(2).any { (k, v) -> k == "--user-agent" && v.contains("Mozilla/5.0") })
            assertTrue(command.windowed(2).any { (k, v) -> k == "--referer" && v == page.url })
        }
    }

    @Test
    fun cacheIdentityCannotCrossPageOrCandidateBoundaries() {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidateA = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/a/master.m3u8",
            mapOf("Referer" to page.url),
        )
        val candidateB = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/b/master.m3u8",
            mapOf("Referer" to page.url),
        )
        val candidateAWithDifferentAuth = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/a/master.m3u8",
            mapOf("Referer" to page.url, "Cookie" to "session=another"),
        )

        val pageKey = MetadataEstimateCache.keyFor(page, VERSION)
        val aKey = MetadataEstimateCache.keyFor(candidateA, VERSION)
        val bKey = MetadataEstimateCache.keyFor(candidateB, VERSION)
        val aAuthKey = MetadataEstimateCache.keyFor(candidateAWithDifferentAuth, VERSION)

        assertNotEquals(pageKey, aKey)
        assertNotEquals(aKey, bKey)
        assertNotEquals(aKey, aAuthKey)
        assertNotEquals(
            MetadataDocumentCache.keyFor(page, VERSION),
            MetadataDocumentCache.keyFor(candidateA, VERSION),
        )
    }

    @Test
    fun anExpiredCandidateFailureIsNeverReplacedByThePageEstimate() = runBlocking {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidate = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://expired.example.test/media.mp4?expire=1&sig=expired",
            mapOf("Referer" to page.url),
        )
        val pageDocument = """
            {"title":"Page","duration":100,"formats":[
              {"format_id":"v","vcodec":"avc1","acodec":"none","height":1080,"filesize":8000000000},
              {"format_id":"a","vcodec":"none","acodec":"mp4a","filesize":100000000}
            ]}
        """.trimIndent()
        val repository = MetadataRepository(
            probe = { request ->
                if (request.resolvedMediaUrl == candidate.resolvedMediaUrl) {
                    preflight(SizeEstimate.unavailable("expired-candidate"), document = null)
                } else {
                    preflight(SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 1), pageDocument)
                }
            },
        )

        assertEquals(8_100_000_000L, repository.estimate(page, VERSION).bytes)
        val candidateEstimate = repository.estimate(candidate, VERSION)
        assertEquals(SizeEstimateKind.UNAVAILABLE, candidateEstimate.kind)
        assertEquals("expired-candidate", candidateEstimate.detail)
        assertNull(candidateEstimate.bytes)
    }

    @Test
    fun replacedAndExpiredCandidateEstimatesCannotApplyToANewerJobState() {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidateA = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/a.m3u8",
            mapOf("Referer" to page.url),
        )
        val candidateB = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/b.m3u8",
            mapOf("Referer" to page.url),
        )
        val urlA = candidateA.resolvedMediaUrl!!
        val jobA = DownloadJob(
            id = "job",
            request = candidateA,
            state = JobState.DOWNLOADING,
            attempt = 1,
        )

        assertTrue(FallbackEstimateIdentity.stillApplies(jobA, 1, urlA))
        assertFalse(FallbackEstimateIdentity.stillApplies(jobA.copy(request = candidateB), 1, urlA))
        assertFalse(FallbackEstimateIdentity.stillApplies(jobA.copy(attempt = 2), 1, urlA))
        assertFalse(FallbackEstimateIdentity.stillApplies(jobA.copy(state = JobState.DONE), 1, urlA))
        assertFalse(FallbackEstimateIdentity.stillApplies(null, 1, urlA))
    }

    @Test
    fun differentCandidatesProduceTheirOwnSizesAndUnavailableWhenMetadataIsMissing() {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidate720 = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/720/master.m3u8",
            emptyMap(),
        ).copy(quality = VideoQuality.P720)
        val candidate1080 = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/1080/master.m3u8",
            emptyMap(),
        ).copy(quality = VideoQuality.P1080)

        val document720 = """
            {"title":"720","duration":60,"formats":[
              {"format_id":"v720","vcodec":"avc1","acodec":"none","height":720,"filesize":20000000},
              {"format_id":"a","vcodec":"none","acodec":"mp4a","filesize":2000000}
            ],"requested_formats":[{"format_id":"v720"},{"format_id":"a"}]}
        """.trimIndent()
        val document1080 = """
            {"title":"1080","duration":60,"formats":[
              {"format_id":"v1080","vcodec":"avc1","acodec":"none","height":1080,"filesize":40000000},
              {"format_id":"a","vcodec":"none","acodec":"mp4a","filesize":2000000}
            ],"requested_formats":[{"format_id":"v1080"},{"format_id":"a"}]}
        """.trimIndent()
        val missing = """{"title":"missing","duration":60}"""

        assertEquals(22_000_000L, SizeEstimateCalculator.fromMetadataJson(document720, candidate720).bytes)
        assertEquals(42_000_000L, SizeEstimateCalculator.fromMetadataJson(document1080, candidate1080).bytes)
        assertEquals(
            SizeEstimateKind.UNAVAILABLE,
            SizeEstimateCalculator.fromMetadataJson(missing, candidate720).kind,
        )
    }

    @Test
    fun hlsCandidateCountsOnlyTheSelectedVariantAndAudioPair() {
        val page = DownloadRequest(url = "https://page.example.test/watch?v=1")
        val candidate = DownloadRequestFactory.browserFallbackRequest(
            page,
            "https://cdn.example.test/master.m3u8",
            emptyMap(),
        ).copy(quality = VideoQuality.P720)
        val document = """
            {"title":"master","duration":100,"formats":[
              {"format_id":"v1080","vcodec":"avc1","acodec":"none","height":1080,"tbr":4500},
              {"format_id":"v720","vcodec":"avc1","acodec":"none","height":720,"tbr":2000},
              {"format_id":"a","vcodec":"none","acodec":"mp4a","tbr":128}
            ],"requested_formats":[{"format_id":"v720"},{"format_id":"a"}]}
        """.trimIndent()

        val estimate = SizeEstimateCalculator.fromMetadataJson(document, candidate)
        assertEquals((2000L + 128L) * 125L * 100L, estimate.bytes)
        assertTrue(estimate.bytes!! < 50_000_000L)
    }

    private fun preflight(estimate: SizeEstimate, document: String?): SizePreflight =
        SizePreflight(
            estimate = estimate,
            startedAtEpochMs = 0,
            finishedAtEpochMs = 10,
            durationMs = 10,
            exitCode = if (document != null) 0 else null,
            timedOut = false,
            stderrTail = "",
            runtimeLabel = "test",
            formatSelector = "all",
            stdoutChars = document?.length ?: 0,
            document = document,
        )

    private companion object {
        const val VERSION = "2026.11.01"
    }
}
