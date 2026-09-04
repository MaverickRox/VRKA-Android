/**
 * URL analysis utilities for media candidate classification.
 *
 * Ported faithfully from Desktop VRKA Build 017 vrka_core/candidates.py.
 * Functions: mediaKind(), isSegment(), isMasterManifest(), canonicalMediaIdentity().
 * Pure functions with no Android framework dependency.
 */
package com.mvrk.vrka.engine

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

// --- Volatile query parameter names stripped for canonical identity ---
// Ported from Desktop VOLATILE_QUERY_NAMES (candidates.py:202-206)
private val VOLATILE_QUERY_NAMES = setOf(
    "auth", "authorization", "expires", "exp", "hdnts", "hmac", "jwt",
    "key", "policy", "signature", "sig", "token", "x-amz-credential",
    "x-amz-date", "x-amz-expires", "x-amz-security-token", "x-amz-signature",
)

// --- Segment suffixes ---
// Ported from Desktop SEGMENT_SUFFIXES (candidates.py:207)
private val SEGMENT_SUFFIXES = listOf(".ts", ".m4s", ".m4a", ".cmfv", ".cmfa", ".aac")

// --- Segment detection regexes ---
// Ported from Desktop candidates.py:208-228
private val SEGMENT_PATH_RE = Regex(
    """(?:^|[/_.-])(?:seg(?:ment)?|chunk|frag(?:ment)?|part)[-_]?\d+""",
    RegexOption.IGNORE_CASE,
)
private val SEGMENT_CODEC_RE = Regex(
    """(?:^|[_.-])(?:h264|h265|hevc|avc1?|aac|mp4a|mpeg4|vp9|opus|seg(?:ment)?|chunk|frag(?:ment)?|part|piece|slice)[_.-]?\d{1,6}(?:[_.-]|$)""",
    RegexOption.IGNORE_CASE,
)
private val SEGMENT_INIT_RE = Regex(
    """(?:^|[_.-])(?:h264|h265|hevc|avc1?|aac|mp4a|mpeg4|vp9|opus)[_.-]init[_.-]""",
    RegexOption.IGNORE_CASE,
)
private val SEGMENT_SEQUENCE_RE = Regex(
    """[_.-]\d{1,8}_[A-Za-z0-9]+_[A-Za-z0-9]""",
    RegexOption.IGNORE_CASE,
)

// --- Generic master playlist stems ---
// Ported from Desktop _GENERIC_MASTER_STEMS (candidates.py:269)
private val GENERIC_MASTER_STEMS = setOf("master", "playlist", "manifest", "index")

// --- Numeric stream id and widget suffix regexes for live/ad widget filtering ---
// Ported from Desktop _NUMERIC_STREAM_ID_RE and _WIDGET_RENDITION_SUFFIX_RE (browser_fallback.py:30-31)
private val NUMERIC_STREAM_ID_RE = Regex("""\b\d{5,}\b""")
private val WIDGET_RENDITION_SUFFIX_RE = Regex("""_(\d{3,4})p(?:\.m3u8)?$""", RegexOption.IGNORE_CASE)

/**
 * True when a candidate URL has the generic sidebar live-widget signature.
 *
 * Ported from Desktop looks_like_live_widget_url() (browser_fallback.py:34-48).
 * Sidebar/live-cam/ad HLS streams are addressed by a numeric stream id in the URL path
 * and a rendition suffix such as `_240p.m3u8` (the requested episode's master/manifest
 * on the same pages has neither).
 */
fun looksLikeLiveWidgetUrl(url: String): Boolean {
    val path = try {
        URI(url).path ?: ""
    } catch (_: Exception) { return false }
    if (!NUMERIC_STREAM_ID_RE.containsMatchIn(path)) return false
    val leaf = path.substringAfterLast("/")
    return WIDGET_RENDITION_SUFFIX_RE.containsMatchIn(leaf)
}

/**
 * Classify a URL and optional content-type into a [CandidateKind].
 *
 * Ported from Desktop media_kind() (candidates.py:239-252).
 */
