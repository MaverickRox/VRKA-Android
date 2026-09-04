package com.mvrk.vrka

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mvrk.vrka.engine.GeckoTransportException
import com.mvrk.vrka.engine.GeckoTransportRequest
import com.mvrk.vrka.engine.GeckoWebExecutorTransport
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class GeckoWebExecutorTransportTest {

    private lateinit var runtime: GeckoRuntime
    private lateinit var transport: GeckoWebExecutorTransport
    private lateinit var testCacheDir: File

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime = GeckoRuntimeManager.getInstance(appContext).runtime
        }
        transport = GeckoWebExecutorTransport(runtime)
        testCacheDir = File(appContext.cacheDir, "transport_tests").apply { mkdirs() }
    }

    @Test
    fun testSuccessfulHttpsStreamingAndProgress() {
        val targetFile = File(testCacheDir, "test_download.bin")
        targetFile.delete()

        val progressReported = AtomicBoolean(false)
        val lastBytes = AtomicLong(0)

        val bytesWritten = transport.downloadToFile(
            request = GeckoTransportRequest(url = "https://www.mozilla.org/robots.txt"),
            destinationFile = targetFile,
            onProgress = { written, _ ->
                progressReported.set(true)
                lastBytes.set(written)
            },
        )

        assertTrue("Bytes written must be positive", bytesWritten > 0)
        assertTrue("Destination file must exist", targetFile.exists())
        assertEquals("File length must match written bytes", bytesWritten, targetFile.length())
        assertTrue("Progress callback must have fired", progressReported.get())
        targetFile.delete()
    }

    @Test
    fun testHttpErrorThrowsGeckoHttpException() {
        try {
            transport.fetch(GeckoTransportRequest(url = "https://www.mozilla.org/404-nonexistent-path-for-vrka-test"))
            fail("Expected GeckoTransportException.HttpError for 404 response")
        } catch (e: GeckoTransportException.HttpError) {
            assertEquals("Status code must be 404", 404, e.statusCode)
        }
    }

    @Test
    fun testTlsFailureThrowsGeckoTlsException() {
        try {
            // BadSSL host with invalid self-signed cert
            transport.fetch(GeckoTransportRequest(url = "https://self-signed.badssl.com/"))
            fail("Expected GeckoTransportException.TlsFailure for self-signed certificate")
        } catch (e: GeckoTransportException.TlsFailure) {
            assertTrue("TLS error code must be non-zero", e.code != 0)
        }
    }

    @Test
    fun testCancellationAbortsStreamingAndDeletesIncompleteFile() {
        val targetFile = File(testCacheDir, "test_cancelled.bin")
        targetFile.delete()

        try {
            transport.downloadToFile(
                request = GeckoTransportRequest(url = "https://www.mozilla.org/robots.txt"),
                destinationFile = targetFile,
                isCancelled = { true }, // Immediate cancellation
            )
            fail("Expected GeckoTransportException.Cancelled")
        } catch (e: GeckoTransportException.Cancelled) {
            assertFalse("Incomplete target file must be deleted on cancellation", targetFile.exists())
        }
    }

    @Test
    fun testRuntimeReuseDoesNotRecreateRuntime() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        var runtime2: GeckoRuntime? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime2 = GeckoRuntimeManager.getInstance(appContext).runtime
        }
        assertSame("GeckoRuntime must be a shared singleton", runtime, runtime2)
    }
}
