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
    fun `parseMasterPlaylist extracts variants and heights`() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080p.m3u8
        """.trimIndent()

        val variants = MediaAssembly.parseMasterPlaylist(master, "https://origin.com/stream/master.m3u8")
        assertEquals(3, variants.size)
        assertEquals(360, variants[0].first)
        assertEquals("https://origin.com/stream/360p.m3u8", variants[0].second)
        assertEquals(720, variants[1].first)
        assertEquals("https://origin.com/stream/720p.m3u8", variants[1].second)
        assertEquals(1080, variants[2].first)
        assertEquals("https://origin.com/stream/1080p.m3u8", variants[2].second)
    }

    @Test
    fun `selectVariant honors quality cap`() {
        val variants = listOf(
            360 to "https://origin.com/360.m3u8",
            720 to "https://origin.com/720.m3u8",
            1080 to "https://origin.com/1080.m3u8",
        )

        // Quality cap 720p -> selects 720p
        assertEquals("https://origin.com/720.m3u8", MediaAssembly.selectVariant(variants, 720))

        // No quality cap -> selects 1080p
        assertEquals("https://origin.com/1080.m3u8", MediaAssembly.selectVariant(variants, null))

        // Quality cap 480p -> selects 360p
        assertEquals("https://origin.com/360.m3u8", MediaAssembly.selectVariant(variants, 480))
    }

    @Test
    fun `parseVariantSegments extracts init segment and media segments`() {
        val variant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:9.009,
            chunk_0.m4s
            #EXTINF:9.009,
            chunk_1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val segments = MediaAssembly.parseVariantSegments(variant, "https://origin.com/video/index.m3u8")
        assertEquals(3, segments.size)
        assertEquals("https://origin.com/video/init.mp4", segments[0])
        assertEquals("https://origin.com/video/chunk_0.m4s", segments[1])
        assertEquals("https://origin.com/video/chunk_1.m4s", segments[2])
    }

    @Test
    fun `isDashManifest correctly identifies DASH manifests`() {
        assertTrue(MediaAssembly.isDashManifest("https://cdn.example.com/manifest.mpd"))
        assertTrue(MediaAssembly.isDashManifest("https://cdn.example.com/stream", "application/dash+xml"))
        assertFalse(MediaAssembly.isDashManifest("https://cdn.example.com/master.m3u8"))
    }

    @Test
    fun `parseDashSegments extracts init and media segments from MPD XML`() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
                <Period>
                    <AdaptationSet mimeType="video/mp4">
                        <Representation id="1" bandwidth="2000000">
                            <SegmentList>
                                <Initialization sourceURL="dash_init.mp4"/>
                                <SegmentURL media="dash_segment_1.m4s"/>
                                <SegmentURL media="dash_segment_2.m4s"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val segments = MediaAssembly.parseDashSegments(mpd, "https://origin.com/dash/manifest.mpd")
        assertEquals(3, segments.size)
        assertEquals("https://origin.com/dash/dash_init.mp4", segments[0])
        assertEquals("https://origin.com/dash/dash_segment_1.m4s", segments[1])
        assertEquals("https://origin.com/dash/dash_segment_2.m4s", segments[2])
    }
}
