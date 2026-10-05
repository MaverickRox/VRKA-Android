package com.mvrk.vrka.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class BoundedMemoryTransportTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `fetchTextBounded throws OversizedPayloadException when Content-Length is 2_685 GB without allocating heap`() = runBlocking {
        // Simulates the exact JAV Guru failure where an endpoint served a 2.685 GB media payload instead of a text playlist
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .header("Content-Length", "2685010000") // 2.685 GB
                    .body(ResponseBody.create(null, "FAKE_BODY_DATA"))
                    .build()
            }.build()

        val transport = OkHttpMediaTransport(mockClient)
        val url = "http://127.0.0.1:$port/huge_video.m3u8"

        try {
            transport.fetchTextBounded(url, maxBytes = 5 * 1024 * 1024L)
            fail("Expected OversizedPayloadException for 2.685 GB payload")
        } catch (e: OversizedPayloadException) {
            assertTrue("Exception message should cite payload size: ${e.message}", e.message?.contains("exceeds manifest limit") == true)
        }
    }

    @Test
    fun `fetchTextBounded throws OversizedPayloadException when streaming data exceeds limit on chunked transfer`() = runBlocking {
        server.createContext("/chunked_overflow") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 0) // Chunked transfer (no Content-Length)
            val os = exchange.responseBody
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            // Write 300 KB into a 100 KB limit
            repeat(5) {
                os.write(chunk)
            }
            os.close()
        }

        val transport = OkHttpMediaTransport()
        val url = "http://127.0.0.1:$port/chunked_overflow"

        try {
            transport.fetchTextBounded(url, maxBytes = 100 * 1024L)
            fail("Expected OversizedPayloadException when streaming chunks exceed maxBytes")
        } catch (e: OversizedPayloadException) {
            assertTrue("Exception message should cite bounded limit: ${e.message}", e.message?.contains("exceeded bounded manifest limit") == true)
        }
    }

    @Test
    fun `fetchTextBounded succeeds for normal sized playlist`() = runBlocking {
        val samplePlaylist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST"
        server.createContext("/normal.m3u8") { exchange ->
            val bytes = samplePlaylist.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        val transport = OkHttpMediaTransport()
        val url = "http://127.0.0.1:$port/normal.m3u8"

        val result = transport.fetchTextBounded(url, maxBytes = 5 * 1024 * 1024L)
        assertEquals(samplePlaylist, result)
    }

    @Test
    fun `downloadToFile streams chunks directly to disk with cancellation support`() = runBlocking {
        val testData = "STREAM_DATA_CHUNK_TEST_PAYLOAD".repeat(100)
        server.createContext("/media.bin") { exchange ->
            val bytes = testData.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        val tempFile = File.createTempFile("test_dest", ".bin")
        try {
            val transport = OkHttpMediaTransport()
            val url = "http://127.0.0.1:$port/media.bin"

            var progressReported = false
            val written = transport.downloadToFile(
                url = url,
                destination = tempFile,
                onProgress = { bytesRead, total ->
                    progressReported = true
                    assertEquals(testData.length.toLong(), total)
                }
            )

            assertEquals(testData.length.toLong(), written)
            assertEquals(testData.length.toLong(), tempFile.length())
            assertEquals(testData, tempFile.readText())
        } finally {
            tempFile.delete()
        }
    }
}
