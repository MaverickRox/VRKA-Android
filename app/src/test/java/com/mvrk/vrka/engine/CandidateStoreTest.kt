package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CandidateStore.
 * Verifies canonical identity, segment collapse, player lineage, lifecycle, pruning.
 */
class CandidateStoreTest {

    @Test
    fun `observe creates candidate for valid media URL`() {
        val store = CandidateStore()
        val candidate = store.observe(
            url = "https://cdn.example.com/video.mp4",
            contentType = "video/mp4",
            timestamp = 100.0,
        )
        assertNotNull(candidate)
        assertEquals(CandidateKind.DIRECT, candidate!!.kind)
        assertEquals(1, store.values().size)
    }

    @Test
    fun `observe rejects non-http URLs`() {
        val store = CandidateStore()
        assertNull(store.observe(url = "blob:https://example.com/abc", timestamp = 100.0))
        assertNull(store.observe(url = "data:video/mp4;base64,abc", timestamp = 100.0))
        assertEquals(0, store.values().size)
    }

    @Test
    fun `observe rejects non-media URLs`() {
        val store = CandidateStore()
        assertNull(store.observe(url = "https://example.com/page.html", timestamp = 100.0))
        assertNull(store.observe(url = "https://example.com/style.css", timestamp = 100.0))
        assertEquals(0, store.values().size)
    }

    @Test
    fun `observe deduplicates by canonical identity`() {
        val store = CandidateStore()
        // Same media, different auth tokens
        val c1 = store.observe(
            url = "https://cdn.example.com/video.mp4?token=abc&quality=high",
            timestamp = 100.0,
        )
        val c2 = store.observe(
            url = "https://cdn.example.com/video.mp4?token=xyz&quality=high",
            timestamp = 101.0,
        )
        assertNotNull(c1)
        assertNotNull(c2)
        assertEquals(1, store.values().size)
        assertEquals(c1!!.candidateId, c2!!.candidateId)
        // URL should be refreshed to latest
        assertTrue(c2.currentUrl.contains("token=xyz"))
        assertEquals(2, c2.requestCount)
    }

    @Test
    fun `observe collapses segments into parent manifest`() {
        val store = CandidateStore()
        // First observe the manifest
        val manifest = store.observe(
            url = "https://cdn.example.com/stream/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            timestamp = 100.0,
        )
        assertNotNull(manifest)
        assertEquals(CandidateKind.HLS, manifest!!.kind)
        assertEquals(0, manifest.segmentCount)

        // Then observe segments with parent URL
        store.observe(
            url = "https://cdn.example.com/stream/segment_001.ts",
            segmentParentUrl = "https://cdn.example.com/stream/master.m3u8",
            timestamp = 101.0,
        )
        store.observe(
            url = "https://cdn.example.com/stream/segment_002.ts",
            segmentParentUrl = "https://cdn.example.com/stream/master.m3u8",
            timestamp = 102.0,
        )

        // Should still be 1 candidate (manifest), with segment count updated
        assertEquals(1, store.values().size)
        val updated = store.values()[0]
        assertEquals(2, updated.segmentCount)
        assertEquals(3, updated.requestCount) // 1 manifest + 2 segments
    }

    @Test
    fun `observe tracks player source lineage`() {
        val store = CandidateStore()
        // Player initially plays a placeholder
        val placeholder = store.observe(
            url = "https://cdn.example.com/placeholder.mp4",
            playerId = "player-1",
            timestamp = 100.0,
        )
        // Player switches to actual content
        val actual = store.observe(
            url = "https://cdn.example.com/actual-video.mp4",
            playerId = "player-1",
            timestamp = 101.0,
        )

        assertNotNull(placeholder)
        assertNotNull(actual)
        assertEquals(CandidateLifecycle.REPLACED, placeholder!!.lifecycle)
        assertEquals(actual!!.candidateId, placeholder.replacedBy)
        assertEquals(listOf(placeholder.candidateId), actual.sourceLineage)
    }

    @Test
    fun `observe tracks playback state`() {
        val store = CandidateStore()
        val candidate = store.observe(
            url = "https://cdn.example.com/video.mp4",
            playing = true,
            timestamp = 100.0,
        )
        assertNotNull(candidate)
        assertTrue(candidate!!.playing)
        assertEquals(CandidateLifecycle.PLAYING, candidate.lifecycle)
        assertEquals(100.0, candidate.playbackStartedAt!!, 0.001)

        // Stop playback
        store.observe(
            url = "https://cdn.example.com/video.mp4",
            playing = false,
            timestamp = 110.0,
        )
        assertFalse(candidate.playing)
        assertEquals(CandidateLifecycle.PLAYABLE, candidate.lifecycle)
        assertEquals(10.0, candidate.sustainedPlaybackSeconds, 0.001)
    }

