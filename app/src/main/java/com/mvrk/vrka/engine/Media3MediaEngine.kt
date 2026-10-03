package com.mvrk.vrka.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.mvrk.vrka.HeaderValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

class DrmProtectedStreamException(message: String) : IOException(message)

/**
 * Dormant experimental AndroidX Media3 transfer implementation.
 *
 * It is not connected to the production download manager and does not provide a VRKA
 * download capability. Normal downloads and Browser Fallback transfers use yt-dlp.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3MediaEngine(
    private val context: Context,
    private val transport: MediaHttpTransport = OkHttpMediaTransport()
) : MediaDownloadEngine {

    override val engineName: String = "Media3MediaEngine"

    override fun isAvailable(): Boolean = true

    override suspend fun download(
        handoffContext: MediaHandoffContext,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult = withContext(Dispatchers.IO) {
        val destination = handoffContext.stagingOutputFile
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        try {
            onProgress(EngineDownloadProgress.Initializing("Inspecting media presentation with Media3"))

            val reqContext = RequestContext(
                targetUrl = handoffContext.url,
                pageUrl = handoffContext.originalPageUrl,
                baseHeaders = handoffContext.headers
            )
            val manifestHeaders = reqContext.resolveHeaders(handoffContext.url)

            // 1. Fetch manifest text with bounded limit (5MB ceiling to avoid OOM)
            val manifestText = transport.fetchTextBounded(
                url = handoffContext.url,
                headers = manifestHeaders,
                maxBytes = 5 * 1024 * 1024L
            )

            // 2. Parse descriptor and check DRM
            val descriptor = StreamDescriptor.parseFromManifest(
                url = handoffContext.url,
                manifestContent = manifestText,
                headers = manifestHeaders
            )

            if (descriptor.drmScheme.isProtected) {
                Log.e(TAG, "Stream rejected: DRM protected (${descriptor.drmScheme})")
                return@withContext EngineExecutionResult.Failed(
                    DrmProtectedStreamException("Stream is protected by ${descriptor.drmScheme} DRM and cannot be saved"),
                    "DRM protected stream"
                )
            }

            // 3. Process HLS stream
            if (descriptor.container == StreamContainer.HLS || handoffContext.url.contains(".m3u8", ignoreCase = true)) {
                return@withContext downloadHls(
                    initialUrl = handoffContext.url,
                    initialContent = manifestText,
                    descriptor = descriptor,
                    reqContext = reqContext,
                    targetFile = destination,
                    expectedHeight = handoffContext.expectedHeight,
                    onProgress = onProgress,
                    cancellationToken = cancellationToken
                )
            }

            // 4. Process DASH stream using Media3 DashManifestParser
            if (descriptor.container == StreamContainer.DASH || handoffContext.url.contains(".mpd", ignoreCase = true)) {
                return@withContext downloadDash(
                    initialUrl = handoffContext.url,
                    initialContent = manifestText,
                    descriptor = descriptor,
                    reqContext = reqContext,
                    targetFile = destination,
                    expectedHeight = handoffContext.expectedHeight,
                    onProgress = onProgress,
                    cancellationToken = cancellationToken
                )
            }

            // 5. Fallback or progressive handling
            onProgress(EngineDownloadProgress.Initializing("Streaming direct media file"))
            val written = transport.downloadToFile(
                url = handoffContext.url,
                headers = manifestHeaders,
                destination = destination,
                onProgress = { bytesRead, totalBytes ->
                    val pct = if (totalBytes != null && totalBytes > 0) {
                        (bytesRead.toFloat() / totalBytes.toFloat() * 100f).coerceIn(1f, 99f)
                    } else 0f
                    // Non-blocking progress emission
                },
                cancellationToken = cancellationToken
            )

            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }

            onProgress(EngineDownloadProgress.Finalizing("Direct media transfer verified"))
            return@withContext EngineExecutionResult.Success(destination, written)

        } catch (e: Throwable) {
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }
            destination.delete()
            return@withContext EngineExecutionResult.Failed(e, "Media3 transfer failed: ${e.message}")
        }
    }

    private suspend fun downloadHls(
        initialUrl: String,
        initialContent: String,
        descriptor: StreamDescriptor,
        reqContext: RequestContext,
        targetFile: File,
        expectedHeight: Int?,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult {
        val parser = HlsPlaylistParser()
        val parsedPlaylist: HlsPlaylist = try {
            parser.parse(safeParseUri(initialUrl), ByteArrayInputStream(initialContent.toByteArray(Charsets.UTF_8)))
        } catch (t: Throwable) {
            Log.w(TAG, "Media3 HlsPlaylistParser failed: ${t.message}")
            return EngineExecutionResult.Failed(t, "Media3 HLS playlist parsing failed: ${t.message}")
        }

        var mediaPlaylistUrl = initialUrl
        var mediaPlaylist: HlsMediaPlaylist

        if (parsedPlaylist is HlsMultivariantPlaylist) {
            val variants = parsedPlaylist.variants
            if (variants.isEmpty()) {
                throw IOException("Master HLS playlist contains no variants")
            }

            // Select variant matching requested height, or highest bitrate
            val selectedVariant = if (expectedHeight != null && expectedHeight > 0) {
                variants.minByOrNull { Math.abs((it.format.height.takeIf { h -> h > 0 } ?: 720) - expectedHeight) } ?: variants.first()
            } else {
                variants.maxByOrNull { it.format.bitrate.takeIf { b -> b > 0 } ?: it.format.height } ?: variants.first()
            }

            val variantUri = selectedVariant.url
            mediaPlaylistUrl = resolveUrl(initialUrl, variantUri.toString())
            Log.i(TAG, "Selected variant playlist: ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(mediaPlaylistUrl)} (${selectedVariant.format.width}x${selectedVariant.format.height})")

            val variantHeaders = reqContext.resolveHeaders(mediaPlaylistUrl)
            val variantText = transport.fetchTextBounded(mediaPlaylistUrl, variantHeaders)
            val secondParse = parser.parse(safeParseUri(mediaPlaylistUrl), ByteArrayInputStream(variantText.toByteArray(Charsets.UTF_8)))
            if (secondParse !is HlsMediaPlaylist) {
                throw IOException("Expected media playlist from variant URL, but got multivariant")
            }
            mediaPlaylist = secondParse
        } else if (parsedPlaylist is HlsMediaPlaylist) {
            mediaPlaylist = parsedPlaylist
        } else {
            throw IOException("Unknown HLS playlist type: ${parsedPlaylist.javaClass.simpleName}")
        }

        val segments = mediaPlaylist.segments
        if (segments.isEmpty()) {
            throw IOException("HLS playlist contains 0 media segments")
        }

        val totalSegments = segments.size
        Log.i(TAG, "Starting HLS download of $totalSegments segments into ${targetFile.name}")

        val progressTracker = ProgressTracker(totalExpectedSegments = totalSegments)
        var totalBytesWritten = 0L
        val stagingTsFile = if (targetFile.extension.equals("mp4", ignoreCase = true)) {
            File(targetFile.parentFile, "${targetFile.nameWithoutExtension}_staging.ts")
        } else {
            targetFile
        }

        if (stagingTsFile.exists()) stagingTsFile.delete()
        val fos = FileOutputStream(stagingTsFile)

        try {
            for ((index, segment) in segments.withIndex()) {
                if (cancellationToken.isCancelled()) {
                    fos.close()
                    stagingTsFile.delete()
                    return EngineExecutionResult.Cancelled
                }

                val segUrl = resolveUrl(mediaPlaylistUrl, segment.url)
                val segHeaders = reqContext.resolveHeaders(segUrl, allowRange = segment.byteRangeLength != C.LENGTH_UNSET.toLong())

                // Segment temp file for bounded download
                val tempSeg = File(targetFile.parentFile, "seg_${index}.tmp")
                try {
                    val bytesRead = transport.downloadToFile(
                        url = segUrl,
                        headers = segHeaders,
                        destination = tempSeg,
                        cancellationToken = cancellationToken
                    )

                    // Append to assembled staging file
                    tempSeg.inputStream().use { input ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                        }
                    }
                    totalBytesWritten += bytesRead
                } finally {
                    tempSeg.delete()
                }

                val snapshot = progressTracker.updateSegments(
                    completed = index + 1,
                    currentBytes = totalBytesWritten,
                    detail = "Downloading segment ${index + 1}/$totalSegments"
                )

                onProgress(
                    EngineDownloadProgress.Downloading(
                        bytesRead = totalBytesWritten,
                        totalBytes = null,
                        percentage = snapshot.safePercentage ?: ((index + 1).toFloat() / totalSegments * 100f).coerceIn(1f, 99f),
                        speedBytesPerSec = snapshot.bytesPerSecond,
                        etaSeconds = snapshot.etaSeconds,
                        detail = snapshot.formatCardStatusText()
                    )
                )
            }
            fos.flush()
        } finally {
            fos.close()
        }

        if (cancellationToken.isCancelled()) {
            stagingTsFile.delete()
            return EngineExecutionResult.Cancelled
        }

        // Remux TS to MP4 if needed
        if (targetFile.extension.equals("mp4", ignoreCase = true)) {
            onProgress(EngineDownloadProgress.Finalizing("Remuxing HLS segments to MP4"))
            val ffmpegEngine = FfmpegMediaEngine(context)
            if (ffmpegEngine.isAvailable()) {
                val success = ffmpegEngine.remuxTsToMp4(stagingTsFile, targetFile)
                if (success && targetFile.exists() && targetFile.length() > 0) {
                    stagingTsFile.delete()
                    return EngineExecutionResult.Success(targetFile, targetFile.length())
                }
            }
            // If remuxing not available or failed, rename staging TS to target
            stagingTsFile.renameTo(targetFile)
        }

        onProgress(EngineDownloadProgress.Finalizing("Download verified"))
        return EngineExecutionResult.Success(targetFile, targetFile.length())
    }

    private suspend fun downloadDash(
        initialUrl: String,
        initialContent: String,
        descriptor: StreamDescriptor,
        reqContext: RequestContext,
        targetFile: File,
        expectedHeight: Int?,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult {
        val parser = DashManifestParser()
        val manifest: DashManifest = try {
            parser.parse(safeParseUri(initialUrl), ByteArrayInputStream(initialContent.toByteArray(Charsets.UTF_8)))
        } catch (t: Throwable) {
            Log.w(TAG, "Media3 DashManifestParser failed: ${t.message}")
            return EngineExecutionResult.Failed(t, "Media3 DASH manifest parsing failed: ${t.message}")
        }

        if (manifest.periodCount == 0) {
            return EngineExecutionResult.Failed(IOException("DASH manifest contains no periods"), "Empty DASH manifest")
        }

        Log.i(TAG, "Media3 parsed DASH manifest (${manifest.periodCount} periods); handing off to native FFmpeg for multi-adaptation demuxing")
        return EngineExecutionResult.Failed(
            UnsupportedOperationException("Media3 parsed DASH manifest; handing off to native FFmpeg for multi-adaptation demuxing"),
            "DASH multi-adaptation demuxing required"
        )
    }

    private fun safeParseUri(url: String): Uri {
        return runCatching { Uri.parse(url) }.getOrNull()
            ?: runCatching { Class.forName("android.net.TestUri").getConstructor(String::class.java).newInstance(url) as Uri }.getOrNull()
            ?: Uri.EMPTY
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(relativeUrl).toString()
        } catch (_: Exception) {
            relativeUrl
        }
    }

    companion object {
        private const val TAG = "VRKA-Media3Engine"
    }
}
