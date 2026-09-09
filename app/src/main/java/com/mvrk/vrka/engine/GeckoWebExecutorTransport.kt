package com.mvrk.vrka.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import com.mvrk.vrka.HeaderValidation
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Request specification for [GeckoWebExecutorTransport].
 */
data class GeckoTransportRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val flags: Int = GeckoWebExecutor.FETCH_FLAGS_NONE,
)

/**
 * Metadata and body stream from a successful [GeckoWebExecutor] fetch.
 */
class GeckoTransportResponse(
    val statusCode: Int,
    val isSecure: Boolean,
    val redirected: Boolean,
    val certSubject: String?,
    val headers: Map<String, String>,
    val body: InputStream?,
) : AutoCloseable {
    val contentLength: Long
        get() = headers["content-length"]?.toLongOrNull() ?: -1L

    val contentType: String
        get() = headers["content-type"].orEmpty()

    override fun close() {
        body?.close()
    }
}

/**
 * Strongly-typed exceptions from [GeckoWebExecutorTransport].
 * Distinguishes TLS, HTTP, Network, Timeout, and Cancellation errors.
 */
sealed class GeckoTransportException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TlsFailure(val code: Int, message: String, cause: Throwable? = null) :
        GeckoTransportException("TLS handshake or certificate failure (0x${Integer.toHexString(code)}): $message", cause)

    class HttpError(val statusCode: Int, val headers: Map<String, String>) :
        GeckoTransportException("HTTP transfer rejected with status $statusCode")

    class NetworkError(val code: Int, message: String, cause: Throwable? = null) :
        GeckoTransportException("Network error (0x${Integer.toHexString(code)}): $message", cause)

    class Timeout(message: String, cause: Throwable? = null) :
        GeckoTransportException("Transfer timed out: $message", cause)

    class Cancelled(message: String = "Transfer was cancelled") :
        GeckoTransportException(message)
}

/**
 * Executes network requests through [GeckoWebExecutor] using an existing production [GeckoRuntime].
 * Supports bounded streaming writes to destinations with cancellation and progress reporting.
 */
