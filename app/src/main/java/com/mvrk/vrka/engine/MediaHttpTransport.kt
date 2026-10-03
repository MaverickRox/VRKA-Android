package com.mvrk.vrka.engine

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.GeckoRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Thrown when an endpoint returns a payload larger than the bounded threshold.
 * Specifically prevents OOM errors such as the JAV Guru 2.685 GB allocation bug.
 */
class OversizedPayloadException(message: String) : IOException(message)

/**
 * Transport header metadata.
 */
data class TransportHeaderInfo(
    val statusCode: Int,
    val contentLength: Long?,
    val contentType: String?,
    val headers: Map<String, String>,
)

/**
 * Abstraction for network transfers across OkHttp native transport, Android HttpEngine,
 * and GeckoView browser context transport.
 */
interface MediaHttpTransport {
    val transportName: String

    /**
     * Bounded text fetching for manifests (HLS playlists, DASH MPDs).
     * Strictly limits the bytes read to [maxBytes] to avoid materializing multi-gigabyte
     * media bodies into heap Strings.
     */
    suspend fun fetchTextBounded(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = 5 * 1024 * 1024L // 5 MB ceiling
    ): String

    /**
     * Streams media content directly to [destination] file using bounded 32KB/64KB chunks.
     * Never buffers the full body in memory.
     */
    suspend fun downloadToFile(
        url: String,
        headers: Map<String, String> = emptyMap(),
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
        cancellationToken: EngineCancellationToken? = null
    ): Long

    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): TransportHeaderInfo
}

/**
 * Production OkHttp media transport using streaming buffers.
 */
class OkHttpMediaTransport(
    private val client: OkHttpClient = defaultClient()
) : MediaHttpTransport {

    override val transportName: String = "OkHttpMediaTransport"

    override suspend fun fetchTextBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): String {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}: ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty response body from ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
            val headerCl = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val cl = maxOf(body.contentLength(), headerCl)
            if (cl > maxBytes) {
                throw OversizedPayloadException("Payload size ($cl bytes) exceeds manifest limit of $maxBytes bytes for ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
            }

            val source = body.byteStream()
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            var totalRead = 0L

            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw OversizedPayloadException("Streaming payload exceeded bounded manifest limit of $maxBytes bytes for ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
                }
                baos.write(buffer, 0, read)
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }

    override suspend fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)?,
        cancellationToken: EngineCancellationToken?
    ): Long {
        destination.parentFile?.mkdirs()
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} downloading ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}: ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty response body from ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
            val totalBytes = body.contentLength().takeIf { it > 0 }

            val source: InputStream = body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesWritten = 0L
            var lastUpdate = 0L

            FileOutputStream(destination).use { fos ->
                while (true) {
                    if (cancellationToken?.isCancelled() == true) {
                        destination.delete()
                        throw IOException("Transfer cancelled")
                    }

                    val read = source.read(buffer)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesWritten += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 250 || (totalBytes != null && bytesWritten >= totalBytes)) {
                        lastUpdate = now
                        onProgress?.invoke(bytesWritten, totalBytes)
                    }
                }
                fos.flush()
            }
            return bytesWritten
        }
    }

    override suspend fun head(
        url: String,
        headers: Map<String, String>
    ): TransportHeaderInfo {
        val requestBuilder = Request.Builder().url(url).head()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val headerMap = response.headers.names().associateWith { response.headers[it].orEmpty() }
            return TransportHeaderInfo(
                statusCode = response.code,
                contentLength = response.body?.contentLength()?.takeIf { it >= 0 },
                contentType = response.header("Content-Type"),
                headers = headerMap
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB chunked transfer

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

/**
 * GeckoView browser-context transport for cases requiring session state,
 * internal Gecko cookies, and anti-bot validation.
 */
class GeckoBrowserContextTransport(
    private val runtime: GeckoRuntime,
) : MediaHttpTransport {

    override val transportName: String = "GeckoBrowserContextTransport"
    private val underlying = GeckoWebExecutorTransport(runtime)

    override suspend fun fetchTextBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): String {
        val response = underlying.fetch(GeckoTransportRequest(url = url, headers = headers))
        response.use { resp ->
            val cl = resp.contentLength
            if (cl > maxBytes) {
                throw OversizedPayloadException("Gecko payload size ($cl bytes) exceeds bounded manifest limit of $maxBytes bytes for ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
            }

            val body = resp.body ?: throw IOException("Empty body from Gecko fetch of ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            var totalRead = 0L

            while (true) {
                val read = body.read(buffer)
                if (read == -1) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw OversizedPayloadException("Streaming Gecko payload exceeded bounded manifest limit of $maxBytes bytes for ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(url)}")
                }
                baos.write(buffer, 0, read)
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }

    override suspend fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)?,
        cancellationToken: EngineCancellationToken?
    ): Long {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { fos ->
            return underlying.downloadToStream(
                request = GeckoTransportRequest(url = url, headers = headers),
                output = fos,
                onProgress = { written, total ->
                    onProgress?.invoke(written, if (total > 0) total else null)
                },
                isCancelled = { cancellationToken?.isCancelled() == true }
            )
        }
    }

    override suspend fun head(
        url: String,
        headers: Map<String, String>
    ): TransportHeaderInfo {
        val resp = underlying.fetch(GeckoTransportRequest(url = url, headers = headers))
        resp.use {
            return TransportHeaderInfo(
                statusCode = it.statusCode,
                contentLength = if (it.contentLength >= 0) it.contentLength else null,
                contentType = it.contentType,
                headers = it.headers
            )
        }
    }
}
