package com.mvrk.vrka

/**
 * Authoritative per-stream metadata emitted by yt-dlp immediately before a transfer.
 * `none`/`NA` are yt-dlp's missing-value markers, not codec names.
 */
internal data class StreamMetadata(
    val formatId: String = "",
    val extension: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val height: Int? = null,
) {
    val hasVideo: Boolean
        get() = normalizedCodec(videoCodec).let { it.isNotEmpty() && it != "none" }

    val hasAudio: Boolean
        get() = normalizedCodec(audioCodec).let { it.isNotEmpty() && it != "none" }

    companion object {
        fun normalizedCodec(value: String): String =
            value.trim().lowercase().takeIf { it.isNotBlank() && it != "na" && it != "null" }.orEmpty()
    }
}

/** Parses the `__VRKA_STREAM__` before_dl line. */
internal object StreamMarker {
    const val MARKER = "__VRKA_STREAM__"
    const val TEMPLATE =
        "$MARKER%(format_id)s|%(ext)s|%(vcodec)s|%(acodec)s|%(height)s"

    fun parse(line: String): StreamMetadata? {
        val index = line.indexOf(MARKER, ignoreCase = true)
        if (index < 0) return null
        val fields = line.substring(index + MARKER.length).trim().split('|')
        if (fields.size < 4) return null
        return StreamMetadata(
            formatId = fields[0].trim(),
            extension = fields[1].trim(),
            videoCodec = fields[2].trim(),
            audioCodec = fields[3].trim(),
            height = fields.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        )
    }
}

/**
 * Classifies the stream actually being transferred. Metadata is authoritative; a destination
 * extension is only a fallback. Requested application mode is deliberately not an input.
 */
internal object StreamClassification {
    private val audioExtensions = setOf("m4a", "mp3", "opus", "ogg", "oga", "wav", "aac", "flac")
    // WebM can be video, muxed or audio-only; without codec metadata it is ambiguous.
    private val videoExtensions = setOf("mp4", "mkv", "mov", "m4v", "ts", "flv", "avi", "mpg", "mpeg")

    fun classify(metadata: StreamMetadata?, destination: String?): ActiveStream {
        if (metadata != null) {
            val hasVideo = metadata.hasVideo
            val hasAudio = metadata.hasAudio
            if (hasVideo && hasAudio) {
                // A muxed/merged selection may be a single video file or the first of two
                // destinations. A video container is safely presented as video.
                return extensionKind(destination)
                    ?.takeIf { it == ActiveStream.AUDIO }
                    ?.let { it }
                    ?: extensionKind(metadata.extension)
                        ?.takeIf { it != ActiveStream.AUDIO }
                    ?: ActiveStream.MEDIA
            }
            if (hasVideo) return ActiveStream.VIDEO
            if (hasAudio) return ActiveStream.AUDIO
            if (metadata.formatId.contains('+')) {
                return extensionKind(destination) ?: ActiveStream.MEDIA
            }
            extensionKind(metadata.extension)?.let { return it }
        }
        extensionKind(destination)?.let { return it }
        return ActiveStream.MEDIA
    }

    fun extensionKind(pathOrExtension: String?): ActiveStream? {
        if (pathOrExtension.isNullOrBlank()) return null
        val name = pathOrExtension.trim().substringAfterLast('/').substringAfterLast('\\')
            .substringBefore('?').substringBefore('#')
        val extension = if ('.' in name) name.substringAfterLast('.').lowercase() else name.lowercase()
        return when (extension) {
            in audioExtensions -> ActiveStream.AUDIO
            in videoExtensions -> ActiveStream.VIDEO
            else -> null
        }
    }
}
