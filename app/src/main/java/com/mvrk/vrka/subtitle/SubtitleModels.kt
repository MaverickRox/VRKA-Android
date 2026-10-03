package com.mvrk.vrka.subtitle

import androidx.compose.runtime.Immutable

/**
 * Structured lifecycle events for subtitle extraction and embedding.
 */
enum class SubtitleEventType {
    SUBTITLE_DISCOVERED,
    SUBTITLE_DOWNLOADING,
    SUBTITLE_DOWNLOADED,
    SUBTITLE_CONVERTING,
    SUBTITLE_EMBED_STARTED,
    SUBTITLE_EMBED_VERIFIED,
    SUBTITLE_SAVED_SEPARATELY,
    SUBTITLE_FAILED,
}

/**
 * High-level status of subtitles for a download job.
 */
enum class SubtitleStatus {
    NONE,
    DOWNLOADING,
    EMBEDDED_VERIFIED,
    SAVED_SEPARATELY,
    UNAVAILABLE,
    SUCCESS, // Legacy alias for backward compatibility
    FAILED,
}

/**
 * Structured event payload for subtitle operations.
 */
@Immutable
data class SubtitleEvent(
    val type: SubtitleEventType,
    val url: String,
    val language: String = "",
    val format: String = "",
    val source: String = "",
    val filename: String = "",
    val errorCategory: String? = null,
    val httpStatus: Int? = null,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Data shape reserved for a browser-discovered subtitle. The active Browser Fallback
 * detector does not currently create or download these candidates.
 */
@Immutable
data class BrowserSubtitleCandidate(
    val url: String,
    val pageUrl: String = "",
    val language: String = "",
    val label: String = "",
    val format: String = "vtt",
    val headers: Map<String, String> = emptyMap(),
    val source: String = "track", // "track", "network", "textTrack"
)