fun mediaKind(url: String, contentType: String = ""): CandidateKind {
    val path = try {
        URI(url).path?.lowercase() ?: ""
    } catch (_: Exception) { "" }
    val mime = contentType.lowercase().split(";", limit = 2).first().trim()

    if (path.endsWith(".m3u8") || mime in setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl"
        )
    ) return CandidateKind.HLS

    if (path.endsWith(".mpd") || mime == "application/dash+xml") {
        return CandidateKind.DASH
    }

    if (mime.startsWith("video/") || mime.startsWith("audio/") ||
        path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mov") ||
        path.endsWith(".mkv") || path.endsWith(".mp3") || path.endsWith(".wav") ||
        path.endsWith(".flac") || path.endsWith(".m4a")
    ) return CandidateKind.DIRECT

    return CandidateKind.OTHER
}

/**
 * True when a URL represents a segment (child of a manifest), not a standalone transfer candidate.
 *
 * Ported from Desktop is_segment() (candidates.py:255-267).
 * Uses 5 regex patterns plus suffix matching.
 */
fun isSegment(url: String, contentType: String = ""): Boolean {
    val path = try {
        URI(url).path?.lowercase() ?: ""
    } catch (_: Exception) { return false }
    val filename = path.substringAfterLast("/")
    val stem = if ("." in filename) filename.substringBeforeLast(".") else filename

    return SEGMENT_SUFFIXES.any { path.endsWith(it) } ||
            SEGMENT_PATH_RE.containsMatchIn(path) ||
            SEGMENT_CODEC_RE.containsMatchIn(path) ||
            SEGMENT_INIT_RE.containsMatchIn(path) ||
            SEGMENT_SEQUENCE_RE.containsMatchIn(stem)
}

/**
 * True when a manifest path is a generic master/rendition-selector name.
 *
 * Ported from Desktop is_master_manifest() (candidates.py:272-281).
 * Preferring the master lets the normal downloader choose the best available
 * quality instead of a fixed rendition.
 */
fun isMasterManifest(url: String): Boolean {
    val path = try {
        URI(url).path ?: ""
    } catch (_: Exception) { return false }
    val filename = path.substringAfterLast("/")
    val stem = if ("." in filename) filename.substringBeforeLast(".").lowercase() else filename.lowercase()
    return stem in GENERIC_MASTER_STEMS
}

/**
 * Build a stable logical identity without changing the transfer URL.
 *
 * Only well-known ephemeral authentication fields are removed. All other
 * query fields remain because they can distinguish genuinely different media.
 *
 * Ported from Desktop canonical_media_identity() (candidates.py:284-306).
 */
fun canonicalMediaIdentity(url: String, kind: CandidateKind? = null): String {
    val chosenKind = kind ?: mediaKind(url)
    return try {
        val parsed = URI(url)
        val host = normalizedHost(parsed.host)
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = (parsed.path ?: "/").replace(Regex("/{2,}"), "/")

        // Parse query and strip volatile params
        val stableQuery = parseQueryParams(parsed.rawQuery)
            .filter { (name, _) -> name.lowercase() !in VOLATILE_QUERY_NAMES }
            .sortedWith(compareBy({ it.first.lowercase() }, { it.second }))

        val queryString = stableQuery.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        val scheme = (parsed.scheme ?: "https").lowercase()
        val normalized = "$scheme://$host$port$path${if (queryString.isNotEmpty()) "?$queryString" else ""}"

        val input = "${chosenKind.value}|$normalized"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        hash.joinToString("") { "%02x".format(it) }.take(24)
    } catch (_: Exception) {
        // Fallback for malformed URLs
        val input = "${chosenKind.value}|$url"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        hash.joinToString("") { "%02x".format(it) }.take(24)
    }
}

/**
 * Normalize a hostname (lowercase, strip trailing dots, handle IP addresses).
 * Ported from Desktop _normalized_host() (candidates.py:231-236).
 */
internal fun normalizedHost(host: String?): String {
    val value = (host ?: "").trim().lowercase().trimEnd('.')
    return if (value.isEmpty()) "" else value
}

/**
 * Parse a raw query string into (name, value) pairs.
 */
private fun parseQueryParams(query: String?): List<Pair<String, String>> {
    if (query.isNullOrEmpty()) return emptyList()
    return query.split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.isEmpty() || parts[0].isEmpty()) null
        else {
            val name = try { URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
            val value = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            } else ""
            name to value
        }
    }
}
