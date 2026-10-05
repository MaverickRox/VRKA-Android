package com.mvrk.vrka.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import java.io.File
import java.io.IOException

/**
 * Dormant experimental transfer router; it is not called by the production download flow.
 *
 * The active normal and Browser Fallback transfer path uses yt-dlp. GeckoView is used for
 * Browser Fallback navigation and discovery, and FFmpeg is used for local processing,
 * probing, and validation. Do not describe this router or its Media3/FFmpeg/N_m3u8DL-RE
 * engines as active download capabilities unless a separately reviewed product change wires
 * them into the production manager.
 */
class MediaEngineRouter(
    private val context: Context,
    private val geckoRuntime: GeckoRuntime? = null,
    private val customEngines: List<MediaDownloadEngine>? = null
) {
    companion object {
        private const val TAG = "VRKA-MediaRouter"
    }

    private val ffmpegEngine = FfmpegMediaEngine(context)
    private val media3Engine = Media3MediaEngine(context)
    private val nm3u8dlreAdapter = Nm3u8dlreEngineAdapter(context)

    /**
     * Executes the download through the optimal engine pipeline with classified failover.
     */
    suspend fun executeDownload(
        handoffContext: MediaHandoffContext,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult = withContext(Dispatchers.IO) {
        val destination = handoffContext.stagingOutputFile
        destination.parentFile?.mkdirs()

        // 1. Check DRM early if manifest is HLS/DASH
        val isManifest = handoffContext.mediaKind in listOf(EngineMediaKind.HLS, EngineMediaKind.DASH) ||
                handoffContext.url.contains(".m3u8", ignoreCase = true) ||
                handoffContext.url.contains(".mpd", ignoreCase = true)

        if (isManifest) {
            val drmCheckResult = probeDrmProtection(handoffContext)
            if (drmCheckResult != null) {
                return@withContext drmCheckResult
            }
        }

        // 2. Select initial primary engine
        val engines = selectEngineChain(handoffContext, isManifest)
        var lastError: Throwable? = null
        var lastReason = ""

        for (engine in engines) {
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }

            Log.i(TAG, "Attempting media transfer with engine: ${engine.engineName} for ${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(handoffContext.url)}")
            onProgress(EngineDownloadProgress.Initializing("Starting media transfer via ${engine.engineName}"))

            val result = engine.download(
                handoffContext = handoffContext,
                onProgress = onProgress,
                cancellationToken = cancellationToken
            )

            when (result) {
                is EngineExecutionResult.Success -> {
                    Log.i(TAG, "Engine ${engine.engineName} succeeded (${result.totalBytes} bytes)")
                    return@withContext result
                }
                is EngineExecutionResult.Cancelled -> {
                    Log.i(TAG, "Download cancelled by user")
                    destination.delete()
                    return@withContext EngineExecutionResult.Cancelled
                }
                is EngineExecutionResult.Failed -> {
                    val failureCategory = classifyDownloadError(result.reason.ifBlank { result.error.message.orEmpty() })
                    Log.w(TAG, "Engine ${engine.engineName} failed (category: ${failureCategory.value}): ${result.reason} (${result.error.message})")
                    lastError = result.error
                    lastReason = result.reason

                    // Stop immediately on DRM or fatal OOM or storage error
                    if (result.error is DrmProtectedStreamException ||
                        result.error is OutOfMemoryError ||
                        failureCategory == FailureCategory.DRM ||
                        failureCategory == FailureCategory.STORAGE_ERROR ||
                        result.reason.contains("DRM", ignoreCase = true)) {
                        destination.delete()
                        return@withContext result
                    }

                    // If 403 Forbidden and GeckoRuntime is available, attempt browser context transport fallback
                    if (isForbiddenError(result.error) && geckoRuntime != null && engine !is Media3MediaEngine) {
                        Log.i(TAG, "HTTP 403 Forbidden encountered; attempting GeckoBrowserContextTransport failover")
                        val geckoTransport = GeckoBrowserContextTransport(geckoRuntime)
                        val geckoMedia3 = Media3MediaEngine(context, geckoTransport)
                        val geckoResult = geckoMedia3.download(handoffContext, onProgress, cancellationToken)
                        if (geckoResult is EngineExecutionResult.Success || geckoResult is EngineExecutionResult.Cancelled) {
                            return@withContext geckoResult
                        }
                    }
                }
            }
        }

        destination.delete()
        return@withContext EngineExecutionResult.Failed(
            lastError ?: IOException("All media engines failed to download stream"),
            lastReason.ifBlank { "Media engine pipeline exhausted" }
        )
    }

    private fun selectEngineChain(handoffContext: MediaHandoffContext, isManifest: Boolean): List<MediaDownloadEngine> {
        if (!customEngines.isNullOrEmpty()) {
            return customEngines
        }
        val chain = mutableListOf<MediaDownloadEngine>()

        if (isManifest) {
            chain.add(media3Engine)
            if (ffmpegEngine.isAvailable()) {
                chain.add(ffmpegEngine)
            }
            if (nm3u8dlreAdapter.isAvailable()) {
                chain.add(nm3u8dlreAdapter)
            }
        } else {
            // Progressive stream
            chain.add(media3Engine)
            if (ffmpegEngine.isAvailable()) {
                chain.add(ffmpegEngine)
            }
        }

        return chain
    }

    private suspend fun probeDrmProtection(handoffContext: MediaHandoffContext): EngineExecutionResult.Failed? {
        return try {
            val reqContext = RequestContext(
                targetUrl = handoffContext.url,
                pageUrl = handoffContext.originalPageUrl,
                baseHeaders = handoffContext.headers
            )
            val transport = OkHttpMediaTransport()
            val text = transport.fetchTextBounded(
                url = handoffContext.url,
                headers = reqContext.resolveHeaders(handoffContext.url),
                maxBytes = 1024 * 1024L // 1 MB probe
            )
            val desc = StreamDescriptor.parseFromManifest(handoffContext.url, text)
            if (desc.drmScheme.isProtected) {
                EngineExecutionResult.Failed(
                    DrmProtectedStreamException("Protected by ${desc.drmScheme} DRM; cannot download"),
                    "DRM protected stream"
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isForbiddenError(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        return msg.contains("403", ignoreCase = true) ||
                msg.contains("forbidden", ignoreCase = true) ||
                msg.contains("access denied", ignoreCase = true)
    }
}
