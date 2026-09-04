package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for URL analysis utilities.
 * Verifies mediaKind(), isSegment(), isMasterManifest(), and canonicalMediaIdentity().
 */
class MediaUrlAnalysisTest {

    // --- mediaKind ---

    @Test
    fun `mediaKind classifies HLS by extension`() {
        assertEquals(CandidateKind.HLS, mediaKind("https://cdn.example.com/stream/master.m3u8"))
    }

    @Test
    fun `mediaKind classifies HLS by content type`() {
        assertEquals(CandidateKind.HLS, mediaKind("https://cdn.example.com/stream", "application/vnd.apple.mpegurl"))
        assertEquals(CandidateKind.HLS, mediaKind("https://cdn.example.com/stream", "application/x-mpegurl"))
    }

    @Test
    fun `mediaKind classifies DASH by extension`() {
        assertEquals(CandidateKind.DASH, mediaKind("https://cdn.example.com/stream/manifest.mpd"))
    }

    @Test
    fun `mediaKind classifies DASH by content type`() {
        assertEquals(CandidateKind.DASH, mediaKind("https://cdn.example.com/stream", "application/dash+xml"))
    }

    @Test
    fun `mediaKind classifies direct video by extension`() {
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/video.mp4"))
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/video.webm"))
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/video.mkv"))
    }

    @Test
    fun `mediaKind classifies direct audio by extension`() {
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/audio.mp3"))
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/audio.flac"))
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/audio.m4a"))
    }

    @Test
    fun `mediaKind classifies direct by content type`() {
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/stream", "video/mp4"))
        assertEquals(CandidateKind.DIRECT, mediaKind("https://cdn.example.com/stream", "audio/mpeg"))
    }

    @Test
    fun `mediaKind classifies other for non-media URLs`() {
        assertEquals(CandidateKind.OTHER, mediaKind("https://example.com/page.html"))
        assertEquals(CandidateKind.OTHER, mediaKind("https://example.com/style.css"))
        assertEquals(CandidateKind.OTHER, mediaKind("https://example.com/script.js"))
    }

    // --- isSegment ---

    @Test
    fun `isSegment detects TS segments`() {
        assertTrue(isSegment("https://cdn.example.com/stream/segment_001.ts"))
        assertTrue(isSegment("https://cdn.example.com/stream/data.ts"))
    }

    @Test
    fun `isSegment detects m4s segments`() {
        assertTrue(isSegment("https://cdn.example.com/stream/init.m4s"))
        assertTrue(isSegment("https://cdn.example.com/stream/chunk_000.m4s"))
    }

    @Test
    fun `isSegment detects path-based segments`() {
        assertTrue(isSegment("https://cdn.example.com/stream/seg-42/data"))
        assertTrue(isSegment("https://cdn.example.com/stream/segment12/index"))
        assertTrue(isSegment("https://cdn.example.com/stream/chunk-5/media"))
        assertTrue(isSegment("https://cdn.example.com/stream/frag3/data"))
    }

    @Test
    fun `isSegment detects codec-numbered segments`() {
        assertTrue(isSegment("https://cdn.example.com/stream/video_h264_001.mp4"))
        assertTrue(isSegment("https://cdn.example.com/stream/audio_aac_042.mp4"))
    }

    @Test
    fun `isSegment detects init fragments`() {
        assertTrue(isSegment("https://cdn.example.com/stream/video_h264_init_abc.mp4"))
    }

    @Test
    fun `isSegment rejects master playlists`() {
        assertFalse(isSegment("https://cdn.example.com/stream/master.m3u8"))
        assertFalse(isSegment("https://cdn.example.com/stream/playlist.m3u8"))
    }

    @Test
    fun `isSegment rejects direct media files`() {
        assertFalse(isSegment("https://cdn.example.com/movie.mp4"))
        assertFalse(isSegment("https://cdn.example.com/song.mp3"))
    }

    // --- isMasterManifest ---

    @Test
    fun `isMasterManifest detects generic master stems`() {
        assertTrue(isMasterManifest("https://cdn.example.com/stream/master.m3u8"))
        assertTrue(isMasterManifest("https://cdn.example.com/stream/playlist.m3u8"))
        assertTrue(isMasterManifest("https://cdn.example.com/stream/manifest.mpd"))
        assertTrue(isMasterManifest("https://cdn.example.com/stream/index.m3u8"))
    }

    @Test
    fun `isMasterManifest rejects variant playlists`() {
        assertFalse(isMasterManifest("https://cdn.example.com/stream/index-f1-v1-a1.m3u8"))
        assertFalse(isMasterManifest("https://cdn.example.com/stream/225371326_240p.m3u8"))
        assertFalse(isMasterManifest("https://cdn.example.com/stream/video_1080p.m3u8"))
    }

    // --- canonicalMediaIdentity ---

    @Test
    fun `canonicalMediaIdentity strips volatile auth tokens`() {
        val url1 = "https://cdn.example.com/stream/master.m3u8?token=abc123&quality=high"
        val url2 = "https://cdn.example.com/stream/master.m3u8?token=xyz789&quality=high"
        assertEquals(
            canonicalMediaIdentity(url1),
            canonicalMediaIdentity(url2),
        )
    }

    @Test
    fun `canonicalMediaIdentity preserves meaningful query params`() {
        val url1 = "https://cdn.example.com/stream/video.mp4?quality=high"
        val url2 = "https://cdn.example.com/stream/video.mp4?quality=low"
        assertNotEquals(
            canonicalMediaIdentity(url1),
            canonicalMediaIdentity(url2),
        )
    }

    @Test
    fun `canonicalMediaIdentity normalizes path slashes`() {
        val url1 = "https://cdn.example.com//stream///video.mp4"
        val url2 = "https://cdn.example.com/stream/video.mp4"
        assertEquals(
            canonicalMediaIdentity(url1),
            canonicalMediaIdentity(url2),
        )
    }

    @Test
    fun `canonicalMediaIdentity is stable across calls`() {
        val url = "https://cdn.example.com/stream/master.m3u8?sig=abcdef"
        val id1 = canonicalMediaIdentity(url)
        val id2 = canonicalMediaIdentity(url)
        assertEquals(id1, id2)
        assertEquals(24, id1.length)
    }

    @Test
    fun `canonicalMediaIdentity strips multiple volatile params`() {
        val url1 = "https://cdn.example.com/video.mp4?sig=abc&exp=123&x-amz-signature=def&format=mp4"
        val url2 = "https://cdn.example.com/video.mp4?sig=xyz&exp=456&x-amz-signature=ghi&format=mp4"
        assertEquals(
            canonicalMediaIdentity(url1),
            canonicalMediaIdentity(url2),
        )
    }
}
