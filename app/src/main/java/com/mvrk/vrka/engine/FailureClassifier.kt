/**
 * Download failure classification and browser-fallback eligibility.
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.engine

/**
 * Failure categories for download error taxonomy.
 */
enum class FailureCategory(val value: String) {
    /** Media resolution or extraction succeeded */
    SUCCESS("success"),

    /** Extractor/parser failed to understand or extract page media; browser fallback eligible */
    BROWSER_RECOVERABLE("browser_recoverable"),

    /** Genuine login / sign-in / authentication required */
    AUTH_REQUIRED("auth_required"),

    /** Network unavailable / DNS resolution failure / connection timeout */
    NETWORK_ERROR("network_error"),

    /** TLS / SSL certificate / security handshake failure */
    TLS_ERROR("tls_error"),

    /** User cancelled the download operation */
    CANCELLED("cancelled"),

    /** Storage full / disk write / filesystem error */
    STORAGE_ERROR("storage_error"),

    /** Extraction succeeded but downstream transfer / FFmpeg processing failed */
    POST_EXTRACTION_ERROR("post_extraction_error"),

    /** Internal application exception / unexpected runtime error */
    INTERNAL_ERROR("internal_error"),

    /** Cloudflare / bot challenge / turnstile / captcha */
    CLOUDFLARE("cloudflare"),

    /** Cookie database error / session cookies needed */
    COOKIES("cookies"),

    /** URL or signature has expired */
    EXPIRED("expired"),

    /** HTTP error (403, 429, connection reset, unable to download) */
    HTTP("http"),

    /** yt-dlp does not recognize the URL / no suitable extractor */
    UNSUPPORTED("unsupported"),

    /** Widevine / FairPlay / DRM protected content */
    DRM("drm"),

    /** curl_cffi / impersonation not available */
    IMPERSONATION("impersonation"),

    /** Legacy alias for network timeout */
    TIMEOUT("timeout"),

    /** Unclassified / unknown error */
    UNKNOWN("unknown"),
}

/**
 * Categories that are never eligible for browser fallback recovery.
 */
val TERMINAL_DIRECT_CATEGORIES = setOf(
    FailureCategory.DRM,
    FailureCategory.IMPERSONATION,
    FailureCategory.NETWORK_ERROR,
    FailureCategory.TLS_ERROR,
    FailureCategory.CANCELLED,
    FailureCategory.STORAGE_ERROR,
    FailureCategory.POST_EXTRACTION_ERROR,
    FailureCategory.INTERNAL_ERROR,
    FailureCategory.AUTH_REQUIRED,
    FailureCategory.TIMEOUT,
)

/**
 * Categories eligible for browser fallback recovery.
 */
val BROWSER_RECOVERABLE_DIRECT_CATEGORIES = setOf(
    FailureCategory.BROWSER_RECOVERABLE,
    FailureCategory.CLOUDFLARE,
    FailureCategory.COOKIES,
    FailureCategory.EXPIRED,
    FailureCategory.HTTP,
)

/**
 * Universal markers indicating yt-dlp resolved the media and began real transfer.
 */
val TRANSFER_STARTED_MARKERS = listOf("[download] destination:")

/**
 * Universal markers indicating media resolution or transfer progress.
 */
val RESOLUTION_AND_TRANSFER_MARKERS = listOf("__vrka_title__", "[download] destination:")

/**
 * Generic extractor fetch markers indicating yt-dlp visited and parsed an actual page
 * (e.g. JS-driven page with no extractable formats) rather than bare invalid input.
 */
val GENERIC_EXTRACTOR_FETCH_MARKERS = listOf(
    "falling back on generic information extractor",
    "downloading webpage",
    "extracting information",
)

/**
 * Cleans yt-dlp output by extracting real error lines and ignoring benign warnings.
 * yt-dlp on Android often outputs:
 * "WARNING: The extractor specified to use impersonation for this download, but no impersonate target is available..."
 * This is an informational warning from yt-dlp's extractor indicating curl_cffi is absent in python,
 * but yt-dlp continues normal extraction. If a real failure occurs later, the failure is reported
 * on ERROR lines. Treating this benign warning as an impersonation failure hides genuine errors.
 */
