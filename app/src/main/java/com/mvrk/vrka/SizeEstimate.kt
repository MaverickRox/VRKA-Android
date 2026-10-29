package com.mvrk.vrka

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class SizeEstimateKind {
    EXACT,
    APPROXIMATE,
    UNAVAILABLE,
}

@Immutable
data class SizeEstimate(
    val kind: SizeEstimateKind,
    val bytes: Long? = null,
    val title: String = "",
    val detail: String = "",
) {
    val isAvailable: Boolean
        get() = bytes != null && bytes > 0

    companion object {
        fun unavailable(detail: String = ""): SizeEstimate =
            SizeEstimate(SizeEstimateKind.UNAVAILABLE, detail = detail)
    }
}

/**
 * Monotonic token source used to reject metadata responses that belong to an older
 * selection. Call [begin] before every preflight and [accepts] before applying a result.
 */
class SizeEstimateGate {
    private var generation = 0

    @Synchronized
    fun begin(): Int = ++generation

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun accepts(token: Int): Boolean = token == generation
}

/**
 * Turns a yt-dlp `--dump-single-json` payload into a final-output size estimate.
 *
 * The estimate mirrors the options the transfer will use: the same format selector
 * determines `requested_formats`, so merged video+audio components are summed, and
 * audio re-encodes are derived from the target bitrate or PCM geometry.
 */
object SizeEstimateCalculator {
    private const val MAX_TITLE_LENGTH = 200
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val DEFAULT_CHANNELS = 2

    fun fromMetadataJson(jsonText: String, request: DownloadRequest): SizeEstimate {
        val root = runCatching { JSONObject(jsonText.trim()) }.getOrNull()
            ?: return SizeEstimate.unavailable("parse")
        val title = root.optString("title").trim().take(MAX_TITLE_LENGTH)
        val entries = root.optJSONArray("entries")
        return if (root.optString("_type") == "playlist" || entries != null) {
            playlist(entries, request, title)
        } else {
            single(root, request, title)
        }
    }

