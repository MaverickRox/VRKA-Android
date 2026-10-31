package com.mvrk.vrka

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class ShareMediaType {
    VIDEO,
    AUDIO,
    COMMANDS,
}

@Immutable
data class SharedLinkRequest(
    val url: String,
    val id: String,
)

/**
 * Persisted share-card choices. Deliberately contains no URL, headers, or tokens, and is
 * independent of the global size-confirmation preference.
 */
@Immutable
data class ShareSheetPreferences(
    val mode: MediaMode = MediaMode.VIDEO,
    val quality: VideoQuality = VideoQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val mp3Bitrate: Int = 320,
    val downloadSubtitles: Boolean = false,
    val embedThumbnail: Boolean = true,
    val useCommands: Boolean = false,
)

object SharePreferencesCodec {
    private val BITRATES = setOf(128, 160, 192, 224, 256, 320)

    fun encode(preferences: ShareSheetPreferences): String = JSONObject()
        .put("mode", preferences.mode.name)
        .put("quality", preferences.quality.name)
        .put("audioFormat", preferences.audioFormat.name)
        .put("mp3Bitrate", preferences.mp3Bitrate)
        .put("downloadSubtitles", preferences.downloadSubtitles)
        .put("embedThumbnail", preferences.embedThumbnail)
        .put("useCommands", preferences.useCommands)
        .toString()

    fun decode(text: String?): ShareSheetPreferences? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(text)
            ShareSheetPreferences(
                mode = enumValue(json.optString("mode"), MediaMode.VIDEO),
                quality = enumValue(json.optString("quality"), VideoQuality.BEST),
                audioFormat = enumValue(json.optString("audioFormat"), AudioFormat.MP3),
                mp3Bitrate = json.optInt("mp3Bitrate", 320).takeIf { it in BITRATES } ?: 320,
                downloadSubtitles = json.optBoolean("downloadSubtitles", false),
                embedThumbnail = json.optBoolean("embedThumbnail", true),
                useCommands = json.optBoolean("useCommands", false),
            )
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

object ShareRequestBuilder {
    fun build(
        url: String,
        preferences: ShareSheetPreferences,
        type: ShareMediaType,
        customTemplate: YtDlpCommandTemplate? = null,
    ): DownloadRequest {
        val bitrate = preferences.mp3Bitrate.coerceIn(128, 320)
        val audio = type == ShareMediaType.AUDIO
        val subtitleEnabled = preferences.downloadSubtitles && type != ShareMediaType.COMMANDS
        val base = DownloadRequest(
            url = url,
            mode = if (audio) MediaMode.AUDIO else MediaMode.VIDEO,
            quality = preferences.quality,
            audioFormat = preferences.audioFormat,
            mp3Bitrate = bitrate,
            downloadSubtitles = subtitleEnabled,
            automaticCaptions = subtitleEnabled,
            embedSubtitles = subtitleEnabled && type == ShareMediaType.VIDEO,
            embedMetadata = true,
            embedThumbnail = audio &&
                preferences.embedThumbnail &&
                preferences.audioFormat != AudioFormat.WAV,
        )
        if (type != ShareMediaType.COMMANDS) return base
        return base.copy(
            downloadSubtitles = false,
            automaticCaptions = true,
            embedSubtitles = false,
            embedThumbnail = false,
            commandTemplateId = customTemplate?.id,
            commandTemplateName = customTemplate?.name.orEmpty(),
            commandTemplateArguments = customTemplate?.arguments.orEmpty(),
        )
    }
}

object ShareLinkValidator {
    private val URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_REGEX.find(text.trim())?.value ?: return null
        return match.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
    }

    fun isValid(url: String?): Boolean = url != null && CustomCommandPolicy.isHttpUrl(url)
}

/**
 * Suppresses duplicate deliveries of the same share intent (cold start, activity recreation,
 * duplicate onNewIntent) without blocking a genuinely newer share of the same text.
 */
class ShareIntentTracker(
    private val windowMs: Long = 1_500L,
) {
    private var lastFingerprint: String? = null
    private var lastAt = 0L
    private var restoredFingerprint: String? = null

    @Synchronized
    fun restore(fingerprint: String?, at: Long) {
        lastFingerprint = fingerprint
        lastAt = at
        restoredFingerprint = fingerprint
    }

    @Synchronized
    fun accept(fingerprint: String, nowMs: Long): Boolean {
        if (fingerprint == restoredFingerprint) {
            restoredFingerprint = null
            return false
        }
        val duplicate = fingerprint == lastFingerprint &&
            nowMs - lastAt in 0..windowMs
        if (!duplicate) {
            lastFingerprint = fingerprint
            lastAt = nowMs
        }
        return !duplicate
    }

    @Synchronized
    fun lastFingerprint(): String? = lastFingerprint

    @Synchronized
    fun lastSeenAt(): Long = lastAt

    companion object {
        fun fingerprint(action: String?, data: String?, text: String?): String =
            action.orEmpty() + "|" + data.orEmpty() + "|" + text.orEmpty()
    }
}

internal fun String.hostLabel(): String =
    runCatching { java.net.URI(this).host?.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { this }
