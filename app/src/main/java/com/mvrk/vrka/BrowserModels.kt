package com.mvrk.vrka

import androidx.compose.runtime.Immutable

@Immutable
data class MediaStreamCandidate(
    val id: String,
    val url: String,
    val pageUrl: String = "",
    val title: String = "",
    val kind: String = "Video", // HLS, DASH, Video, Audio
    val resolution: String = "", // e.g. "1920x1080", "1080p"
    val mimeType: String = "",
    val headers: Map<String, String> = emptyMap(),
    val source: String = "network", // network, dom, playlist
    val timestamp: Long = System.currentTimeMillis(),
) {
    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            resolution.isNotBlank() -> "$kind ($resolution)"
            else -> "$kind Stream"
        }

    val displaySubtitle: String
        get() = when {
            url.length > 55 -> url.take(28) + "..." + url.takeLast(24)
            else -> url
        }
}

@Immutable
data class BrowserNavState(
    val currentUrl: String = "https://duckduckgo.com",
    val title: String = "VRKA Browser",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecure: Boolean = true,
    val error: String? = null,
)