    private fun playlist(entries: JSONArray?, request: DownloadRequest, title: String): SizeEstimate {
        if (entries == null || entries.length() == 0) {
            return SizeEstimate.unavailable("playlist-empty").copy(title = title)
        }
        var total = 0L
        var exact = true
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index)
                ?: return SizeEstimate.unavailable("playlist-entry").copy(title = title)
            val item = single(entry, request, "")
            val bytes = item.bytes
            if (!item.isAvailable || bytes == null) {
                return SizeEstimate.unavailable("playlist-entry").copy(title = title)
            }
            total += bytes
            if (item.kind != SizeEstimateKind.EXACT) exact = false
        }
        if (total <= 0) return SizeEstimate.unavailable("playlist-empty").copy(title = title)
        return SizeEstimate(
            kind = if (exact) SizeEstimateKind.EXACT else SizeEstimateKind.APPROXIMATE,
            bytes = total,
            title = title,
            detail = "playlist",
        )
    }

    private fun single(json: JSONObject, request: DownloadRequest, title: String): SizeEstimate {
        val duration = json.optDouble("duration", Double.NaN)
            .takeIf { it.isFinite() && it > 0 }
        val effectiveDuration = effectiveDuration(request, duration)
        return if (request.mode == MediaMode.AUDIO) {
            audio(json, request, duration, effectiveDuration, title)
        } else {
            video(json, request, duration, effectiveDuration, title)
        }
    }

    private fun video(
        json: JSONObject,
        request: DownloadRequest,
        rawDuration: Double?,
        effectiveDuration: Double?,
        title: String,
    ): SizeEstimate {
        val requested = json.optJSONArray("requested_formats")
        var total = 0L
        var exact = true
        if (requested != null && requested.length() > 0) {
            for (index in 0 until requested.length()) {
                val format = requested.optJSONObject(index)
                    ?: return SizeEstimate.unavailable("format-missing").copy(title = title)
                val size = sizeOf(format)
                    ?: return SizeEstimate.unavailable("size-missing").copy(title = title)
                total += size.first
                if (!size.second) exact = false
            }
        } else {
            val size = sizeOf(json)
                ?: return SizeEstimate.unavailable("size-missing").copy(title = title)
            total = size.first
            exact = size.second
        }
        if (total <= 0) return SizeEstimate.unavailable("size-missing").copy(title = title)

        if (isTrimmed(request)) {
            val scaled = scaleForTrim(total, rawDuration, effectiveDuration)
                ?: return SizeEstimate.unavailable("trim-unknown").copy(title = title)
            return SizeEstimate(SizeEstimateKind.APPROXIMATE, scaled, title, "trim")
        }
        return SizeEstimate(
            kind = if (exact) SizeEstimateKind.EXACT else SizeEstimateKind.APPROXIMATE,
            bytes = total,
            title = title,
        )
    }

    private fun audio(
        json: JSONObject,
        request: DownloadRequest,
        rawDuration: Double?,
        effectiveDuration: Double?,
        title: String,
    ): SizeEstimate {
        val trimmed = isTrimmed(request)
        when (request.audioFormat) {
            AudioFormat.MP3 -> {
                if (effectiveDuration != null) {
                    val bitrate = request.mp3Bitrate.coerceIn(128, 320)
                    val bytes = (bitrate * 125.0 * effectiveDuration).toLong()
                    if (bytes > 0) return SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes, title, "mp3-bitrate")
                }
            }
            AudioFormat.WAV -> {
                if (effectiveDuration != null) {
                    val sampleRate = json.optInt("asr", DEFAULT_SAMPLE_RATE)
                        .takeIf { it in 8_000..384_000 } ?: DEFAULT_SAMPLE_RATE
                    val channels = json.optInt("audio_channels", DEFAULT_CHANNELS).coerceIn(1, 8)
                    val bytes = (effectiveDuration * sampleRate * channels * 2).toLong()
                    if (bytes > 0) return SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes, title, "pcm")
                }
            }
            AudioFormat.OPUS -> Unit
        }

        // The final container differs from the source stream, so a source size is only
        // ever an approximation of the produced file.
        val requested = json.optJSONArray("requested_formats")
        val sourceSize = if (requested != null && requested.length() > 0) {
            requested.optJSONObject(0)?.let(::sizeOf)
        } else {
            sizeOf(json)
        } ?: return SizeEstimate.unavailable("size-missing").copy(title = title)

        var bytes = sourceSize.first
        if (trimmed) {
            bytes = scaleForTrim(bytes, rawDuration, effectiveDuration)
                ?: return SizeEstimate.unavailable("trim-unknown").copy(title = title)
        }
        if (bytes <= 0) return SizeEstimate.unavailable("size-missing").copy(title = title)
        return SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes, title, "source")
    }

    private fun sizeOf(value: JSONObject): Pair<Long, Boolean>? {
        val exact = value.optLong("filesize", 0L)
        if (exact > 0) return exact to true
        val approximate = value.optLong("filesize_approx", 0L)
        if (approximate > 0) return approximate to false
        return null
    }

    private fun isTrimmed(request: DownloadRequest): Boolean =
        request.trimStart.isNotBlank() || request.trimEnd.isNotBlank()

    private fun effectiveDuration(request: DownloadRequest, duration: Double?): Double? {
        if (!isTrimmed(request)) return duration
        if (duration == null || duration <= 0) return null
        val start = parseTimestampSeconds(request.trimStart) ?: 0.0
        val end = parseTimestampSeconds(request.trimEnd)?.coerceAtMost(duration) ?: duration
        return (end - start).takeIf { it > 0 }
    }

    private fun scaleForTrim(bytes: Long, rawDuration: Double?, effectiveDuration: Double?): Long? {
        if (rawDuration == null || rawDuration <= 0 || effectiveDuration == null) return null
        return (bytes * (effectiveDuration / rawDuration)).toLong().takeIf { it > 0 }
    }

    private fun parseTimestampSeconds(value: String): Double? {
        if (value.isBlank()) return null
        val parts = value.trim().split(':')
        if (parts.size !in 1..3) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { it < 0 || !it.isFinite() }) return null
        if (parts.size > 1 && numbers.last() >= 60) return null
        if (parts.size == 3 && numbers[1] >= 60) return null
        return numbers.fold(0.0) { total, part -> total * 60 + part }
    }
}

/** Pure preference resolution so the default-enabled contract is testable. */
object SizeConfirmationPreference {
    fun resolve(stored: Boolean?): Boolean = stored ?: true
}
