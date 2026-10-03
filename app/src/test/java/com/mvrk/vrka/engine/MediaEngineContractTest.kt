package com.mvrk.vrka.engine

import com.mvrk.vrka.HeaderValidation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaEngineContractTest {

    @Test
    fun `media kind correctly identifies HLS manifests and MIME types`() {
        assertEquals(EngineMediaKind.HLS, EngineMediaKind.fromUrlOrMime("https://example.com/stream/master.m3u8", null))
        assertEquals(EngineMediaKind.HLS, EngineMediaKind.fromUrlOrMime("https://example.com/stream/index.m3u8?token=123", "application/octet-stream"))
        assertEquals(EngineMediaKind.HLS, EngineMediaKind.fromUrlOrMime("https://example.com/playlist", "application/x-mpegurl"))
        assertEquals(EngineMediaKind.HLS, EngineMediaKind.fromUrlOrMime("https://example.com/playlist", "application/vnd.apple.mpegurl"))
    }

    @Test
    fun `media kind correctly identifies DASH manifests and progressive streams`() {
        assertEquals(EngineMediaKind.DASH, EngineMediaKind.fromUrlOrMime("https://example.com/manifest.mpd", null))
        assertEquals(EngineMediaKind.DASH, EngineMediaKind.fromUrlOrMime("https://example.com/stream", "application/dash+xml"))

        assertEquals(EngineMediaKind.PROGRESSIVE_VIDEO, EngineMediaKind.fromUrlOrMime("https://example.com/video.mp4", "video/mp4"))
        assertEquals(EngineMediaKind.PROGRESSIVE_VIDEO, EngineMediaKind.fromUrlOrMime("https://example.com/clip.webm", "video/webm"))

        assertEquals(EngineMediaKind.PROGRESSIVE_AUDIO, EngineMediaKind.fromUrlOrMime("https://example.com/audio.mp3", "audio/mpeg"))
        assertEquals(EngineMediaKind.PROGRESSIVE_AUDIO, EngineMediaKind.fromUrlOrMime("https://example.com/track.m4a", null))
        assertEquals(EngineMediaKind.UNKNOWN, EngineMediaKind.fromUrlOrMime("https://example.com/data.bin", "application/octet-stream"))
    }

    @Test
    fun `host binding preserves sensitive headers only for the exact host`() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://anikoto.cz/watch/ep1",
            "Cookie" to "session_id=secret123",
            "Authorization" to "Bearer token456"
        )

        // 1. Exactly same host
        val sameHost = HeaderValidation.bindHeadersToHost(
            headers = headers,
            targetUrl = "https://anikoto.cz/api/stream.m3u8",
            contextUrl = "https://anikoto.cz/watch/ep1"
        )
        assertEquals("session_id=secret123", sameHost["Cookie"])
        assertEquals("Bearer token456", sameHost["Authorization"])
        assertEquals("Mozilla/5.0", sameHost["User-Agent"])

        // 2. Sibling/subdomain host does not inherit the browser credentials.
        val subHost = HeaderValidation.bindHeadersToHost(
            headers = headers,
            targetUrl = "https://cdn.anikoto.cz/hls/ep1.m3u8",
            contextUrl = "https://anikoto.cz/watch/ep1"
        )
        assertNull(subHost["Cookie"])
        assertNull(subHost["Authorization"])
    }

    @Test
    fun `host binding strips sensitive credentials for untrusted third party domains`() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://anikoto.cz/watch/ep1",
            "Cookie" to "session_id=secret123",
            "Authorization" to "Bearer token456"
        )

        // Cross-domain target
        val crossDomain = HeaderValidation.bindHeadersToHost(
            headers = headers,
            targetUrl = "https://thirdparty-tracker.org/video.mp4",
            contextUrl = "https://anikoto.cz/watch/ep1"
        )

        assertNull("Cookie must be stripped on cross-origin", crossDomain["Cookie"])
        assertNull("Authorization must be stripped on cross-origin", crossDomain["Authorization"])
        assertEquals("Mozilla/5.0", crossDomain["User-Agent"])
        assertEquals("https://anikoto.cz/watch/ep1", crossDomain["Referer"])
    }

    @Test
    fun `cancellation token updates status atomically`() {
        val token = SimpleEngineCancellationToken()
        assertFalse(token.isCancelled())
        token.cancel()
        assertTrue(token.isCancelled())
    }

    @Test
    fun `media handoff context holds required download metadata`() {
        val target = File("/tmp/output.mp4")
        val context = MediaHandoffContext(
            url = "https://anikoto.cz/stream.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = mapOf("Referer" to "https://anikoto.cz/watch/ep1"),
            title = "Episode 1",
            stagingOutputFile = target,
            durationSeconds = 1420.5,
            expectedHeight = 1080,
            originalPageUrl = "https://anikoto.cz/watch/ep1"
        )

        assertEquals("https://anikoto.cz/stream.m3u8", context.url)
        assertEquals(EngineMediaKind.HLS, context.mediaKind)
        assertEquals(1080, context.expectedHeight)
        assertEquals(1420.5, context.durationSeconds!!, 0.001)
        assertEquals("https://anikoto.cz/watch/ep1", context.originalPageUrl)
    }
}