class GeckoWebExecutorTransport(
    private val runtime: GeckoRuntime,
) {
    companion object {
        private const val TAG = "VRKA-GeckoTransport"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val STREAM_BUFFER_SIZE = 32_768 // 32 KB bounded buffer

        private val DISALLOWED_HEADERS = setOf(
            "host",
            "content-length",
            "connection",
            "upgrade",
        )
    }

    @Volatile
    private var executor: GeckoWebExecutor? = null

    /**
     * Lazily instantiates [GeckoWebExecutor] on the Android Main/UI thread as required
     * by GeckoView.
     */
    private fun getOrCreateExecutor(): GeckoWebExecutor {
        executor?.let { return it }
        synchronized(this) {
            executor?.let { return it }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                executor = GeckoWebExecutor(runtime)
            } else {
                val latch = CountDownLatch(1)
                var created: GeckoWebExecutor? = null
                var error: Throwable? = null
                Handler(Looper.getMainLooper()).post {
                    try {
                        created = GeckoWebExecutor(runtime)
                    } catch (t: Throwable) {
                        error = t
                    } finally {
                        latch.countDown()
                    }
                }
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw GeckoTransportException.Timeout("Timed out waiting to initialize GeckoWebExecutor on main thread")
                }
                error?.let { throw IOException("Failed to initialize GeckoWebExecutor", it) }
                executor = created ?: throw IllegalStateException("GeckoWebExecutor instance was null after creation")
            }
            return executor!!
        }
    }

    /**
     * Executes a network fetch through GeckoWebExecutor and returns the response stream.
     */
    fun fetch(
        request: GeckoTransportRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GeckoTransportResponse {
        val exec = getOrCreateExecutor()
        val builder = WebRequest.Builder(request.url)

        // Inject validated headers
        request.headers.forEach { (key, value) ->
            val lower = key.lowercase()
            if (value.isNotBlank() && lower !in DISALLOWED_HEADERS && HeaderValidation.isValidHeaderName(key)) {
                builder.addHeader(key, value)
            }
        }

        val webRequest = builder.build()
        val geckoResult = exec.fetch(webRequest, request.flags)

        val response: WebResponse = try {
            geckoResult.poll(timeoutMs)
                ?: throw GeckoTransportException.Timeout("Request to ${sanitizeUrlForLogging(request.url)} timed out after ${timeoutMs}ms")
        } catch (wre: WebRequestError) {
            when (wre.category) {
                WebRequestError.ERROR_CATEGORY_SECURITY ->
                    throw GeckoTransportException.TlsFailure(wre.code, wre.message ?: "SSL/TLS validation failed", wre)
                WebRequestError.ERROR_CATEGORY_NETWORK -> {
                    if (wre.code == WebRequestError.ERROR_NET_TIMEOUT) {
                        throw GeckoTransportException.Timeout(wre.message ?: "Connection timed out", wre)
                    }
                    throw GeckoTransportException.NetworkError(wre.code, wre.message ?: "Network error", wre)
                }
                else -> throw GeckoTransportException.NetworkError(wre.code, wre.message ?: "Web request failed", wre)
            }
        } catch (te: GeckoTransportException) {
            throw te
        } catch (t: Throwable) {
            throw IOException("Unexpected error during Gecko fetch: ${t.message}", t)
        }

        val status = response.statusCode
        if (status !in 200..299) {
            response.body?.close()
            throw GeckoTransportException.HttpError(status, response.headers)
        }

        return GeckoTransportResponse(
            statusCode = status,
            isSecure = response.isSecure,
            redirected = response.redirected,
            certSubject = response.certificate?.subjectDN?.name,
            headers = response.headers,
            body = response.body,
        )
    }

    /**
     * Streams the response body into an [OutputStream] with bounded memory usage.
     *
     * @param request the transport request to execute.
     * @param output destination output stream (caller is responsible for closing, or it will be flushed).
     * @param onProgress optional callback reporting (bytesRead, totalBytes).
     * @param isCancelled optional predicate returning true if the transfer has been aborted.
     * @return total bytes written.
     */
    fun downloadToStream(
        request: GeckoTransportRequest,
        output: OutputStream,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Long {
        if (isCancelled?.invoke() == true) {
            throw GeckoTransportException.Cancelled()
        }

        val start = System.currentTimeMillis()
        val response = fetch(request)
        val body = response.body ?: throw IOException("Empty response body from ${sanitizeUrlForLogging(request.url)}")
        val totalExpected = response.contentLength

        var bytesWritten = 0L
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var lastProgressTime = 0L

        try {
            while (true) {
                if (isCancelled?.invoke() == true) {
                    throw GeckoTransportException.Cancelled("Transfer cancelled after $bytesWritten bytes")
                }

                val read = body.read(buffer)
                if (read <= 0) break

                output.write(buffer, 0, read)
                bytesWritten += read

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 200 || (totalExpected > 0 && bytesWritten >= totalExpected)) {
                    lastProgressTime = now
                    onProgress?.invoke(bytesWritten, totalExpected)
                }
            }
            output.flush()
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "Stream completed: host=${sanitizeUrlForLogging(request.url)}, bytes=$bytesWritten, elapsed=${elapsed}ms")
            return bytesWritten
        } finally {
            body.close()
        }
    }

    /**
     * Streams the response body directly into a destination [File].
     * If cancelled or failed, deletes the incomplete file to avoid corrupted artifacts.
     */
    fun downloadToFile(
        request: GeckoTransportRequest,
        destinationFile: File,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Long {
        destinationFile.parentFile?.mkdirs()
        try {
            FileOutputStream(destinationFile).use { fos ->
                return downloadToStream(request, fos, onProgress, isCancelled)
            }
        } catch (e: Throwable) {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            throw e
        }
    }

    private fun sanitizeUrlForLogging(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${uri.path}"
        } catch (_: Throwable) {
            url.substringBefore('?')
        }
    }
}
