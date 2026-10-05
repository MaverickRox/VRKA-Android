package com.mvrk.vrka.engine

import java.net.URI

/**
 * Supported media container formats for engine processing.
 */
enum class StreamContainer(val extension: String) {
    HLS("m3u8"),
    DASH("mpd"),
    MP4("mp4"),
    MKV("mkv"),
    WEBM("webm"),
    AUDIO_ONLY("m4a"),
    UNKNOWN("bin");

    companion object {
        fun fromUrlOrMime(url: String, mime: String?): StreamContainer {
            val lowerUrl = url.lowercase()
            val lowerMime = mime?.lowercase().orEmpty()
            val path = try { URI(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }

            return when {
                path.endsWith(".m3u8") || lowerUrl.contains(".m3u8") || lowerMime.contains("mpegurl") -> HLS
                path.endsWith(".mpd") || lowerUrl.contains(".mpd") || lowerMime.contains("dash+xml") -> DASH
                path.endsWith(".mp4") || lowerMime.contains("video/mp4") -> MP4
                path.endsWith(".mkv") || lowerMime.contains("video/x-matroska") -> MKV
                path.endsWith(".webm") || lowerMime.contains("video/webm") -> WEBM
                path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".opus") || lowerMime.startsWith("audio/") -> AUDIO_ONLY
                else -> UNKNOWN
            }
        }
    }
}

/**
 * DRM encryption schemes. If detected, extraction fails fast with explicit diagnostic error.
 */
enum class DrmScheme {
    NONE,
    WIDEVINE,
    PLAYREADY,
    CLEARKEY,
    FAIRPLAY,
    UNKNOWN_DRM;

    val isProtected: Boolean
        get() = this != NONE
}

/**
 * Video track metadata extracted from stream manifests or media headers.
 */
data class VideoTrackDescriptor(
    val id: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val codecs: String? = null,
    val url: String? = null,
)

/**
 * Audio track metadata extracted from stream manifests (including alternate audio streams).
 */
data class AudioTrackDescriptor(
    val id: String,
    val language: String? = null,
    val name: String? = null,
    val channels: Int? = null,
    val codecs: String? = null,
    val url: String? = null,
    val isDefault: Boolean = false,
)

/**
 * Subtitle track metadata extracted from stream manifests.
 */
data class SubtitleTrackDescriptor(
    val id: String,
    val language: String? = null,
    val name: String? = null,
    val url: String? = null,
    val format: String? = null,
)

/**
 * Engine-neutral presentation descriptor representing a media resource.
 */
