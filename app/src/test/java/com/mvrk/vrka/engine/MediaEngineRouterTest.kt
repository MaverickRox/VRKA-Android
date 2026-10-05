package com.mvrk.vrka.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class MediaEngineRouterTest {

    private class MockTestContext(private val baseDir: File) : ContextWrapper(null) {
        private val appInfo = ApplicationInfo().apply { nativeLibraryDir = baseDir.absolutePath }
        override fun getApplicationInfo(): ApplicationInfo = appInfo
        override fun getCacheDir(): File = baseDir
        override fun getNoBackupFilesDir(): File = baseDir
    }

    private lateinit var server: HttpServer
    private var port: Int = 0
    private lateinit var context: Context

    @Before
    fun setUp() {
        val testDir = File("build/test-cache").apply { mkdirs() }
        context = MockTestContext(testDir)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `router fails fast on DRM protected stream without endless retries`() = runBlocking {
        val drmManifest = """
            #EXTM3U
            #EXT-X-VERSION:5
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://license.com",KEYFORMAT="com.widevine"
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()

        server.createContext("/drm.m3u8") { exchange ->
            val bytes = drmManifest.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        val router = MediaEngineRouter(context)
        val stagingFile = File("build/test-cache/drm_test.mp4")

        val handoff = MediaHandoffContext(
            url = "http://127.0.0.1:$port/drm.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = emptyMap(),
            title = "DRM Video",
            stagingOutputFile = stagingFile
        )

        val token = SimpleEngineCancellationToken()
        val result = router.executeDownload(handoff, {}, token)

        assertTrue("Expected Failed result for DRM stream", result is EngineExecutionResult.Failed)
        val failed = result as EngineExecutionResult.Failed
        assertTrue("Error should be DrmProtectedStreamException: ${failed.error}", failed.error is DrmProtectedStreamException)
        assertTrue("Reason should indicate DRM: ${failed.reason}", failed.reason.contains("DRM", ignoreCase = true))
    }

    @Test
    fun `router respects cancellation signal immediately`() = runBlocking {
        server.createContext("/slow.m3u8") { exchange ->
            // Simulates slow or hanging stream
            exchange.sendResponseHeaders(200, 0)
            Thread.sleep(500)
            exchange.close()
        }

        val router = MediaEngineRouter(context)
        val stagingFile = File("build/test-cache/cancel_test.mp4")

        val handoff = MediaHandoffContext(
            url = "http://127.0.0.1:$port/slow.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = emptyMap(),
            title = "Slow Stream",
            stagingOutputFile = stagingFile
        )

        val token = SimpleEngineCancellationToken()
        token.cancel() // Cancel before or during

        val result = router.executeDownload(handoff, {}, token)
        assertEquals(EngineExecutionResult.Cancelled, result)
        assertFalse("Staging file must be cleaned up on cancellation", stagingFile.exists())
    }
}