    @Test
    fun `select marks candidate as user-selected`() {
        val store = CandidateStore()
        val candidate = store.observe(
            url = "https://cdn.example.com/video.mp4",
            timestamp = 100.0,
        )!!
        store.select(candidate.candidateId)
        assertTrue(candidate.userSelected)
        assertEquals(CandidateLifecycle.SELECTED, candidate.lifecycle)
    }

    @Test
    fun `markHandoff records success and failure`() {
        val store = CandidateStore()
        val c1 = store.observe(url = "https://cdn.example.com/v1.mp4", timestamp = 100.0)!!
        val c2 = store.observe(url = "https://cdn.example.com/v2.mp4", timestamp = 100.0)!!

        store.markHandoff(c1.candidateId, success = true)
        assertEquals(CandidateLifecycle.HANDED_OFF, c1.lifecycle)

        store.markHandoff(c2.candidateId, success = false)
        assertEquals(CandidateLifecycle.FAILED_HANDOFF, c2.lifecycle)
    }

    @Test
    fun `prune removes stale candidates`() {
        val store = CandidateStore(maxAgeSeconds = 60.0)
        store.observe(url = "https://cdn.example.com/old.mp4", timestamp = 100.0)
        store.observe(url = "https://cdn.example.com/new.mp4", timestamp = 140.0) // within 60s of old
        assertEquals(2, store.values().size)

        store.prune(now = 200.0) // old is 100s stale (>60), new is 60s stale (borderline)
        assertEquals(1, store.values().size)
        assertEquals("https://cdn.example.com/new.mp4", store.values()[0].currentUrl)
    }

    @Test
    fun `prune retains selected candidates even if stale`() {
        val store = CandidateStore(maxAgeSeconds = 60.0)
        val candidate = store.observe(url = "https://cdn.example.com/video.mp4", timestamp = 100.0)!!
        store.select(candidate.candidateId)

        store.prune(now = 200.0) // 100s stale, but selected
        assertEquals(1, store.values().size)
    }

    @Test
    fun `bounded capacity prunes lowest-priority candidates`() {
        val store = CandidateStore(maxCandidates = 4)
        for (i in 1..6) {
            store.observe(url = "https://cdn.example.com/video$i.mp4", timestamp = 100.0 + i)
        }
        assertTrue(store.values().size <= 4)
    }

    @Test
    fun `storeOnlyWidgetShaped returns true only when all candidates are widget shaped`() {
        val store = CandidateStore()
        // Numeric stream ID with rendition suffix (e.g. sidebar live widget)
        store.observe(url = "https://live.example.com/hls/123456/stream_720p.m3u8", timestamp = 100.0)
        store.observe(url = "https://live.example.com/hls/987654/cam_480p.m3u8", timestamp = 101.0)
        
        assertTrue(store.storeOnlyWidgetShaped())
        assertFalse(store.hasUserStartedCandidate())

        // Now main content master playlist appears
        store.observe(
            url = "https://cdn.example.com/videos/master.m3u8",
            timestamp = 105.0,
            userStarted = true,
            primaryPlayer = true,
        )

        assertFalse(store.storeOnlyWidgetShaped())
        assertTrue(store.hasUserStartedCandidate())
    }

    @Test
    fun `clear removes all candidates`() {
        val store = CandidateStore()
        store.observe(url = "https://cdn.example.com/video.mp4", timestamp = 100.0)
        assertEquals(1, store.values().size)
        store.clear()
        assertEquals(0, store.values().size)
    }

    @Test
    fun `observe collapses segments using manifest stem and directory matching without parentUrl`() {
        val store = CandidateStore()
        val manifest = store.observe(
            url = "https://cdn.example.com/hls/movie_720p.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            timestamp = 100.0,
        )
        assertNotNull(manifest)
        assertEquals(0, manifest!!.segmentCount)

        // Observe segment that omits segmentParentUrl
        store.observe(
            url = "https://cdn.example.com/hls/movie_720p_h264_001.ts",
            timestamp = 101.0,
        )
        store.observe(
            url = "https://cdn.example.com/hls/movie_720p_h264_002.ts",
            timestamp = 102.0,
        )

        assertEquals(1, store.values().size)
        val updated = store.values()[0]
        assertEquals(2, updated.segmentCount)
    }
}