fun cleanErrorOutput(errorMessage: String): String {
    if (errorMessage.isBlank()) return errorMessage
    val lines = errorMessage.lines().map { it.trim() }.filter { it.isNotBlank() }

    // If there are explicit yt-dlp error lines, prioritize them
    val errorLines = lines.filter { line ->
        line.startsWith("ERROR:", ignoreCase = true) ||
        line.startsWith("yt-dlp: error:", ignoreCase = true) ||
        line.startsWith("Traceback (most recent call last):", ignoreCase = true)
    }
    if (errorLines.isNotEmpty()) {
        return errorLines.joinToString("\n")
    }

    // Filter out benign extractor impersonation warnings
    val nonWarningLines = lines.filterNot { line ->
        line.contains("The extractor specified to use impersonation", ignoreCase = true) ||
        (line.startsWith("WARNING:", ignoreCase = true) && line.contains("impersonat", ignoreCase = true))
    }
    return if (nonWarningLines.isNotEmpty()) {
        nonWarningLines.joinToString("\n")
    } else {
        ""
    }
}

/**
 * Classifies a download error message into a [FailureCategory].
 * Pattern matching order matters: more specific patterns are checked first.
 */
fun classifyDownloadError(errorMessage: String): FailureCategory {
    val cleaned = cleanErrorOutput(errorMessage)
    if (cleaned.isBlank() && errorMessage.isNotBlank()) {
        return FailureCategory.UNKNOWN
    }
    val lower = cleaned.lowercase()

    // 1. User cancellation
    if (containsAny(lower, "cancellationexception", "cancelled by user", "operation cancelled", "download cancelled", "task cancelled", "cancelled")) {
        return FailureCategory.CANCELLED
    }

    // 2. Storage / filesystem errors
    if (containsAny(lower, "no space left on device", "insufficient storage", "disk full", "storage error", "enospc", "permission denied (filesystem)")) {
        return FailureCategory.STORAGE_ERROR
    }

    // 3. Post-extraction / FFmpeg / remuxing failures
    if (containsAny(lower, "ffmpeg error", "ffmpeg execution failed", "postprocessing: ffmpeg", "ffmpeg failed", "postprocessing failed", "conversion failed")) {
        return FailureCategory.POST_EXTRACTION_ERROR
    }

    // 4. Internal application exceptions / programming errors
    if (containsAny(lower, "nullpointerexception", "illegalstateexception", "indexoutofboundsexception", "internal application error", "internal error")) {
        return FailureCategory.INTERNAL_ERROR
    }

    // 5. DRM (terminal — never fallback)
    if (containsAny(lower, "drm protected", "protected by drm", "digital rights management", "widevine", "fairplay")) {
        return FailureCategory.DRM
    }

    // 6. Impersonation failure (terminal — never fallback)
    if (containsAny(lower, "unsupported impersonation target", "impersonate target is unavailable", "curl_cffi is not installed", "curl_cffi is required", "error: --impersonate", "--impersonate is not available", "impersonation target")) {
        return FailureCategory.IMPERSONATION
    }

    // 7. TLS / security handshake failure
    if (containsAny(lower, "certificate verify failed", "tls certificate error", "ssl: cert_has_expired", "sslv3_alert_handshake_failure", "tls handshake failure", "certificate error", "ssl error")) {
        return FailureCategory.TLS_ERROR
    }

    // 8. DNS / Network / Connection timeout errors
    if (containsAny(lower, "name or service not known", "failed to resolve", "getaddrinfo failed", "dns error", "unknown host", "name_not_resolved", "network is unreachable")) {
        return FailureCategory.NETWORK_ERROR
    }
    if (containsAny(lower, "timed out", "timeout", "read operation timed out", "connection timed out", "connect timeout")) {
        return FailureCategory.NETWORK_ERROR
    }

    // 9. Cloudflare / bot detection (browser-recoverable)
    if (containsAny(lower, "cloudflare", "cf-chl-", "just a moment...", "just a moment", "attention required")) {
        return FailureCategory.CLOUDFLARE
    }

    // 10. Extractor / Parser / Client-side player failure (browser-recoverable)
    // Common generic yt-dlp extractor failures where direct parser cannot understand page or player
    if (containsAny(lower,
            "unable to extract flashvars",
            "unable to extract player",
            "unable to extract embed",
            "unable to extract video",
            "unable to extract media",
            "unable to extract formats",
            "failed to extract",
            "failed to parse player data",
            "failed to parse player",
            "failed to parse json",
            "failed to parse webpage data",
            "failed to parse webpage",
            "unable to parse webpage",
            "no video formats found",
            "kvs",
            "client-side player",
            "embedded-player",
            "embedded player")) {
        return FailureCategory.BROWSER_RECOVERABLE
    }

    // 11. Cookie database errors
    if (containsAny(lower,
            "cookies-from-browser",
            "could not find chrome",
            "could not find edge",
            "could not find firefox",
            "could not find brave",
            "could not copy chrome cookie database",
            "could not copy edge cookie database",
            "could not copy firefox cookie database",
            "could not copy brave cookie database",
            "database is locked",
            "failed to decrypt",
            "cookie decryption",
            "browser must be closed",
            "no useful cookies",
            "no cookies")) {
        return FailureCategory.COOKIES
    }

    // 12. Genuine authentication failure
    if (containsAny(lower,
            "authentication required",
            "login required",
            "sign in to confirm",
            "account required",
            "this video is private",
            "private video")) {
        return FailureCategory.AUTH_REQUIRED
    }

    // 13. Expired URL / token (browser-recoverable)
    if (containsAny(lower, "url has expired", "expired url", "signature has expired")) {
        return FailureCategory.EXPIRED
    }

    // 14. Unsupported URL or unavailable media (Desktop generic extractor fetch markers distinguish real page from invalid URL)
    if (containsAny(lower, "unsupported url", "no suitable extractor", "not a valid url", "this video is unavailable", "video unavailable", "video is unavailable")) {
        return FailureCategory.UNSUPPORTED
    }

    // 15. HTTP error / network rejection (browser-recoverable)
    if (containsAny(lower, "http error", "403 forbidden", "unable to download webpage", "connection reset", "429")) {
        return FailureCategory.HTTP
    }

    return FailureCategory.UNKNOWN
}

