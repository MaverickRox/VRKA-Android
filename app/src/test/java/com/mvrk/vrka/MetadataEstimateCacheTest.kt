package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEstimateCacheTest {
    private var now = 1_000_000L

    private fun cache(maxEntries: Int = 4) = MetadataEstimateCache(
        maxEntries = maxEntries,
        ttlMs = 60_000L,
        unavailableTtlMs = 5_000L,
        clock = { now },
    )

    private val available = SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes = 12_000_000L)

    @Test
    fun availableEstimatesAreReusedWithinTheirTtl() {
        val cache = cache()
        val key = MetadataEstimateCache.keyFor(DownloadRequest(url = URL), "2026.01.01")
        cache.put(key, available)

        assertEquals(available, cache.get(key))

        now += 59_999L
        assertEquals(available, cache.get(key))
    }

    @Test
    fun staleEstimatesAreDropped() {
        val cache = cache()
        val key = MetadataEstimateCache.keyFor(DownloadRequest(url = URL), "2026.01.01")
        cache.put(key, available)

        now += 60_001L
        assertNull(cache.get(key))
        assertEquals(0, cache.size())
    }

    @Test
    fun failuresExpireFasterSoARetryCanRecover() {
        val cache = cache()
        val key = MetadataEstimateCache.keyFor(DownloadRequest(url = URL), "2026.01.01")
        cache.put(key, SizeEstimate.unavailable("timeout"))

        now += 4_000L
        assertEquals(SizeEstimateKind.UNAVAILABLE, cache.get(key)?.kind)
        now += 2_000L
        assertNull(cache.get(key))
    }

    @Test
    fun customCommandEstimatesAreNotCached() {
        val cache = cache()
        val key = MetadataEstimateCache.keyFor(DownloadRequest(url = URL), "2026.01.01")
        cache.put(key, SizeEstimate.unavailable("custom-command"))
        assertNull(cache.get(key))
    }

    @Test
    fun keysChangeWithSelectionRuntimeAndHeaders() {
        val base = DownloadRequest(url = URL, quality = VideoQuality.P1080)
        val baseKey = MetadataEstimateCache.keyFor(base, "2026.01.01")

        assertNotEquals(baseKey, MetadataEstimateCache.keyFor(base.copy(quality = VideoQuality.P720), "2026.01.01"))
        assertNotEquals(baseKey, MetadataEstimateCache.keyFor(base.copy(prefer60Fps = true), "2026.01.01"))
        assertNotEquals(baseKey, MetadataEstimateCache.keyFor(base.copy(trimStart = "00:01:00"), "2026.01.01"))
        assertNotEquals(baseKey, MetadataEstimateCache.keyFor(base, "2026.02.02"))
        assertNotEquals(baseKey, MetadataEstimateCache.keyFor(base.copy(isPlaylist = true), "2026.01.01"))
        assertNotEquals(
            baseKey,
            MetadataEstimateCache.keyFor(
                base.copy(customHeaders = mapOf("Referer" to "https://private.example/watch")),
                "2026.01.01",
            ),
        )
    }

    @Test
    fun keyNeverContainsHeaderValues() {
        val request = DownloadRequest(
            url = URL,
            customHeaders = mapOf("Cookie" to "session=super-secret-token", "Referer" to "https://private.example/a"),
        )
        val key = MetadataEstimateCache.keyFor(request, "2026.01.01")

        assertFalse(key.contains("super-secret-token"))
        assertFalse(key.contains("private.example"))
        assertTrue(key.startsWith(URL))
    }

    @Test
    fun cacheStaysBoundedAndEvictsLeastRecentlyUsed() {
        val cache = cache(maxEntries = 2)
        val first = MetadataEstimateCache.keyFor(DownloadRequest(url = "$URL/1"), "v")
        val second = MetadataEstimateCache.keyFor(DownloadRequest(url = "$URL/2"), "v")
        val third = MetadataEstimateCache.keyFor(DownloadRequest(url = "$URL/3"), "v")

        cache.put(first, available)
        cache.put(second, available)
        cache.get(first)
        cache.put(third, available)

        assertEquals(2, cache.size())
        assertEquals(available, cache.get(first))
        assertNull(cache.get(second))
        assertEquals(available, cache.get(third))
    }

    @Test
    fun documentKeyIgnoresFormatSelectionAndTrim() {
        val base = DownloadRequest(url = URL)
        val key = MetadataDocumentCache.keyFor(base, "2026.01.01")

        // The cached document contains every stream, so these changes only affect the local
        // recalculation and must not trigger another extraction.
        assertEquals(key, MetadataDocumentCache.keyFor(base.copy(quality = VideoQuality.P720), "2026.01.01"))
        assertEquals(key, MetadataDocumentCache.keyFor(base.copy(prefer60Fps = true), "2026.01.01"))
        assertEquals(
            key,
            MetadataDocumentCache.keyFor(
                base.copy(mode = MediaMode.AUDIO, audioFormat = AudioFormat.MP3, mp3Bitrate = 320),
                "2026.01.01",
            ),
        )
        assertEquals(key, MetadataDocumentCache.keyFor(base.copy(trimStart = "00:01:00"), "2026.01.01"))
    }

    @Test
    fun documentKeyTracksSourceRuntimeRangeAndHeaders() {
        val base = DownloadRequest(url = URL)
        val key = MetadataDocumentCache.keyFor(base, "2026.01.01")

        assertNotEquals(key, MetadataDocumentCache.keyFor(base.copy(url = "$URL/2"), "2026.01.01"))
        assertNotEquals(key, MetadataDocumentCache.keyFor(base, "2026.02.02"))
        assertNotEquals(
            key,
            MetadataDocumentCache.keyFor(base.copy(isPlaylist = true, playlistStart = 2), "2026.01.01"),
        )
        assertNotEquals(
            key,
            MetadataDocumentCache.keyFor(
                base.copy(resolvedHeaders = mapOf("Referer" to "https://private.example/a")),
                "2026.01.01",
            ),
        )
    }

    @Test
    fun documentCacheExpiresAndStaysBounded() {
        val cache = MetadataDocumentCache(maxEntries = 2, ttlMs = 60_000L, clock = { now })
        cache.put("a", MetadataDocumentCache.Document("{}", "video|best", now))
        cache.put("b", MetadataDocumentCache.Document("{}", "video|best", now))
        cache.get("a")
        cache.put("c", MetadataDocumentCache.Document("{}", "video|best", now))

        assertEquals(2, cache.size())
        assertTrue(cache.get("a") != null)
        assertNull(cache.get("b"))
        assertTrue(cache.get("c") != null)

        now += 60_001L
        assertNull(cache.get("a"))
        assertNull(cache.get("c"))
        assertEquals(0, cache.size())
    }

    private companion object {
        const val URL = "https://example.test/video"
    }
}
