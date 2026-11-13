package com.mvrk.vrka

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.UUID

internal const val INTERRUPTED_DOWNLOAD_ERROR =
    "Download was interrupted when Android stopped VRKA. Tap retry."

/**
 * Automatic native yt-dlp attempts per logical job. Automatic retries were explicitly
 * rejected; a classified failure enters browser fallback immediately or is reported with
 * the manual Retry action. This constant documents and pins the single-attempt contract.
 */
internal const val MAX_NATIVE_TRANSFER_ATTEMPTS = 1

enum class JobState {
    QUEUED,
    PREPARING,
    WAITING_FOR_USER,
    BROWSER_FALLBACK,
    DOWNLOADING,
    POSTPROCESSING,
    DONE,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED

    val isForegroundWork: Boolean
        get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == POSTPROCESSING || this == BROWSER_FALLBACK

    val label: String
        get() = when (this) {
            QUEUED -> "Queued"
            PREPARING -> "Preparing"
            WAITING_FOR_USER -> "Waiting for you"
            BROWSER_FALLBACK -> "Browser fallback"
            DOWNLOADING -> "Downloading"
            POSTPROCESSING -> "Finishing"
            DONE -> "Complete"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
        }
}

enum class MediaMode(val label: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
}

/** The stream currently being transferred for a merged video+audio selection. */
enum class ActiveStream {
    VIDEO,
    AUDIO,
    MEDIA,
}

enum class VideoQuality(val label: String, val height: Int?) {
    BEST("Best available", null),
    P2160("2160p", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

enum class AudioFormat(val label: String, val codec: String) {
    MP3("MP3 (compressed)", "mp3"),
    OPUS("Opus (prefer native)", "opus"),
    WAV("WAV (uncompressed)", "wav"),
}

enum class SaveLocationMode(val label: String) {
    REMEMBER_LOCATION("Use selected"),
    ASK_EVERY_TIME("Ask every time"),
}

enum class FontPreference(val label: String) {
    VRKA_FONT("VRKA Font"),
    SYSTEM_FONT("System Font"),
}

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
}

enum class UpdatePreference(val label: String) {
    STABLE("Stable"),
    NIGHTLY("Nightly"),
}

@Immutable
data class DownloadRequest(
    val url: String,
    val mode: MediaMode = MediaMode.VIDEO,
    val quality: VideoQuality = VideoQuality.BEST,
    val prefer60Fps: Boolean = false,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val mp3Bitrate: Int = 320,
    val isPlaylist: Boolean = false,
    val playlistStart: Int? = null,
    val playlistEnd: Int? = null,
    val downloadSubtitles: Boolean = false,
    val automaticCaptions: Boolean = true,
    val embedSubtitles: Boolean = true,
    val subtitleLanguages: String = "en.*",
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val sponsorBlock: Boolean = false,
    val sponsorCategories: String = "sponsor,selfpromo,interaction",
    val trimStart: String = "",
    val trimEnd: String = "",
    val customArguments: List<String> = emptyList(),
    val commandTemplateId: String? = null,
    val commandTemplateName: String = "",
    val commandTemplateArguments: List<String> = emptyList(),
    val h264CompatibilityMode: Boolean = false,
    val referer: String = "",
    val origin: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val destinationTreeUri: String? = null,
    val playlistGroupId: String? = null,
    val playlistItemId: String? = null,
    val playlistItemIndex: Int? = null,
    val playlistItemCount: Int? = null,
    val resolvedMediaUrl: String? = null,
    val resolvedHeaders: Map<String, String> = emptyMap(),
    val resolvedHeadersOrigin: String = "",
)

@Immutable
data class PlaylistItem(
    val url: String,
    val title: String,
    val playlistItemIndex: Int,
    val playlistItemCount: Int,
    val playlistItemId: String = UUID.randomUUID().toString(),
    val estimate: SizeEstimate? = null,
)

object PlaylistRouting {
    fun isYouTubeHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val cleanHost = host.lowercase().removePrefix("www.")
        return cleanHost == "youtube.com" ||
            cleanHost.endsWith(".youtube.com") ||
            cleanHost == "youtu.be"
    }

