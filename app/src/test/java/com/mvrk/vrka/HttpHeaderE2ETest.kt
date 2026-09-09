package com.mvrk.vrka

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end automated test validating that user-supplied HTTP headers (Referer, Origin,
 * arbitrary custom headers, and resolved session headers) correctly propagate across
 * extraction, direct network, HLS manifest, and HLS segment request paths.
 */
class HttpHeaderE2ETest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    data class RecordedRequest(
        val path: String,
        val method: String,
        val headers: Map<String, List<String>>,
    ) {
        fun getHeader(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/manifest.m3u8", HttpHandler { exchange ->
            record(exchange)
            val manifest = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:2
                #EXTINF:2.0,
                http://127.0.0.1:$serverPort/segment_0.ts
                #EXT-X-ENDLIST
            """.trimIndent()
            val bytes = manifest.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        server.createContext("/segment_0.ts", HttpHandler { exchange ->
            record(exchange)
            // 188-byte dummy MPEG-TS packet starting with sync byte 0x47
            val tsPacket = ByteArray(188) { if (it == 0) 0x47.toByte() else 0x00.toByte() }
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, tsPacket.size.toLong())
            exchange.responseBody.write(tsPacket)
            exchange.close()
        })

        server.createContext("/direct_audio.mp3", HttpHandler { exchange ->
            record(exchange)
            val audioBytes = ByteArray(256) { 0xFF.toByte() }
            exchange.responseHeaders.set("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, audioBytes.size.toLong())
            exchange.responseBody.write(audioBytes)
            exchange.close()
        })

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun record(exchange: HttpExchange) {
        val map = HashMap<String, List<String>>()
        for ((k, v) in exchange.requestHeaders) {
            map[k] = v
        }
        recordedRequests.add(RecordedRequest(exchange.requestURI.path, exchange.requestMethod, map))
    }

    @Test
    fun testDownloadRequestFactoryGeneratesHeaderOptionsForAllEffectiveHeaders() {
        val request = DownloadRequest(
            url = "http://127.0.0.1:$serverPort/manifest.m3u8",
            referer = "https://publisher.example.com/stream",
            origin = "https://publisher.example.com",
            customHeaders = mapOf(
                "X-Forwarded-For" to "203.0.113.195",
                "X-Device-Id" to "oneplus-cph2447-01",
                "User-Agent" to "CustomVRKA/4.5.1",
            ),
            resolvedHeaders = mapOf(
                "Cookie" to "auth_token=super_secret_session_token",
                "Authorization" to "Bearer access_token_xyz",
            ),
        )

        val job = DownloadJob(
            id = "header-test-job",
            request = request,
            state = JobState.DOWNLOADING,
        )

        val stagingDir = File(System.getProperty("java.io.tmpdir"), "vrka_header_test")
        val ytdlRequest = DownloadRequestFactory.download(job, stagingDir)

        val commandArgs = ytdlRequest.buildCommand().toList()

        // Verify each header is passed as --add-header "Key:Value"
        val expectedHeaders = listOf(
            "Referer:https://publisher.example.com/stream",
            "Origin:https://publisher.example.com",
            "X-Forwarded-For:203.0.113.195",
            "X-Device-Id:oneplus-cph2447-01",
            "User-Agent:CustomVRKA/4.5.1",
            "Cookie:auth_token=super_secret_session_token",
            "Authorization:Bearer access_token_xyz",
        )

        for (expected in expectedHeaders) {
            val key = expected.substringBefore(":")
            val hasHeaderOption = commandArgs.indices.any { i ->
                commandArgs[i] == "--add-header" && commandArgs.getOrNull(i + 1)?.startsWith("$key:", ignoreCase = true) == true
            }
            assertTrue("Expected yt-dlp arguments to contain --add-header for '$expected'. Args: $commandArgs", hasHeaderOption)
        }

        // Also check info request
        val infoRequest = DownloadRequestFactory.info(request)
        val infoArgs = infoRequest.buildCommand().toList()
        for (expected in expectedHeaders) {
            val key = expected.substringBefore(":")
            val hasHeaderOption = infoArgs.indices.any { i ->
                infoArgs[i] == "--add-header" && infoArgs.getOrNull(i + 1)?.startsWith("$key:", ignoreCase = true) == true
            }
            assertTrue("Expected info args to contain --add-header for '$expected'. Args: $infoArgs", hasHeaderOption)
        }
    }


    private fun sendHttpRequest(urlStr: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val url = URL(urlStr)
        java.net.Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val writer = java.io.PrintWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))
            writer.print("GET ${url.path} HTTP/1.1\r\n")
            writer.print("Host: ${url.host}:${url.port}\r\n")
            headers.forEach { (k, v) ->
                writer.print("$k: $v\r\n")
            }
            writer.print("Connection: close\r\n\r\n")
            writer.flush()

            val inStream = socket.getInputStream()
            val raw = inStream.readBytes()
            val headerEnd = findHeaderEnd(raw)
            val headerStr = String(raw, 0, headerEnd, Charsets.UTF_8)
            val statusLine = headerStr.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val body = raw.copyOfRange(headerEnd, raw.size)
            return code to body
        }
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return bytes.size
    }

    @Test
    fun testServerSideHeaderReceptionOnDirectAndHlsRequests() {
        val request = DownloadRequest(
            url = "http://127.0.0.1:$serverPort/manifest.m3u8",
            referer = "https://ref.example.org",
            origin = "https://origin.example.org",
            customHeaders = mapOf(
                "X-Custom-Client" to "VRKA-Android-4.5.1",
            ),
            resolvedHeaders = mapOf(
                "Cookie" to "session_id=12345",
            ),
        )

        val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(request)

        // 1. Fetch HLS manifest using effective headers
        val (manifestCode, manifestRaw) = sendHttpRequest("http://127.0.0.1:$serverPort/manifest.m3u8", effectiveHeaders)
        val manifestBody = String(manifestRaw, Charsets.UTF_8)
        assertEquals(200, manifestCode)
        assertTrue(manifestBody.contains("segment_0.ts"))

        // 2. Fetch HLS segment using the same effective header set (as yt-dlp/ffmpeg does)
        val (segmentCode, segmentBytes) = sendHttpRequest("http://127.0.0.1:$serverPort/segment_0.ts", effectiveHeaders)
        assertEquals(200, segmentCode)
        assertEquals(188, segmentBytes.size)
        assertEquals(0x47.toByte(), segmentBytes[0])

        // 3. Fetch direct audio using effective headers
        val (audioCode, audioBytes) = sendHttpRequest("http://127.0.0.1:$serverPort/direct_audio.mp3", effectiveHeaders)
        assertEquals(200, audioCode)
        assertEquals(256, audioBytes.size)

        // 4. Verify on test server that every single endpoint received all headers
        val manifestReq = recordedRequests.firstOrNull { it.path == "/manifest.m3u8" }
        assertNotNull("Manifest request must be recorded", manifestReq)
        assertEquals("https://ref.example.org", manifestReq!!.getHeader("Referer"))
        assertEquals("https://origin.example.org", manifestReq.getHeader("Origin"))
        assertEquals("VRKA-Android-4.5.1", manifestReq.getHeader("X-Custom-Client"))
        assertEquals("session_id=12345", manifestReq.getHeader("Cookie"))

        val segmentReq = recordedRequests.firstOrNull { it.path == "/segment_0.ts" }
        assertNotNull("Segment request must be recorded", segmentReq)
        assertEquals("https://ref.example.org", segmentReq!!.getHeader("Referer"))
        assertEquals("https://origin.example.org", segmentReq.getHeader("Origin"))
        assertEquals("VRKA-Android-4.5.1", segmentReq.getHeader("X-Custom-Client"))
        assertEquals("session_id=12345", segmentReq.getHeader("Cookie"))

        val audioReq = recordedRequests.firstOrNull { it.path == "/direct_audio.mp3" }
        assertNotNull("Audio request must be recorded", audioReq)
        assertEquals("https://ref.example.org", audioReq!!.getHeader("Referer"))
        assertEquals("https://origin.example.org", audioReq.getHeader("Origin"))
        assertEquals("VRKA-Android-4.5.1", audioReq.getHeader("X-Custom-Client"))
        assertEquals("session_id=12345", audioReq.getHeader("Cookie"))
    }
}