/**
 * Formats friendly user guidance for classified errors.
 */
fun formatDownloadError(errorMessage: String): Pair<FailureCategory, String> {
    val cleaned = cleanErrorOutput(errorMessage)
    val category = classifyDownloadError(cleaned)
    val guidance = when (category) {
        FailureCategory.SUCCESS ->
            "Download completed successfully."
        FailureCategory.DRM ->
            "This media appears to be DRM-protected. VRKA will not bypass DRM; use a lawful non-DRM source."
        FailureCategory.CLOUDFLARE ->
            "The site returned a Cloudflare verification response. Browser impersonation, cookies, or the on-demand verification window may help."
        FailureCategory.IMPERSONATION ->
            "The selected browser impersonation target is unavailable in this yt-dlp build."
        FailureCategory.BROWSER_RECOVERABLE ->
            "yt-dlp could not read the site's player. VRKA will try the browser fallback."
        FailureCategory.AUTH_REQUIRED ->
            "The site requires an authenticated browser session or account login."
        FailureCategory.COOKIES ->
            "The site appears to require an authenticated browser session or valid cookies."
        FailureCategory.NETWORK_ERROR, FailureCategory.TIMEOUT ->
            "The site did not respond in time or network is unreachable. Check the connection and try again."
        FailureCategory.TLS_ERROR ->
            "TLS/security validation failed. Check system date/time and certificates."
        FailureCategory.CANCELLED ->
            "Download was cancelled."
        FailureCategory.STORAGE_ERROR ->
            "Insufficient storage space or filesystem error. Free up space and try again."
        FailureCategory.POST_EXTRACTION_ERROR ->
            "Media extraction succeeded, but subsequent processing or file assembly failed."
        FailureCategory.INTERNAL_ERROR ->
            "An internal application error occurred."
        FailureCategory.EXPIRED ->
            "The media address appears to have expired. Refresh the page and try again."
        FailureCategory.UNSUPPORTED ->
            if (cleaned.contains("video is unavailable", ignoreCase = true) || cleaned.contains("video unavailable", ignoreCase = true)) {
                "This video is unavailable or has been removed."
            } else {
                "This address is not supported by the active yt-dlp build."
            }
        FailureCategory.HTTP ->
            if (cleaned.contains("browser credential/custom header", ignoreCase = true)) {
                "The site rejected the browser-fallback transfer. VRKA withheld credential-like browser headers because yt-dlp cannot safely scope them across redirects and media segments."
            } else if (isSubtitleFailure(cleaned, "")) {
                "The site rate-limited or rejected the subtitle request (HTTP 429 Too Many Requests). Try downloading without subtitles."
            } else if (cleaned.contains("429", ignoreCase = true)) {
                "The site rate-limited the request (HTTP 429 Too Many Requests). Try again later."
            } else {
                "The website rejected or interrupted the request."
            }
        FailureCategory.UNKNOWN ->
            cleaned.ifBlank { errorMessage }.ifBlank { "The operation failed." }
    }
    return category to guidance
}

