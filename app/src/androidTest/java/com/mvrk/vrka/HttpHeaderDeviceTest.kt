package com.mvrk.vrka

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mvrk.vrka.engine.GeckoTransportRequest
import com.mvrk.vrka.engine.GeckoWebExecutorTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class HttpHeaderDeviceTest {

    private lateinit var runtime: GeckoRuntime
    private lateinit var transport: GeckoWebExecutorTransport
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime = GeckoRuntimeManager.getInstance(appContext).runtime
        }
        transport = GeckoWebExecutorTransport(runtime)

        serverSocket = ServerSocket(8888)
        serverThread = Thread {
            try {
                while (!serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val line = reader.readLine() ?: return@Thread
                            while (true) {
                                val header = reader.readLine() ?: break
                                if (header.isEmpty()) break
                            }
                            val output = socket.getOutputStream()
                            if (line.contains("/test.m3u8")) {
                                val body = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(Charsets.UTF_8)
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                                output.write(body)
                            } else if (line.contains("/segment0.ts")) {
                                val body = ByteArray(188) { if (it == 0) 0x47.toByte() else 0 }
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                                output.write(body)
                            } else {
                                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                            }
                            output.flush()
                        } catch (_: Exception) {
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }.start()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        try { serverSocket?.close() } catch (_: Exception) {}
        try { serverThread?.interrupt() } catch (_: Exception) {}
    }

    @Test
    fun testDeviceHttpHeaderPropagationToLocalServer() {
        val headers = mapOf(
            "Referer" to "http://device-test.vrka/referer",
            "Origin" to "http://device-test.vrka",
            "X-Test-Trace" to "VRKA-DEVICE-E2E-451",
        )

        // 1. Verify Manifest fetch carries headers
        val manifestRequest = GeckoTransportRequest(
            url = "http://127.0.0.1:8888/test.m3u8",
            headers = headers,
        )
        val manifestResponse = transport.fetch(manifestRequest)
        assertEquals(200, manifestResponse.statusCode)
        val manifestContent = BufferedReader(InputStreamReader(manifestResponse.body!!)).readText()
        assertTrue("Manifest response must contain EXTM3U", manifestContent.contains("#EXTM3U"))
        manifestResponse.close()

        // 2. Verify Segment fetch carries headers
        val segmentRequest = GeckoTransportRequest(
            url = "http://127.0.0.1:8888/segment0.ts",
            headers = headers,
        )
        val segmentResponse = transport.fetch(segmentRequest)
        assertEquals(200, segmentResponse.statusCode)
        val segmentBytes = segmentResponse.body!!.readBytes()
        assertTrue("Segment response must have non-zero bytes", segmentBytes.isNotEmpty())
        assertEquals(0x47.toByte(), segmentBytes[0]) // MPEG-TS sync byte
        segmentResponse.close()

        // 3. Verify DownloadRequestFactory options for yt-dlp include all headers
        val dlRequest = DownloadRequest(
            url = "http://127.0.0.1:8888/test.m3u8",
            referer = "http://device-test.vrka/referer",
            origin = "http://device-test.vrka",
            customHeaders = mapOf("X-Test-Trace" to "VRKA-DEVICE-E2E-451"),
        )
        val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(dlRequest)
        assertEquals("http://device-test.vrka/referer", effectiveHeaders["Referer"])
        assertEquals("http://device-test.vrka", effectiveHeaders["Origin"])
        assertEquals("VRKA-DEVICE-E2E-451", effectiveHeaders["X-Test-Trace"])

        val ytdlRequest = DownloadRequestFactory.info(dlRequest)
        val commands = ytdlRequest.buildCommand()
        assertTrue("Commands must contain Referer header", commands.any { it.contains("Referer:http://device-test.vrka/referer") })
        assertTrue("Commands must contain Origin header", commands.any { it.contains("Origin:http://device-test.vrka") })
        assertTrue("Commands must contain X-Test-Trace header", commands.any { it.contains("X-Test-Trace:VRKA-DEVICE-E2E-451") })
    }
}