data class StreamDescriptor(
    val url: String,
    val container: StreamContainer,
    val isMasterPlaylist: Boolean = false,
    val durationSeconds: Double? = null,
    val videoTracks: List<VideoTrackDescriptor> = emptyList(),
    val audioTracks: List<AudioTrackDescriptor> = emptyList(),
    val subtitleTracks: List<SubtitleTrackDescriptor> = emptyList(),
    val drmScheme: DrmScheme = DrmScheme.NONE,
    val isEncrypted: Boolean = false,
    val encryptionMethod: String? = null,
    val hasByteRangeSegments: Boolean = false,
    val hasSeparateAudioTracks: Boolean = false,
    val rawHeaders: Map<String, String> = emptyMap(),
) {
    /**
     * Best video resolution label (e.g. "1080p", "720p").
     */
    val resolutionLabel: String?
        get() {
            val maxHeight = videoTracks.mapNotNull { it.height }.maxOrNull()
            return maxHeight?.let { "${it}p" }
        }

    /**
     * True if stream requires multi-track demux and A/V remuxing.
     */
    val requiresMuxing: Boolean
        get() = hasSeparateAudioTracks || (videoTracks.isNotEmpty() && audioTracks.isNotEmpty() && audioTracks.any { it.url != null })

    companion object {
        /**
         * Inspects manifest text (HLS or DASH) to build a StreamDescriptor.
         */
        fun parseFromManifest(
            url: String,
            manifestContent: String,
            headers: Map<String, String> = emptyMap()
        ): StreamDescriptor {
            val container = StreamContainer.fromUrlOrMime(url, headers["content-type"])
            return when (container) {
                StreamContainer.HLS -> parseHlsManifest(url, manifestContent, headers)
                StreamContainer.DASH -> parseDashManifest(url, manifestContent, headers)
                else -> StreamDescriptor(
                    url = url,
                    container = container,
                    rawHeaders = headers
                )
            }
        }

        private fun parseHlsManifest(
            url: String,
            content: String,
            headers: Map<String, String>
        ): StreamDescriptor {
            val isMaster = content.contains("#EXT-X-STREAM-INF") || content.contains("#EXT-X-MEDIA")
            val hasByteRange = content.contains("#EXT-X-BYTERANGE")
            val hasSeparateAudio = content.contains("#EXT-X-MEDIA:TYPE=AUDIO")

            // DRM / Encryption inspection
            var drm = DrmScheme.NONE
            var isEncrypted = false
            var encMethod: String? = null

            if (content.contains("#EXT-X-KEY")) {
                isEncrypted = true
                val keyLines = content.lines().filter { it.startsWith("#EXT-X-KEY") }
                for (line in keyLines) {
                    when {
                        line.contains("KEYFORMAT=\"com.widevine", ignoreCase = true) ||
                        line.contains("widevine", ignoreCase = true) -> {
                            drm = DrmScheme.WIDEVINE
                            encMethod = "Widevine"
                            break
                        }
                        line.contains("KEYFORMAT=\"com.microsoft.playready", ignoreCase = true) ||
                        line.contains("playready", ignoreCase = true) -> {
                            drm = DrmScheme.PLAYREADY
                            encMethod = "PlayReady"
                            break
                        }
                        line.contains("METHOD=SAMPLE-AES", ignoreCase = true) -> {
                            encMethod = "SAMPLE-AES"
                            if (drm == DrmScheme.NONE) drm = DrmScheme.UNKNOWN_DRM
                        }
                        line.contains("METHOD=AES-128", ignoreCase = true) -> {
                            encMethod = "AES-128"
                            // Clear AES-128 is playable by FFmpeg / Media3 if key is accessible
                        }
                    }
                }
            }

            val videoTracks = mutableListOf<VideoTrackDescriptor>()
            val audioTracks = mutableListOf<AudioTrackDescriptor>()
            val subtitleTracks = mutableListOf<SubtitleTrackDescriptor>()

            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                    val codecMatch = Regex("""CODECS=\"([^\"]+)\"""").find(line)

                    val w = resMatch?.groupValues?.get(1)?.toIntOrNull()
                    val h = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull()
                    val codecs = codecMatch?.groupValues?.get(1)

                    val trackUrl = if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                        lines[i + 1].trim()
                    } else null

                    videoTracks.add(
                        VideoTrackDescriptor(
                            id = "v_${videoTracks.size}",
                            width = w,
                            height = h,
                            bitrate = bw,
                            codecs = codecs,
                            url = trackUrl
                        )
                    )
                } else if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    val nameMatch = Regex("""NAME=\"([^\"]+)\"""").find(line)
                    val langMatch = Regex("""LANGUAGE=\"([^\"]+)\"""").find(line)
                    val uriMatch = Regex("""URI=\"([^\"]+)\"""").find(line)
                    val isDefault = line.contains("DEFAULT=YES")

                    audioTracks.add(
                        AudioTrackDescriptor(
                            id = "a_${audioTracks.size}",
                            name = nameMatch?.groupValues?.get(1),
                            language = langMatch?.groupValues?.get(1),
                            url = uriMatch?.groupValues?.get(1),
                            isDefault = isDefault
                        )
                    )
                } else if (line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                    val nameMatch = Regex("""NAME=\"([^\"]+)\"""").find(line)
                    val langMatch = Regex("""LANGUAGE=\"([^\"]+)\"""").find(line)
                    val uriMatch = Regex("""URI=\"([^\"]+)\"""").find(line)

                    subtitleTracks.add(
                        SubtitleTrackDescriptor(
                            id = "s_${subtitleTracks.size}",
                            name = nameMatch?.groupValues?.get(1),
                            language = langMatch?.groupValues?.get(1),
                            url = uriMatch?.groupValues?.get(1)
                        )
                    )
                }
                i++
            }

            return StreamDescriptor(
                url = url,
                container = StreamContainer.HLS,
                isMasterPlaylist = isMaster,
                videoTracks = videoTracks,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                drmScheme = drm,
                isEncrypted = isEncrypted,
                encryptionMethod = encMethod,
                hasByteRangeSegments = hasByteRange,
                hasSeparateAudioTracks = hasSeparateAudio,
                rawHeaders = headers
            )
        }

        private fun parseDashManifest(
            url: String,
            content: String,
            headers: Map<String, String>
        ): StreamDescriptor {
            var drm = DrmScheme.NONE
            if (content.contains("ContentProtection", ignoreCase = true)) {
                when {
                    content.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", ignoreCase = true) ||
                    content.contains("widevine", ignoreCase = true) -> drm = DrmScheme.WIDEVINE
                    content.contains("9a04f079-9840-4286-ab92-e65be0885f95", ignoreCase = true) ||
                    content.contains("playready", ignoreCase = true) -> drm = DrmScheme.PLAYREADY
                    content.contains("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b", ignoreCase = true) ||
                    content.contains("clearkey", ignoreCase = true) -> drm = DrmScheme.CLEARKEY
                    else -> drm = DrmScheme.UNKNOWN_DRM
                }
            }

            return StreamDescriptor(
                url = url,
                container = StreamContainer.DASH,
                isMasterPlaylist = true,
                drmScheme = drm,
                isEncrypted = drm.isProtected,
                rawHeaders = headers
            )
        }
    }
}
