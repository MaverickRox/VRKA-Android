package com.mvrk.vrka

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
    /** Local, non-UI explanation of how the estimate was produced or why it failed. */
    val diagnostic: String = "",
) {
    val isAvailable: Boolean
        get() = bytes != null && bytes > 0

    companion object {
        fun unavailable(detail: String = "", diagnostic: String = ""): SizeEstimate =
            SizeEstimate(SizeEstimateKind.UNAVAILABLE, detail = detail, diagnostic = diagnostic)
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

/** One selectable video height with the locally calculated size for that selection. */
@Immutable
data class FormatSizeOption(
    val height: Int,
    val fps: Double? = null,
    val bytes: Long? = null,
)

/**
 * User-facing view of a single metadata document: title, available video heights with local
 * size estimates, and the subtitle languages the source offers. It is derived from the same
 * cached document used by [SizeEstimateCalculator], so the format selector, subtitle summary
 * and share card never trigger separate yt-dlp extractions.
 *
 * Sizes here are always labelled approximate by the UI because they are re-selected locally
 * from the format table rather than reported for a chosen `requested_formats` pair.
 */
@Immutable
data class MetadataSnapshot(
    val title: String = "",
    val durationSeconds: Double? = null,
    val videoOptions: List<FormatSizeOption> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val automaticCaptionLanguages: List<String> = emptyList(),
    val isPlaylist: Boolean = false,
) {
    val hasSubtitles: Boolean
        get() = subtitleLanguages.isNotEmpty() || automaticCaptionLanguages.isNotEmpty()

    fun sizeFor(height: Int?): FormatSizeOption? =
        height?.let { selected -> videoOptions.firstOrNull { it.height == selected } }
}

/**
 * Turns a yt-dlp `--dump-single-json` payload into a final-output size estimate.
 *
 * The estimate mirrors the options the transfer will use: the same format selector
 * determines `requested_formats`, so merged video+audio components are summed. When a
 * component omits `filesize`/`filesize_approx`, the matching entry from `formats` is
 * consulted, and a bitrate+duration approximation is used as the last resort. Values that
 * are not exact are always reported as approximate.
 */
object SizeEstimateCalculator {
    private const val MAX_TITLE_LENGTH = 200
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val DEFAULT_CHANNELS = 2

    /**
     * @param recalculateSelection when the document was produced for a different quality or
     * format, `requested_formats` describes the old selection. In that case the streams are
     * re-selected locally from the cached `formats` table so no second extraction is needed.
     * Those results are always approximate because the local ranking mirrors, but cannot
     * guarantee, yt-dlp's own sort order.
     */
    fun fromMetadataJson(
        jsonText: String,
        request: DownloadRequest,
        recalculateSelection: Boolean = false,
    ): SizeEstimate {
        val root = parseRoot(jsonText)
            ?: return SizeEstimate.unavailable(
                detail = "parse",
                diagnostic = "stdout did not contain a complete JSON object (${jsonText.length} chars)",
            )
        val title = root.optString("title").trim().take(MAX_TITLE_LENGTH)
        val entries = root.optJSONArray("entries")
        return if (root.optString("_type") == "playlist" || entries != null) {
            playlist(entries, request, title, recalculateSelection)
        } else {
            single(root, request, title, recalculateSelection)
        }
    }

    /**
     * Builds the shared UI metadata view from the same document that answers size questions.
     * Returns null only when the payload is not a JSON object at all.
     */
    fun snapshotFromMetadataJson(jsonText: String, request: DownloadRequest): MetadataSnapshot? {
        val root = parseRoot(jsonText) ?: return null
        val title = root.optString("title").trim().take(MAX_TITLE_LENGTH)
            .ifBlank { root.optString("fulltitle").trim().take(MAX_TITLE_LENGTH) }
        val subtitles = subtitleLanguages(root, "subtitles")
        val automaticCaptions = subtitleLanguages(root, "automatic_captions")
        val entries = root.optJSONArray("entries")
        if (root.optString("_type") == "playlist" || entries != null) {
            return MetadataSnapshot(
                title = title,
                subtitleLanguages = subtitles,
                automaticCaptionLanguages = automaticCaptions,
                isPlaylist = true,
            )
        }
        val duration = root.optDouble("duration", Double.NaN)
            .takeIf { it.isFinite() && it > 0 }
        return MetadataSnapshot(
            title = title,
            durationSeconds = duration,
            videoOptions = videoSizeOptions(root, request, duration),
            subtitleLanguages = subtitles,
            automaticCaptionLanguages = automaticCaptions,
        )
    }

    /**
     * Bounded description of a metadata response for local diagnostics. It records the shape
     * of the payload (format count, heights, selected ids, subtitle languages) instead of the
     * full document, which can be ~100 KB and is never persisted.
     */
    fun summarizeMetadataJson(jsonText: String, maxChars: Int = 700): String {
        val root = parseRoot(jsonText) ?: return "invalid-json (${jsonText.length} chars)"
        val parts = mutableListOf<String>()
        root.optString("title").trim().take(80).takeIf(String::isNotBlank)?.let { parts += "title=$it" }
        root.optDouble("duration", Double.NaN).takeIf { it.isFinite() && it > 0 }?.let {
            parts += "duration=" + String.format(Locale.US, "%.1f", it) + "s"
        }
        val entries = root.optJSONArray("entries")
        if (root.optString("_type") == "playlist" || entries != null) {
            parts += "playlistEntries=${entries?.length() ?: 0}"
        } else {
            val formats = allFormats(root)
            parts += "formats=${formats.size}"
            val heights = formats
                .mapNotNull { format -> format.optInt("height", 0).takeIf { height -> height > 0 } }
                .distinct()
                .sortedDescending()
            if (heights.isNotEmpty()) parts += "heights=${heights.joinToString(",")}"
            requestedFormats(root)?.let { requested ->
                val ids = (0 until requested.length()).mapNotNull { index ->
                    requested.optJSONObject(index)?.optString("format_id")?.takeIf(String::isNotBlank)
                }
                if (ids.isNotEmpty()) parts += "requested=${ids.joinToString(",")}"
            }
            parts += "video=${formats.count { it.isVideoOnly() }} " +
                "audio=${formats.count { it.isAudioOnly() }} " +
                "muxed=${formats.count { it.isProgressive() }}"
        }
        subtitleLanguages(root, "subtitles").takeIf(List<String>::isNotEmpty)
            ?.let { parts += "subtitles=${it.joinToString(",")}" }
        subtitleLanguages(root, "automatic_captions").takeIf(List<String>::isNotEmpty)
            ?.let { parts += "autoCaptions=${it.joinToString(",")}" }
        return parts.joinToString("; ").take(maxChars)
    }

    private fun subtitleLanguages(json: JSONObject, key: String): List<String> =
        json.optJSONObject(key)?.let { table ->
            val names = mutableListOf<String>()
            val iterator = table.keys()
            while (iterator.hasNext()) {
                val code = iterator.next()
                // Live chat is not a downloadable subtitle track.
                if (code.isNotBlank() && code != "live_chat") names += code
            }
            names.distinct().sorted()
        } ?: emptyList()

    /**
     * The size of one selectable height is its best video stream plus the best audio stream,
     * or the progressive stream alone. Merged pairs are summed once; no stream is counted
     * twice and missing components simply leave that height without a size.
     */
    private fun videoSizeOptions(
        json: JSONObject,
        request: DownloadRequest,
        duration: Double?,
    ): List<FormatSizeOption> {
        val formats = allFormats(json)
        val formatsById = indexFormats(json)
        val videoOnly = formats.filter { it.isVideoOnly() }
        val progressive = formats.filter { it.isProgressive() }
        val heights = (videoOnly + progressive)
            .mapNotNull { format -> format.optInt("height", 0).takeIf { it > 0 } }
            .distinct()
            .sortedDescending()
        if (heights.isEmpty()) return emptyList()
        val audio = bestAudioStream(formats.filter { it.isAudioOnly() })
        val effectiveDuration = effectiveDuration(request, duration)
        return heights.mapNotNull { height ->
            // A dedicated video stream is preferred; a progressive stream is the fallback
            // for heights that only exist as a single muxed format.
            val dedicated = videoOnly.filter { it.optInt("height", 0) == height }
            val atHeight = dedicated.ifEmpty {
                progressive.filter { it.optInt("height", 0) == height }
            }
            val sixtyFps = if (request.prefer60Fps) {
                atHeight.filter { it.optDouble("fps", 0.0) >= 60.0 }
            } else {
                emptyList()
            }
            val video = (sixtyFps.ifEmpty { atHeight }).maxWithOrNull(
                compareBy<JSONObject>({ it.optDouble("fps", 0.0) })
                    .thenBy { it.optDouble("tbr", 0.0) },
            ) ?: return@mapNotNull null
            val videoSize = resolveSize(video, formatsById, duration) ?: return@mapNotNull null
            val audioSize = if (video.isProgressive()) {
                null
            } else {
                audio?.let { resolveSize(it, formatsById, duration) } ?: return@mapNotNull null
            }
            var total = videoSize.bytes + (audioSize?.bytes ?: 0L)
            if (isTrimmed(request)) {
                total = scaleForTrim(total, duration, effectiveDuration) ?: return@mapNotNull null
            }
            FormatSizeOption(
                height = height,
                fps = video.optDouble("fps", Double.NaN).takeIf { it.isFinite() && it > 0 },
                bytes = total.takeIf { it > 0 },
            )
        }
    }

    /**
     * yt-dlp normally emits a single JSON object, but shells, wrappers, or update notices may
     * add leading/trailing lines. Accept the outermost object so a notice never turns a valid
     * payload into "Size unavailable".
     */
    internal fun parseRoot(jsonText: String): JSONObject? {
        val text = jsonText.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return null
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        if (candidate.length <= 1) return null
        return runCatching { JSONObject(candidate) }.getOrNull()
    }

    private fun playlist(
        entries: JSONArray?,
        request: DownloadRequest,
        title: String,
        recalculateSelection: Boolean,
    ): SizeEstimate {
        if (entries == null || entries.length() == 0) {
            return SizeEstimate.unavailable("playlist-empty").copy(title = title)
        }
        var total = 0L
        var exact = true
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index)
                ?: return SizeEstimate.unavailable("playlist-entry").copy(title = title)
            val item = single(entry, request, "", recalculateSelection)
            val bytes = item.bytes
            if (!item.isAvailable || bytes == null) {
                return SizeEstimate.unavailable(
                    detail = "playlist-entry",
                    diagnostic = "entry $index: ${item.detail.ifBlank { "size-missing" }}",
                ).copy(title = title)
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

    private fun single(
        json: JSONObject,
        request: DownloadRequest,
        title: String,
        recalculateSelection: Boolean,
    ): SizeEstimate {
        val duration = json.optDouble("duration", Double.NaN)
            .takeIf { it.isFinite() && it > 0 }
        val effectiveDuration = effectiveDuration(request, duration)
        return if (request.mode == MediaMode.AUDIO) {
            audio(json, request, duration, effectiveDuration, title, recalculateSelection)
        } else {
            video(json, request, duration, effectiveDuration, title, recalculateSelection)
        }
    }

    private fun video(
        json: JSONObject,
        request: DownloadRequest,
        rawDuration: Double?,
        effectiveDuration: Double?,
        title: String,
        recalculateSelection: Boolean,
    ): SizeEstimate {
        if (recalculateSelection) {
            return locallySelectedVideo(json, request, rawDuration, effectiveDuration, title)
        }
        val formats = indexFormats(json)
        val requested = requestedFormats(json)
        var total = 0L
        var exact = true
        var merged = false
        val missing = mutableListOf<String>()
        if (requested != null && requested.length() > 0) {
            merged = requested.length() > 1
            for (index in 0 until requested.length()) {
                val format = requested.optJSONObject(index)
                    ?: return SizeEstimate.unavailable("format-missing").copy(title = title)
                val resolved = resolveSize(format, formats, rawDuration)
                if (resolved == null) {
                    missing += format.optString("format_id").ifBlank { "stream-$index" }
                    continue
                }
                total += resolved.bytes
                if (!resolved.exact) exact = false
            }
        } else {
            val resolved = resolveSize(json, formats, rawDuration)
                ?: return unavailableMissing(title, listOf("selected"))
            total = resolved.bytes
            exact = resolved.exact
        }
        if (missing.isNotEmpty() || total <= 0) {
            return unavailableMissing(title, missing.ifEmpty { listOf("selected") })
        }

        // A merged container is never byte-identical to the sum of its source streams, so the
        // total is an estimate even when every component reports an exact filesize.
        val isExact = exact && !merged
        val detail = when {
            isTrimmed(request) -> "trim"
            merged -> "merged"
            exact -> ""
            else -> "approx"
        }
        if (isTrimmed(request)) {
            val scaled = scaleForTrim(total, rawDuration, effectiveDuration)
                ?: return SizeEstimate.unavailable("trim-unknown").copy(title = title)
            return SizeEstimate(SizeEstimateKind.APPROXIMATE, scaled, title, "trim")
        }
        return SizeEstimate(
            kind = if (isExact) SizeEstimateKind.EXACT else SizeEstimateKind.APPROXIMATE,
            bytes = total,
            title = title,
            detail = detail,
        )
    }

    /**
     * Re-selects video streams from the cached format table using the same tier order as
     * [DownloadRequestFactory.buildVideoFormat]. Always reported as approximate because the
     * local ranking mirrors the selector without being the selector itself.
     */
    private fun locallySelectedVideo(
        json: JSONObject,
        request: DownloadRequest,
        rawDuration: Double?,
        effectiveDuration: Double?,
        title: String,
    ): SizeEstimate {
        val streams = selectLocalVideo(json, request)
            ?: return SizeEstimate.unavailable(
                detail = "selection-unavailable",
                diagnostic = "cached metadata has no usable format table for local selection",
            ).copy(title = title)
        val formats = indexFormats(json)
        var total = 0L
        val missing = mutableListOf<String>()
        streams.forEachIndexed { index, stream ->
            val resolved = resolveSize(stream, formats, rawDuration)
            if (resolved == null) {
                missing += stream.optString("format_id").ifBlank { "stream-$index" }
            } else {
                total += resolved.bytes
            }
        }
        if (missing.isNotEmpty() || total <= 0) return unavailableMissing(title, missing)

        if (isTrimmed(request)) {
            val scaled = scaleForTrim(total, rawDuration, effectiveDuration)
                ?: return SizeEstimate.unavailable("trim-unknown").copy(title = title)
            return SizeEstimate(SizeEstimateKind.APPROXIMATE, scaled, title, "trim")
        }
        return SizeEstimate(
            kind = SizeEstimateKind.APPROXIMATE,
            bytes = total,
            title = title,
            detail = if (streams.size > 1) "local-merged" else "local",
        )
    }

    /**
     * Tier order: 60fps video + audio, any video + audio, progressive at the requested
     * height, then any progressive stream. Null means the document cannot be used locally.
     */
    private fun selectLocalVideo(json: JSONObject, request: DownloadRequest): List<JSONObject>? {
        val formats = allFormats(json)
        // Direct-media pages expose a single size on the root document instead of a table.
        if (formats.isEmpty()) return listOf(json)
        if (formats.any { !it.has("vcodec") || !it.has("acodec") }) return listOf(json)
        val heightLimit = request.quality.height?.takeIf { it > 0 }
        val videoOnly = formats.filter { it.isVideoOnly() }
        val audioOnly = formats.filter { it.isAudioOnly() }
        val progressive = formats.filter { it.isProgressive() }
        val bestAudio = bestAudioStream(audioOnly)

        if (bestAudio != null) {
            if (request.prefer60Fps) {
                bestVideoStream(videoOnly, heightLimit, minFps = 60.0)?.let {
                    return listOf(it, bestAudio)
                }
            }
            bestVideoStream(videoOnly, heightLimit, minFps = null)?.let {
                return listOf(it, bestAudio)
            }
        }
        bestProgressiveStream(progressive, heightLimit)?.let { return listOf(it) }
        bestProgressiveStream(progressive, null)?.let { return listOf(it) }
        val anyVideo = bestProgressiveStream(formats.filter { it.vcodec() != "none" }, null)
        return anyVideo?.let { listOf(it) }
    }

    private fun selectLocalAudio(json: JSONObject, request: DownloadRequest): JSONObject? {
        val formats = allFormats(json)
        if (formats.isEmpty()) return null
        if (formats.any { !it.has("acodec") || !it.has("vcodec") }) return null
        val audioOnly = formats.filter { it.isAudioOnly() }
        val preferred = if (request.audioFormat == AudioFormat.OPUS) {
            audioOnly.filter { it.optString("acodec").startsWith("opus", ignoreCase = true) }
        } else {
            emptyList()
        }
        return bestAudioStream(preferred.ifEmpty { audioOnly })
            ?: bestProgressiveStream(formats.filter { it.isProgressive() }, null)
    }

    private fun bestVideoStream(
        candidates: List<JSONObject>,
        heightLimit: Int?,
        minFps: Double?,
    ): JSONObject? = candidates
        .filter { format ->
            val height = format.optInt("height", 0)
            val heightMatches = heightLimit == null || (height > 0 && height <= heightLimit)
            val fpsMatches = minFps == null || format.optDouble("fps", 0.0) >= minFps
            heightMatches && fpsMatches
        }
        .maxWithOrNull(
            compareBy<JSONObject>({ it.optInt("height", 0) })
                .thenBy { it.optDouble("fps", 0.0) }
                .thenBy { it.optDouble("tbr", 0.0) },
        )

    private fun bestProgressiveStream(
        candidates: List<JSONObject>,
        heightLimit: Int?,
    ): JSONObject? = candidates
        .filter { format ->
            val height = format.optInt("height", 0)
            heightLimit == null || (height > 0 && height <= heightLimit)
        }
        .maxWithOrNull(
            compareBy<JSONObject>({ it.optInt("height", 0) })
                .thenBy { it.optDouble("fps", 0.0) }
                .thenBy { it.optDouble("tbr", 0.0) },
        )

    private fun bestAudioStream(candidates: List<JSONObject>): JSONObject? =
        candidates.maxWithOrNull(
            compareBy<JSONObject>({ it.optDouble("abr", 0.0) })
                .thenBy { it.optDouble("tbr", 0.0) }
                .thenBy { it.optDouble("asr", 0.0) },
        )

    private fun allFormats(json: JSONObject): List<JSONObject> {
        val formats = json.optJSONArray("formats") ?: return emptyList()
        val result = ArrayList<JSONObject>(formats.length())
        for (index in 0 until formats.length()) {
            formats.optJSONObject(index)?.let { result.add(it) }
        }
        return result
    }

    private fun JSONObject.vcodec(): String = optString("vcodec", "none")

    private fun JSONObject.acodec(): String = optString("acodec", "none")

    private fun JSONObject.isVideoOnly(): Boolean = vcodec() != "none" && acodec() == "none"

    private fun JSONObject.isAudioOnly(): Boolean = acodec() != "none" && vcodec() == "none"

    private fun JSONObject.isProgressive(): Boolean = vcodec() != "none" && acodec() != "none"

    private fun unavailableMissing(title: String, missing: List<String>): SizeEstimate =
        SizeEstimate.unavailable(
            detail = "size-missing",
            diagnostic = "no filesize/filesize_approx/bitrate for ${missing.joinToString(",")}",
        ).copy(title = title)

    private fun audio(
        json: JSONObject,
        request: DownloadRequest,
        rawDuration: Double?,
        effectiveDuration: Double?,
        title: String,
        recalculateSelection: Boolean,
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
        val formats = indexFormats(json)
        val requested = requestedFormats(json)
        val local = if (recalculateSelection) selectLocalAudio(json, request) else null
        val source = when {
            local != null -> resolveSize(local, formats, rawDuration)
            requested != null && requested.length() > 0 ->
                requested.optJSONObject(0)?.let { resolveSize(it, formats, rawDuration) }
            else -> resolveSize(json, formats, rawDuration)
        } ?: return unavailableMissing(title, listOf("audio"))

        var bytes = source.bytes
        if (trimmed) {
            bytes = scaleForTrim(bytes, rawDuration, effectiveDuration)
                ?: return SizeEstimate.unavailable("trim-unknown").copy(title = title)
        }
        if (bytes <= 0) return unavailableMissing(title, listOf("audio"))
        return SizeEstimate(SizeEstimateKind.APPROXIMATE, bytes, title, "source")
    }

    private data class ResolvedSize(val bytes: Long, val exact: Boolean)

    private fun resolveSize(
        value: JSONObject,
        formats: Map<String, JSONObject>,
        duration: Double?,
    ): ResolvedSize? {
        sizeOf(value)?.let { return it }
        val formatId = value.optString("format_id").takeIf { it.isNotBlank() }
        if (formatId != null) {
            val match = formats[formatId]
            if (match != null && match !== value) sizeOf(match)?.let { return it }
        }
        val tbr = value.optDouble("tbr", Double.NaN).takeIf { it.isFinite() && it > 0 }
            ?: formatId?.let { formats[it] }
                ?.optDouble("tbr", Double.NaN)
                ?.takeIf { it.isFinite() && it > 0 }
        val streamDuration = value.optDouble("duration", Double.NaN)
            .takeIf { it.isFinite() && it > 0 } ?: duration
        if (tbr != null && streamDuration != null && streamDuration > 0) {
            val bytes = (tbr * 125.0 * streamDuration).toLong()
            if (bytes > 0) return ResolvedSize(bytes, exact = false)
        }
        return null
    }

    private fun indexFormats(json: JSONObject): Map<String, JSONObject> {
        val formats = json.optJSONArray("formats") ?: return emptyMap()
        val result = HashMap<String, JSONObject>(formats.length())
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val id = format.optString("format_id").takeIf { it.isNotBlank() } ?: continue
            result.putIfAbsent(id, format)
        }
        return result
    }

    /**
     * `--dump-single-json` can describe the selected streams in either `requested_formats` or,
     * for some yt-dlp builds, only inside `requested_downloads`. Accept both shapes so a valid
     * merged selection is never reported as unavailable.
     */
    private fun requestedFormats(json: JSONObject): JSONArray? {
        json.optJSONArray("requested_formats")
            ?.takeIf { it.length() > 0 }
            ?.let { return it }
        val downloads = json.optJSONArray("requested_downloads")
            ?.takeIf { it.length() > 0 }
            ?: return null
        val nested = downloads.optJSONObject(0)
            ?.optJSONArray("requested_formats")
            ?.takeIf { it.length() > 0 }
        return nested ?: downloads
    }

    private fun sizeOf(value: JSONObject): ResolvedSize? {
        val exact = value.positiveLong("filesize")
        if (exact != null) return ResolvedSize(exact, true)
        val approximate = value.positiveLong("filesize_approx")
        if (approximate != null) return ResolvedSize(approximate, false)
        return null
    }

    private fun JSONObject.positiveLong(key: String): Long? {
        val raw = opt(key)
        val value = when (raw) {
            null, JSONObject.NULL -> return null
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: return null
            else -> return null
        }
        return value.takeIf { it > 0 }
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