/**
 * Detects if a failure was specifically caused by subtitle downloading or extraction.
 * Subtitle failures cannot be resolved by browser fallback because browser fallback
 * only sniffs direct media streams (audio/video), not closed captions / subtitle tracks.
 */
fun isSubtitleFailure(errorMessage: String, executionOutput: String = ""): Boolean {
    val combined = "$errorMessage\n$executionOutput".lowercase()
    val subtitleMarkers = listOf(
        "unable to download video subtitles",
        "unable to download all requested subtitles",
        "unable to download subtitles",
        "error downloading subtitles",
        "failed to download subtitles",
        "subtitles could not be downloaded",
        "writing video subtitles failed",
        "no subtitles being downloaded",
        "downloading video subtitles",
        "downloading auto-generated video subtitles",
        "sleep-subtitles",
        "--write-subs",
        "--write-auto-subs",
    )
    if (subtitleMarkers.any { it in combined }) return true
    if ((combined.contains("subtitle") || combined.contains("caption")) && (
        combined.contains("429") ||
        combined.contains("too many requests") ||
        combined.contains("unable to download") ||
        combined.contains("http error") ||
        combined.contains("rate-limit") ||
        combined.contains("rate limit")
    )) {
        return true
    }
    return false
}

/**
 * True when the direct run already resolved the media and began a real transfer before failing.
 */
fun isTransferFailureAfterResolution(
    executionOutput: String = "",
    hasResolvedMediaUrl: Boolean = false,
    hasTransferStarted: Boolean = false,
): Boolean {
    if (hasResolvedMediaUrl || hasTransferStarted) return true
    return executionOutput.lineSequence().any { rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("[debug]", ignoreCase = true)) return@any false
        if (trimmed.contains("--print", ignoreCase = true) ||
            trimmed.contains("before_dl:", ignoreCase = true) ||
            trimmed.contains("%(title)s", ignoreCase = true)
        ) return@any false
        val lower = trimmed.lowercase()
        RESOLUTION_AND_TRANSFER_MARKERS.any { it in lower }
    }
}

/**
 * True when an Unsupported-URL failure still fetched a real page.
 */
fun unsupportedFailureFetchedPage(output: String): Boolean {
    val lower = output.lowercase()
    return GENERIC_EXTRACTOR_FETCH_MARKERS.any { it in lower }
}

/**
 * True when a URL targets a dedicated yt-dlp native extractor (e.g. YouTube).
 * Direct controls stay on the direct path and recover with their own retry rules.
 */
fun isYtdlpNativeTarget(url: String): Boolean {
    if (url.isBlank()) return false
    val trimmed = url.trim().lowercase()
    if (trimmed.contains("youtube.com") || trimmed.contains("youtu.be")) return true
    val host = try {
        java.net.URI(trimmed).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }
    return host == "youtube.com" || host.endsWith(".youtube.com") ||
           host == "youtu.be" || host.endsWith(".youtu.be")
}

/**
 * Determines if a classified failure is eligible for browser fallback recovery.
 */