    fun isPurePlaylistUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!isYouTubeHost(uri.host)) return false
        val path = uri.path.orEmpty()
        val listId = uri.getQueryParameter("list")?.trim()
        val hasValidList = !listId.isNullOrBlank() && listId.length >= 2
        val hasVideo = uri.getQueryParameter("v")?.isNotBlank() == true
        return path.contains("/playlist") && hasValidList && !hasVideo
    }

    fun isVideoWithPlaylistContext(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!isYouTubeHost(uri.host)) return false
        val listId = uri.getQueryParameter("list")?.trim()
        val hasValidList = !listId.isNullOrBlank() && listId.length >= 2
        if (!hasValidList) return false

        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        val path = uri.path.orEmpty()
        val hasVideoParam = uri.getQueryParameter("v")?.isNotBlank() == true
        val isYoutuBeVideo = host == "youtu.be" && uri.pathSegments.firstOrNull()?.isNotBlank() == true
        val isShortsVideo = path.contains("/shorts/") && uri.pathSegments.lastOrNull()?.isNotBlank() == true
        val isEmbedVideo = path.contains("/embed/") && uri.pathSegments.lastOrNull()?.isNotBlank() == true

        return hasVideoParam || isYoutuBeVideo || isShortsVideo || isEmbedVideo
    }

    fun stripPlaylistContext(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (!isYouTubeHost(uri.host)) return url
        if (uri.getQueryParameter("list") == null) return url
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { param ->
            if (param != "list" && param != "index") {
                uri.getQueryParameters(param).forEach { value ->
                    builder.appendQueryParameter(param, value)
                }
            }
        }
        return builder.build().toString()
    }

    fun extractPlaylistUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!isYouTubeHost(uri.host)) return null
        val listId = uri.getQueryParameter("list")?.trim()?.takeIf { it.length >= 2 } ?: return null
        return "https://www.youtube.com/playlist?list=$listId"
    }
}

/**
 * A confirmed request paired with the preflight estimate that was visible when the user
 * submitted it. The estimate is informational only: it never blocks the download and is
 * replaced by the transfer's own reported size once bytes move.
 */
internal data class PendingDownload(
    val request: DownloadRequest,
    val estimate: SizeEstimate?,
)

@Immutable
data class DownloadJob(
    val id: String,
    val request: DownloadRequest,
    val state: JobState = JobState.QUEUED,
    val title: String = "",
    val detail: String = "Waiting",
    val progress: Float = 0f,
    val speed: String = "",
    val etaSeconds: Long? = null,
    // Sizes have three distinct sources and are never mixed:
    // - estimatedTotalBytes: metadata preflight, before any bytes move;
    // - reportedTotalBytes: the total printed by the active transfer stream(s);
    // - actualFinalFileBytes: the published document's size after completion.
    val estimatedTotalBytes: Long? = null,
    val estimatedSizeAccuracy: SizeEstimateKind = SizeEstimateKind.UNAVAILABLE,
    val downloadedBytes: Long? = null,
    val reportedTotalBytes: Long? = null,
    val reportedTotalIsExact: Boolean = false,
    // The active transfer scope. `progress`, downloadedBytes and reportedTotalBytes all
    // describe this stream/item; they must never be mixed with a job-level aggregate.
    val activeStream: ActiveStream? = null,
    val playlistItemIndex: Int? = null,
    val playlistItemCount: Int? = null,
    // Size of the published output as reported by the content provider. Only set after the
    // file exists in its final destination, so it never inherits an estimate.
    val actualFinalFileBytes: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val outputUris: List<String> = emptyList(),
    val error: String = "",
    val attempt: Int = 1,
    val actualResolution: String? = null,
    // Runtime that executed this job's transfer. Captured when the yt-dlp process starts so
    // the record survives history reloads and runtime switches that happen afterwards.
    val runtimeName: String = "",
    val runtimeVersion: String = "",
    val subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus = com.mvrk.vrka.subtitle.SubtitleStatus.NONE,
    // Persisted before the first file is handed to MediaStore/DocumentsProvider. A job that
    // died after this point is not auto-resumed because its outputs may already be published.
    val publishStarted: Boolean = false,
    // Durable storage metadata. These are not part of the legacy JSON contract.
    val recoveryRequired: Boolean = false,
    val legacyVerification: String = "",
    val playlistGroupId: String? = null,
)

@Immutable
data class RuntimeStatus(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val version: String = "",
    val message: String = "Runtime loads only when needed",
)
