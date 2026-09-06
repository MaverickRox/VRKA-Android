/**
 * Download failure classification and browser-fallback eligibility.
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.engine

/**
 * Failure categories for download error taxonomy.
 */
enum class FailureCategory(val value: String) {
    /** Cloudflare / bot challenge / turnstile / captcha */
    CLOUDFLARE("cloudflare"),

    /** Login / sign-in / cookie / authentication required */
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

    /** Network / connection / read timeout */
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
)

/**
 * Categories eligible for browser fallback recovery.
 */
val BROWSER_RECOVERABLE_DIRECT_CATEGORIES = setOf(
    FailureCategory.CLOUDFLARE,
    FailureCategory.COOKIES,
    FailureCategory.EXPIRED,
    FailureCategory.HTTP,
)

/**
 * Universal markers indicating yt-dlp resolved the media and began real transfer.
 */
val TRANSFER_STARTED_MARKERS = listOf("__vrka_title__", "[download] destination:")

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
 * Classifies a download error message into a [FailureCategory].
 * Pattern matching order matters: more specific patterns are checked first.
 */
fun classifyDownloadError(errorMessage: String): FailureCategory {
    val lower = errorMessage.lowercase()

    // DRM (terminal — never fallback)
    if (containsAny(lower, "drm protected", "protected by drm", "digital rights management")) {
        return FailureCategory.DRM
    }

    // Cloudflare / bot detection (browser-recoverable)
    if (containsAny(lower, "cloudflare", "cf-chl-", "just a moment...", "just a moment", "attention required")) {
        return FailureCategory.CLOUDFLARE
    }

    // Impersonation failure (terminal — never fallback)
    if (containsAny(lower, "impersonate", "curl_cffi", "unsupported impersonation target")) {
        return FailureCategory.IMPERSONATION
    }

    // Cookie / authentication (browser-recoverable)
    if (containsAny(lower,
            "cookies-from-browser",
            "sign in to confirm",
            "login required",
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

    // Expired URL / token (browser-recoverable)
    if (containsAny(lower, "url has expired", "expired url", "signature has expired")) {
        return FailureCategory.EXPIRED
    }

    // Timeout (direct terminal — not browser-recoverable)
    if (containsAny(lower, "timed out", "timeout", "read operation timed out")) {
        return FailureCategory.TIMEOUT
    }

    // Unsupported URL (Desktop generic extractor fetch markers distinguish real page from invalid URL)
    if (containsAny(lower, "unsupported url", "no suitable extractor", "not a valid url")) {
        return FailureCategory.UNSUPPORTED
    }

    // HTTP error / network rejection (browser-recoverable)
    if (containsAny(lower, "http error", "403 forbidden", "unable to download webpage", "connection reset")) {
        return FailureCategory.HTTP
    }

    return FailureCategory.UNKNOWN
}

/**
 * Formats friendly user guidance for classified errors.
 */
fun formatDownloadError(errorMessage: String): Pair<FailureCategory, String> {
    val category = classifyDownloadError(errorMessage)
    val guidance = when (category) {
        FailureCategory.DRM ->
            "This media appears to be DRM-protected. VRKA will not bypass DRM; use a lawful non-DRM source."
        FailureCategory.CLOUDFLARE ->
            "The site returned a Cloudflare verification response. Browser impersonation, cookies, or the on-demand verification window may help."
        FailureCategory.IMPERSONATION ->
            "The selected browser impersonation target is unavailable in this yt-dlp build."
        FailureCategory.COOKIES ->
            "The site appears to require an authenticated browser session or valid cookies."
        FailureCategory.EXPIRED ->
            "The media address appears to have expired. Refresh the page and try again."
        FailureCategory.TIMEOUT ->
            "The site did not respond in time. Check the connection and try again."
        FailureCategory.UNSUPPORTED ->
            "This address is not supported by the active yt-dlp build."
        FailureCategory.HTTP ->
            "The website rejected or interrupted the request."
        FailureCategory.UNKNOWN ->
            errorMessage.ifBlank { "The operation failed." }
    }
    return category to guidance
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
        TRANSFER_STARTED_MARKERS.any { it in lower }
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
    val host = try {
        java.net.URI(url.trim()).host?.lowercase() ?: ""
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

    // Terminal categories: never fallback
    if (category in TERMINAL_DIRECT_CATEGORIES) return false

    // YouTube / yt-dlp-native targets:
    // Direct controls stay on the direct path and recover with their own retry rules.
    // When direct yt-dlp cannot safely acquire media due to authentication/bot-check walls
    // (COOKIES) or HTTP access restrictions, fallback is allowed.
    if (isYtdlpNativeTarget(targetUrl)) {
        return category in listOf(FailureCategory.COOKIES, FailureCategory.CLOUDFLARE, FailureCategory.HTTP)
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