fun isFailureBrowserRecoverable(
    category: FailureCategory,
    errorMessage: String = "",
    targetUrl: String = "",
    executionOutput: String = "",
    hasResolvedMediaUrl: Boolean = false,
    hasTransferStarted: Boolean = false,
    priorCategories: Collection<FailureCategory> = emptyList(),
): Boolean {
    // A failure after requested media was resolved and a real transfer began
    // is never a page-access failure and never fallback-eligible.
    if (isTransferFailureAfterResolution(executionOutput, hasResolvedMediaUrl, hasTransferStarted)) {
        return false
    }

    // Subtitle failures cannot be resolved by browser fallback (Puemos only sniffs media streams).
    if (isSubtitleFailure(errorMessage, executionOutput)) {
        return false
    }

    // Terminal categories: never fallback
    if (category in TERMINAL_DIRECT_CATEGORIES) return false

    // YouTube / yt-dlp-native targets:
    // Direct controls stay on the direct path and recover with their own retry rules.
    // HTTP rate limits (429) or extractor/player failures on YouTube must never launch Browser Fallback.
    // Only bot challenges (Cloudflare) or session authentication walls (COOKIES) might be browser-recoverable.
    val isYouTube = isYtdlpNativeTarget(targetUrl) ||
        executionOutput.contains("[youtube]", ignoreCase = true) ||
        errorMessage.contains("[youtube]", ignoreCase = true)

    if (isYouTube) {
        if (category == FailureCategory.HTTP || category == FailureCategory.BROWSER_RECOVERABLE) {
            return false
        }
        return category in listOf(FailureCategory.COOKIES, FailureCategory.CLOUDFLARE)
    }

    // Browser-recoverable categories
    if (category in BROWSER_RECOVERABLE_DIRECT_CATEGORIES) return true

    // Unsupported: eligible when generic extractor visibly fetched the page before giving up
    if (category == FailureCategory.UNSUPPORTED) {
        val combined = if (executionOutput.isNotBlank()) "$errorMessage\n$executionOutput" else errorMessage
        if (unsupportedFailureFetchedPage(combined)) return true
    }

    // Prior categories in attempt chain (e.g. HTTP 403 then Unsupported URL after impersonation retry)
    if (priorCategories.any { it in BROWSER_RECOVERABLE_DIRECT_CATEGORIES }) {
        return true
    }

    // Unknown and other failures: not recoverable by default
    return false
}

/**
 * Convenience function to classify an error message and determine fallback eligibility
 * in a single call.
 */
fun classifyAndCheckRecoverable(
    errorMessage: String,
    targetUrl: String = "",
    executionOutput: String = "",
    hasResolvedMediaUrl: Boolean = false,
    hasTransferStarted: Boolean = false,
    priorCategories: Collection<FailureCategory> = emptyList(),
): Pair<FailureCategory, Boolean> {
    val category = classifyDownloadError(errorMessage)
    val recoverable = isFailureBrowserRecoverable(
        category = category,
        errorMessage = errorMessage,
        targetUrl = targetUrl,
        executionOutput = executionOutput,
        hasResolvedMediaUrl = hasResolvedMediaUrl,
        hasTransferStarted = hasTransferStarted,
        priorCategories = priorCategories,
    )
    return category to recoverable
}

/**
 * Diagnostic failure codes for internal tracing and logging.
 */
enum class DiagnosticFailure(val code: String) {
    DIRECT_EXTRACTION_FAILED("DIRECT_EXTRACTION_FAILED"),
    FALLBACK_OPEN_FAILED("FALLBACK_OPEN_FAILED"),
    PAGE_LOAD_FAILED("PAGE_LOAD_FAILED"),
    USER_INTERACTION_FAILED("USER_INTERACTION_FAILED"),
    PLAYBACK_NOT_STARTED("PLAYBACK_NOT_STARTED"),
    NO_MEDIA_CANDIDATE("NO_MEDIA_CANDIDATE"),
    CANDIDATE_REJECTED("CANDIDATE_REJECTED"),
    CANDIDATE_VALIDATION_FAILED("CANDIDATE_VALIDATION_FAILED"),
    CONTEXT_TRANSFER_FAILED("CONTEXT_TRANSFER_FAILED"),
    DOWNLOADER_INITIALIZATION_FAILED("DOWNLOADER_INITIALIZATION_FAILED"),
    HTTP_TRANSFER_FAILED("HTTP_TRANSFER_FAILED"),
    HLS_ASSEMBLY_FAILED("HLS_ASSEMBLY_FAILED"),
    DASH_ASSEMBLY_FAILED("DASH_ASSEMBLY_FAILED"),
    POST_PROCESSING_FAILED("POST_PROCESSING_FAILED"),
    FINAL_VALIDATION_FAILED("FINAL_VALIDATION_FAILED"),
    FALLBACK_TIMEOUT("FALLBACK_TIMEOUT"),
    FALLBACK_CANCELLED("FALLBACK_CANCELLED"),
    GECKO_TRANSPORT_ACTIVATED("GECKO_TRANSPORT_ACTIVATED"),
    GECKO_TRANSPORT_FAILED("GECKO_TRANSPORT_FAILED"),
    UNKNOWN_FAILURE("UNKNOWN_FAILURE"),
}

