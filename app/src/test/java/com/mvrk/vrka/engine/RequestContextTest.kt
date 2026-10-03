package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

class RequestContextTest {

    @Test
    fun `statically strips incoming Range header when allowRange is false`() {
        val context = RequestContext(
            targetUrl = "https://media.example.com/stream/master.m3u8",
            baseHeaders = mapOf(
                "User-Agent" to "VRKA/5.0",
                "Range" to "bytes=0-1048576"
            )
        )

        val resolved = context.resolveHeaders("https://media.example.com/stream/master.m3u8", allowRange = false)
        assertFalse(resolved.containsKey("Range"))
        assertFalse(resolved.containsKey("range"))
        assertEquals("VRKA/5.0", resolved["User-Agent"])
    }

    @Test
    fun `preserves Range header when allowRange is explicitly true`() {
        val context = RequestContext(
            targetUrl = "https://media.example.com/stream/file.mp4",
            baseHeaders = mapOf(
                "Range" to "bytes=100-200"
            )
        )

        val resolved = context.resolveHeaders("https://media.example.com/stream/file.mp4", allowRange = true)
        assertEquals("bytes=100-200", resolved["Range"])
    }

    @Test
    fun `retains browser-captured headers when requesting exact target URL`() {
        // When the browser captures headers specifically for a candidate URL (even on a CDN)
        val context = RequestContext(
            targetUrl = "https://cdn.example.com/hls/stream.m3u8?token=xyz",
            pageUrl = "https://page.example.com/watch/video1",
            baseHeaders = mapOf(
                "Cookie" to "cdn_session=active_session",
                "Authorization" to "Bearer cdn_token",
                "User-Agent" to "Mozilla/5.0 Gecko"
            )
        )

        val resolved = context.resolveHeaders("https://cdn.example.com/hls/stream.m3u8?token=xyz")
        assertEquals("cdn_session=active_session", resolved["Cookie"])
        assertEquals("Bearer cdn_token", resolved["Authorization"])
        assertEquals("Mozilla/5.0 Gecko", resolved["User-Agent"])
        assertEquals("https://page.example.com/watch/video1", resolved["Referer"])
        assertFalse(resolved.containsKey("Origin"))
    }

    @Test
    fun `does not share credentials across sibling subdomains`() {
        val context = RequestContext(
            targetUrl = "https://stream.site.com/master.m3u8",
            pageUrl = "https://www.site.com/watch/123",
            baseHeaders = mapOf(
                "Cookie" to "user_session=abc",
                "Authorization" to "Bearer valid_jwt"
            )
        )

        val resolved = context.resolveHeaders("https://cdn.site.com/segments/001.ts")
        assertFalse(resolved.containsKey("Cookie"))
        assertFalse(resolved.containsKey("Authorization"))
    }

    @Test
    fun `legitimate cross-origin CDN media receives page Referer and Origin but protects credentials`() {
        val context = RequestContext(
            targetUrl = "https://anikoto.cz/watch/ep1",
            pageUrl = "https://anikoto.cz/watch/ep1",
            baseHeaders = mapOf(
                "Cookie" to "session=secret123",
                "Authorization" to "Bearer sensitiveToken",
                "X-Auth-Token" to "customAuth",
                "User-Agent" to "Mozilla/5.0"
            )
        )

        // 1. Same host gets credentials
        val sameHost = context.resolveHeaders("https://anikoto.cz/api/playlist.m3u8")
        assertEquals("session=secret123", sameHost["Cookie"])
        assertEquals("Bearer sensitiveToken", sameHost["Authorization"])
        assertEquals("customAuth", sameHost["X-Auth-Token"])

        // 2. Third-party CDN on completely different apex domain has sensitive credentials stripped
        // but Referer and Origin are preserved to pass CDN hotlink protection
        val thirdPartyCdn = context.resolveHeaders("https://vividfalcon52.boats/segments/seg01.ts")
        assertFalse(thirdPartyCdn.containsKey("Cookie"))
        assertFalse(thirdPartyCdn.containsKey("Authorization"))
        assertFalse(thirdPartyCdn.containsKey("X-Auth-Token"))
        assertEquals("Mozilla/5.0", thirdPartyCdn["User-Agent"])
        assertEquals("https://anikoto.cz/watch/ep1", thirdPartyCdn["Referer"])
        assertFalse(thirdPartyCdn.containsKey("Origin"))
    }

    @Test
    fun `uses page Referer fallback without fabricating Origin`() {
        val context = RequestContext(
            targetUrl = "https://media.example.com/hls/master.m3u8",
            pageUrl = "https://example.com/watch/video123"
        )

        val resolved = context.resolveHeaders("https://media.example.com/hls/master.m3u8")
        assertEquals("https://example.com/watch/video123", resolved["Referer"])
        assertFalse(resolved.containsKey("Origin"))
    }

    @Test
    fun `does not use last two labels as a shared host boundary`() {
        assertFalse(RequestContext.isSameDomainOrSubdomain("cdn.attacker.co.uk", "site.example.co.uk"))
        assertTrue(RequestContext.isSameDomainOrSubdomain("cdn.site.example", "cdn.site.example"))
    }
}
