package com.mvrk.vrka

import androidx.compose.runtime.Immutable

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
    val resolvedMediaUrl: String? = null,
    val resolvedHeaders: Map<String, String> = emptyMap(),
    val resolvedHeadersOrigin: String = "",
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
    // Byte counts surfaced by the transfer itself. totalIsExact is false when the engine or
    // yt-dlp only produced an estimate (`of ~ 48.20MiB`).
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val totalIsExact: Boolean = false,
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
)

enum class StreamSelectionState {
    IDLE,
    RESOLVING,
    RESOLVED,
    AWAITING_CONFIRMATION,
    QUEUED,
    STARTING,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELLED;
}

@Immutable
data class RuntimeStatus(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val version: String = "",
    val message: String = "Runtime loads only when needed",
)
