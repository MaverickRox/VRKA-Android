package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MediaAssembly.
 * Verifies segment URL resolution, master playlist variant parsing, and segment sequencing.
 */
class MediaAssemblyTest {

    @Test
    fun `resolveSegmentUrl handles absolute URLs`() {
        val resolved = MediaAssembly.resolveSegmentUrl(
            "https://cdn.example.com/seg01.ts",
            "https://origin.example.com/media/playlist.m3u8"
        )
        assertEquals("https://cdn.example.com/seg01.ts", resolved)
    }

    @Test
    fun `resolveSegmentUrl handles origin relative URLs`() {
        val resolved = MediaAssembly.resolveSegmentUrl(
            "/stream/seg01.ts",
            "https://origin.example.com/media/hls/playlist.m3u8"
        )
        assertEquals("https://origin.example.com/stream/seg01.ts", resolved)
    }

    @Test
    fun `resolveSegmentUrl handles path relative URLs`() {
        val resolved = MediaAssembly.resolveSegmentUrl(
            "segment_1.ts",
            "https://origin.example.com/media/hls/playlist.m3u8?token=xyz"
        )
        assertEquals("https://origin.example.com/media/hls/segment_1.ts", resolved)
    }

    @Test
    fun `isHlsPlaylist correctly identifies playlists`() {
        assertTrue(MediaAssembly.isHlsPlaylist("https://cdn.example.com/master.m3u8"))
        assertTrue(MediaAssembly.isHlsPlaylist("https://cdn.example.com/master.m3u8?token=abc"))
        assertTrue(MediaAssembly.isHlsPlaylist("https://cdn.example.com/stream", "application/vnd.apple.mpegurl"))
        assertFalse(MediaAssembly.isHlsPlaylist("https://cdn.example.com/video.mp4"))
    }

    @Test
    fun `isDashManifest correctly identifies DASH manifests`() {
        assertTrue(MediaAssembly.isDashManifest("https://cdn.example.com/manifest.mpd"))
        assertTrue(MediaAssembly.isDashManifest("https://cdn.example.com/stream", "application/dash+xml"))
        assertFalse(MediaAssembly.isDashManifest("https://cdn.example.com/master.m3u8"))
    }

    @Test
    fun `h264 compatibility recognizes AVC and transcodes only other codec names`() {
        assertTrue(MediaAssembly.isH264Codec("video/avc"))
        assertTrue(MediaAssembly.isH264Codec("h264"))
        assertTrue(MediaAssembly.isH264Codec("avc1.640028"))
        assertFalse(MediaAssembly.isH264Codec("video/av01"))
        assertFalse(MediaAssembly.isH264Codec("hevc"))
    }
}
