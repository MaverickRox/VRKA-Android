package com.mvrk.vrka.engine

import java.io.File

/**
 * Normalized media kind captured from web sniffing or direct request.
 */
enum class EngineMediaKind {
    HLS,
    DASH,
    PROGRESSIVE_VIDEO,
    PROGRESSIVE_AUDIO,
    UNKNOWN;

    companion object {
        fun fromUrlOrMime(url: String, mime: String?): EngineMediaKind {
            val lowerUrl = url.lowercase()
            val lowerMime = mime?.lowercase().orEmpty()
            return when {
                lowerUrl.contains(".m3u8") || lowerMime.contains("mpegurl") || lowerMime.contains("application/x-mpegurl") -> HLS
                lowerUrl.contains(".mpd") || lowerMime.contains("dash+xml") -> DASH
                lowerUrl.contains(".mp4") || lowerMime.contains("video/mp4") || lowerMime.contains("video/webm") -> PROGRESSIVE_VIDEO
                lowerUrl.contains(".mp3") || lowerUrl.contains(".m4a") || lowerMime.contains("audio/") -> PROGRESSIVE_AUDIO
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Handoff context containing validated network credentials, headers, and media target.
 */
data class MediaHandoffContext(
    val url: String,
    val mediaKind: EngineMediaKind,
    val headers: Map<String, String>,
    val title: String,
    val stagingOutputFile: File,
    val durationSeconds: Double? = null,
    val expectedHeight: Int? = null,
    val originalPageUrl: String? = null,
)

/**
 * Real-time progress updates during download, assembly, and remuxing.
 */
sealed class EngineDownloadProgress {
    data class Initializing(val message: String) : EngineDownloadProgress()
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long?,
        val percentage: Float,
        val speedBytesPerSec: Long? = null,
        val etaSeconds: Long? = null,
        val detail: String = ""
    ) : EngineDownloadProgress()
    data class Finalizing(val message: String) : EngineDownloadProgress()
}

/**
 * Final execution result from the media engine.
 */
sealed class EngineExecutionResult {
    data class Success(val outputFile: File, val totalBytes: Long) : EngineExecutionResult()
    data class Failed(val error: Throwable, val reason: String) : EngineExecutionResult()
    object Cancelled : EngineExecutionResult()
}

/**
 * Engine cancellation token interface.
 */
interface EngineCancellationToken {
    fun cancel()
    fun isCancelled(): Boolean
}

class SimpleEngineCancellationToken : EngineCancellationToken {
    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun isCancelled(): Boolean = cancelled
}

/**
 * Engine-neutral abstraction for all media downloading, assembly, and container muxing.
 */
interface MediaDownloadEngine {
    val engineName: String

    suspend fun download(
        handoffContext: MediaHandoffContext,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult

    fun isAvailable(): Boolean
}
