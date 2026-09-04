package com.mvrk.vrka.engine

import java.net.URI

/**
 * HLS and media assembly helpers.
 * Ported faithfully from Desktop VRKA Build 017 vrka_core/media_assembly.py.
 */
object MediaAssembly {

    private val EXT_X_MAP_RE = Regex("""#EXT-X-MAP:URI="([^"]+)"""")
    private val RESOLUTION_RE = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Resolves a relative segment or variant URL against its parent playlist URL.
     * Ported from Desktop _resolve_segment_url (media_assembly.py:50-58).
     */
    fun resolveSegmentUrl(seg: String, playlistUrl: String): String {
        val trimmed = seg.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val baseUri = URI(playlistUrl)
            baseUri.resolve(trimmed).toString()
        } catch (_: Throwable) {
            val base = playlistUrl.split("?")[0].substringBeforeLast('/', playlistUrl)
            "$base/$trimmed"
        }
    }

    /**
     * Determines whether a given URL or MIME type indicates an HLS playlist.
     * Ported from Desktop classify (media_assembly.py:30-48).
     */
    fun isHlsPlaylist(url: String, contentType: String = ""): Boolean {
        val loweredType = contentType.lowercase()
        val path = url.split("?")[0].lowercase()
        return "mpegurl" in loweredType || path.endsWith(".m3u8")
    }

    /**
     * Determines whether a playlist is a master playlist containing variants.
     */
    fun isMasterPlaylist(playlistContent: String): Boolean {
        return playlistContent.contains("#EXT-X-STREAM-INF")
    }

    /**
     * Extracts variant stream URLs and optional resolution heights from an HLS master playlist.
     */
    fun parseMasterPlaylist(masterContent: String, masterUrl: String): List<Pair<Int?, String>> {
        val variants = mutableListOf<Pair<Int?, String>>()
        val lines = masterContent.lines().map(String::trim)

        var lastHeight: Int? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val match = RESOLUTION_RE.find(line)
                lastHeight = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val resolvedUrl = resolveSegmentUrl(line, masterUrl)
                variants.add(lastHeight to resolvedUrl)
                lastHeight = null
            }
        }
        return variants
    }

    /**
     * Selects the preferred variant playlist URL based on the user's requested quality cap.
     */
    fun selectVariant(variants: List<Pair<Int?, String>>, maxQualityHeight: Int?): String? {
        if (variants.isEmpty()) return null
        if (maxQualityHeight == null) {
            // Pick highest available
            return variants.maxByOrNull { it.first ?: 0 }?.second ?: variants.first().second
        }
        // Filter at or below quality cap
        val withinCap = variants.filter { (height, _) -> height == null || height <= maxQualityHeight }
        if (withinCap.isNotEmpty()) {
            return withinCap.maxByOrNull { it.first ?: 0 }?.second
        }
        // If all exceed cap, pick the lowest available
        return variants.minByOrNull { it.first ?: Int.MAX_VALUE }?.second
    }

    /**
     * Parses an HLS variant playlist to extract ordered segment URLs.
     * Includes any #EXT-X-MAP initialization segment heading the list (for fMP4).
     * Ported from Desktop _assemble_from_playlist (media_assembly.py:109-147).
     */
    fun parseVariantSegments(variantContent: String, variantUrl: String): List<String> {
        val segments = mutableListOf<String>()

        // fMP4 playlists declare the init fragment via EXT-X-MAP; it must head the sequence
        EXT_X_MAP_RE.findAll(variantContent).forEach { match ->
            val initUri = match.groupValues[1]
            segments.add(resolveSegmentUrl(initUri, variantUrl))
        }

        // Add media segments in sequential order
        variantContent.lines()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { segLine ->
                segments.add(resolveSegmentUrl(segLine, variantUrl))
            }

        return segments
    }

    /**
     * Determines whether a given URL or content-type indicates a DASH MPD manifest.
     */
    fun isDashManifest(url: String, contentType: String = ""): Boolean {
        val loweredType = contentType.lowercase()
        val path = url.split("?")[0].lowercase()
        return "dash+xml" in loweredType || path.endsWith(".mpd")
    }

    /**
     * Parses a DASH MPD XML manifest to extract initialization and segment URLs.
     * Simple, deterministic regex-based extraction of BaseURL, SegmentTemplate, and SegmentURL.
     */
    fun parseDashSegments(mpdContent: String, mpdUrl: String): List<String> {
        val segments = mutableListOf<String>()
        val baseUrlMatch = Regex("""<BaseURL>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE).find(mpdContent)
        val baseUrl = if (baseUrlMatch != null) {
            resolveSegmentUrl(baseUrlMatch.groupValues[1].trim(), mpdUrl)
        } else {
            mpdUrl
        }

        // 1. Initialization segment from SegmentTemplate or Initialization
        val initMatch = Regex("""initialization="([^"]+)"""", RegexOption.IGNORE_CASE).find(mpdContent)
            ?: Regex("""<Initialization[^>]+sourceURL="([^"]+)"""", RegexOption.IGNORE_CASE).find(mpdContent)
        if (initMatch != null) {
            segments.add(resolveSegmentUrl(initMatch.groupValues[1], baseUrl))
        }

        // 2. SegmentTemplate with media pattern or explicit SegmentURL entries
        val segmentUrls = Regex("""<SegmentURL[^>]+media="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(mpdContent)
        for (match in segmentUrls) {
            segments.add(resolveSegmentUrl(match.groupValues[1], baseUrl))
        }

        return segments
    }
}