/**
 * Granular classification for native replay failures when attempting to download
 * a browser-established media candidate.
 */
enum class NativeReplayFailureReason(val code: String) {
    TLS_HANDSHAKE_FAILURE("TLS_HANDSHAKE_FAILURE"),
    HTTP_FORBIDDEN_OR_AUTH("HTTP_FORBIDDEN_OR_AUTH"),
    HTTP_CLIENT_ERROR("HTTP_CLIENT_ERROR"),
    HTTP_SERVER_ERROR("HTTP_SERVER_ERROR"),
    CONNECTION_RESET("CONNECTION_RESET"),
    NETWORK_TIMEOUT("NETWORK_TIMEOUT"),
    DNS_FAILURE("DNS_FAILURE"),
    BOT_CHALLENGE("BOT_CHALLENGE"),
    DRM_PROTECTED("DRM_PROTECTED"),
    OTHER_FAILURE("OTHER_FAILURE"),
}

/**
 * Classifies the exact reason a native replay attempt failed on a browser-established candidate.
 */
fun classifyNativeReplayFailure(errorMessage: String): NativeReplayFailureReason {
    val lower = errorMessage.lowercase()
    if (containsAny(lower, "drm", "widevine", "fairplay", "content decryption")) {
        return NativeReplayFailureReason.DRM_PROTECTED
    }
    if (containsAny(lower, "ssl", "tls", "handshake", "cipher", "alert 40", "sslv3", "tlsv1", "certificate")) {
        return NativeReplayFailureReason.TLS_HANDSHAKE_FAILURE
    }
    if (containsAny(lower, "403", "forbidden", "access denied", "401", "unauthorized")) {
        return NativeReplayFailureReason.HTTP_FORBIDDEN_OR_AUTH
    }
    if (containsAny(lower, "cloudflare", "challenge", "turnstile", "captcha", "cf-chl-", "verify you are human")) {
        return NativeReplayFailureReason.BOT_CHALLENGE
    }
    if (containsAny(lower, "connection reset", "connection refused", "broken pipe", "socket closed")) {
        return NativeReplayFailureReason.CONNECTION_RESET
    }
    if (containsAny(lower, "timed out", "timeout", "read timeout", "connect timeout")) {
        return NativeReplayFailureReason.NETWORK_TIMEOUT
    }
    if (containsAny(lower, "name or service not known", "dns", "unknown host", "nodename nor servname")) {
        return NativeReplayFailureReason.DNS_FAILURE
    }
    if (containsAny(lower, "404", "410", "not found", "gone")) {
        return NativeReplayFailureReason.HTTP_CLIENT_ERROR
    }
    if (containsAny(lower, "500", "502", "503", "504", "bad gateway", "service unavailable", "gateway timeout")) {
        return NativeReplayFailureReason.HTTP_SERVER_ERROR
    }
    return NativeReplayFailureReason.OTHER_FAILURE
}

/**
 * Determines whether a native replay failure is eligible for GeckoWebExecutor
 * browser-network transport:
 * 1. Must be a browser-derived candidate.
 * 2. Must be a recoverable network/TLS/auth failure (not DRM, not client error 404).
 */
fun isEligibleForGeckoTransport(
    reason: NativeReplayFailureReason,
    isBrowserDerivedCandidate: Boolean,
): Boolean {
    if (!isBrowserDerivedCandidate) return false
    return when (reason) {
        NativeReplayFailureReason.TLS_HANDSHAKE_FAILURE -> true
        NativeReplayFailureReason.HTTP_FORBIDDEN_OR_AUTH -> true
        NativeReplayFailureReason.BOT_CHALLENGE -> true
        NativeReplayFailureReason.CONNECTION_RESET -> true
        NativeReplayFailureReason.NETWORK_TIMEOUT -> true
        NativeReplayFailureReason.OTHER_FAILURE -> true
        NativeReplayFailureReason.DRM_PROTECTED -> false
        NativeReplayFailureReason.HTTP_CLIENT_ERROR -> false
        NativeReplayFailureReason.HTTP_SERVER_ERROR -> false
        NativeReplayFailureReason.DNS_FAILURE -> false
    }
}

private fun containsAny(text: String, vararg patterns: String): Boolean =
    patterns.any { text.contains(it) }
