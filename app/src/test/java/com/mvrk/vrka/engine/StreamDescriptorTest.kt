package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

class StreamDescriptorTest {

    @Test
    fun `parse multivariant HLS with video, audio, and subtitle renditions`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Japanese",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="ja",URI="audio/ja.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS="avc1.64002a,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
            720p.m3u8
        """.trimIndent()

        val desc = StreamDescriptor.parseFromManifest(
            url = "https://example.com/master.m3u8",
            manifestContent = manifest
        )

        assertTrue(desc.isMasterPlaylist)
        assertEquals(StreamContainer.HLS, desc.container)
        assertEquals(2, desc.videoTracks.size)
        assertEquals(1080, desc.videoTracks[0].height)
        assertEquals(1920, desc.videoTracks[0].width)
        assertEquals(4500000L, desc.videoTracks[0].bitrate)
        assertEquals(720, desc.videoTracks[1].height)
        assertEquals("1080p", desc.resolutionLabel)

        assertEquals(2, desc.audioTracks.size)
        assertEquals("English", desc.audioTracks[0].name)
        assertTrue(desc.audioTracks[0].isDefault)
        assertEquals("Japanese", desc.audioTracks[1].name)
        assertFalse(desc.audioTracks[1].isDefault)

        assertEquals(1, desc.subtitleTracks.size)
        assertEquals("English", desc.subtitleTracks[0].name)

        assertTrue(desc.hasSeparateAudioTracks)
        assertTrue(desc.requiresMuxing)
        assertEquals(DrmScheme.NONE, desc.drmScheme)
        assertFalse(desc.drmScheme.isProtected)
    }

    @Test
    fun `detect Widevine DRM in HLS manifest`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:5
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,...",KEYFORMAT="com.widevine",KEYFORMATVERSIONS="1"
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()

        val desc = StreamDescriptor.parseFromManifest("https://example.com/stream.m3u8", manifest)
        assertEquals(DrmScheme.WIDEVINE, desc.drmScheme)
        assertTrue(desc.drmScheme.isProtected)
        assertTrue(desc.isEncrypted)
        assertEquals("Widevine", desc.encryptionMethod)
    }

    @Test
    fun `detect PlayReady DRM in HLS manifest`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:5
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://license.example.com",KEYFORMAT="com.microsoft.playready"
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()

        val desc = StreamDescriptor.parseFromManifest("https://example.com/stream.m3u8", manifest)
        assertEquals(DrmScheme.PLAYREADY, desc.drmScheme)
        assertTrue(desc.drmScheme.isProtected)
        assertEquals("PlayReady", desc.encryptionMethod)
    }

    @Test
    fun `detect clear AES-128 encryption in HLS without DRM flag`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key.bin",IV=0x0123456789ABCDEF
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        val desc = StreamDescriptor.parseFromManifest("https://example.com/stream.m3u8", manifest)
        // Standard AES-128 with clear key is playable by FFmpeg/Media3
        assertEquals(DrmScheme.NONE, desc.drmScheme)
        assertFalse(desc.drmScheme.isProtected)
        assertTrue(desc.isEncrypted)
        assertEquals("AES-128", desc.encryptionMethod)
    }

    @Test
    fun `detect DASH manifest with Widevine ContentProtection`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
                <Period>
                    <AdaptationSet mimeType="video/mp4">
                        <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" />
                        <Representation id="1" bandwidth="2000000" width="1280" height="720"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val desc = StreamDescriptor.parseFromManifest("https://example.com/manifest.mpd", mpd)
        assertEquals(StreamContainer.DASH, desc.container)
        assertEquals(DrmScheme.WIDEVINE, desc.drmScheme)
        assertTrue(desc.drmScheme.isProtected)
    }
}
