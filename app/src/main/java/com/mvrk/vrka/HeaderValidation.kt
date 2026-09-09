package com.mvrk.vrka

import java.util.Locale
import java.util.TreeMap

internal sealed class HeaderValidationResult {
    object Valid : HeaderValidationResult()
    data class Invalid(val reason: String) : HeaderValidationResult()
}

internal object HeaderValidation {
    private val HEADER_NAME_REGEX = Regex("""^[A-Za-z0-9!#$%&'*+\-.^_`|~]+$""")

    private val ALLOWED_SESSION_HEADERS = setOf(
        "authorization",
        "cookie",
        "origin",
        "referer",
        "user-agent",
        "x-video-expiration",
        "x-video-ip",
        "x-video-token",
    )

    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "apikey",
        "x-auth-token",
        "session",
        "session-token",
    )

    private val SENSITIVE_KEYWORD_PATTERNS = listOf(
        "token", "secret", "auth", "password", "session", "key",
    )

    fun isValidHeaderName(name: String): Boolean =
        validateHeaderName(name) is HeaderValidationResult.Valid

    fun validateHeaderName(name: String): HeaderValidationResult {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return HeaderValidationResult.Invalid("Header name cannot be empty.")
        }
        if (trimmed.contains('\r') || trimmed.contains('\n') || trimmed.contains("%0d", true) || trimmed.contains("%0a", true)) {
            return HeaderValidationResult.Invalid("Header name contains forbidden CRLF characters (CRLF injection rejected).")
        }
        if (!HEADER_NAME_REGEX.matches(trimmed)) {
            return HeaderValidationResult.Invalid("Header name '$trimmed' contains invalid characters. Must follow RFC 7230 token specification.")
        }
        return HeaderValidationResult.Valid
    }

    fun validateHeaderValue(value: String): HeaderValidationResult {
        if (value.contains('\r') || value.contains('\n') || value.contains("%0d", true) || value.contains("%0a", true)) {
            return HeaderValidationResult.Invalid("Header value contains forbidden CRLF characters (CRLF injection rejected).")
        }
        val trimmed = value.trim()
        for (char in trimmed) {
            val code = char.code
            if (code < 32 && code != 9) {
                return HeaderValidationResult.Invalid("Header value contains unprintable control character (ASCII $code).")
            }
        }
        return HeaderValidationResult.Valid
    }

    fun canonicalHeaderName(name: String): String = when (name.lowercase(Locale.ROOT)) {
        "referer" -> "Referer"
        "origin" -> "Origin"
        "user-agent" -> "User-Agent"
        "cookie" -> "Cookie"
        "authorization" -> "Authorization"
        "proxy-authorization" -> "Proxy-Authorization"
        "content-type" -> "Content-Type"
        "accept" -> "Accept"
        else -> name
    }

    fun isSensitiveHeader(headerName: String): Boolean {
        val lower = headerName.trim().lowercase(Locale.ROOT)
        if (lower in SENSITIVE_HEADER_NAMES) return true
        return SENSITIVE_KEYWORD_PATTERNS.any { lower.contains(it) }
    }

    fun redactValueIfSensitive(headerName: String, value: String): String =
        if (isSensitiveHeader(headerName)) "[REDACTED]" else value

    fun parseHeaderPairs(pairs: List<Pair<String, String>>): Map<String, String> {
        val map = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((name, value) in pairs) {
            val trimmedName = name.trim()
            val trimmedValue = value.trim()
            if (trimmedName.isNotBlank() &&
                validateHeaderName(trimmedName) is HeaderValidationResult.Valid &&
                validateHeaderValue(trimmedValue) is HeaderValidationResult.Valid
            ) {
                map[canonicalHeaderName(trimmedName)] = trimmedValue
            }
        }
        return map
    }

    /**
     * Resolves effective HTTP headers for network request paths (yt-dlp, FFmpeg, GeckoTransport).
     *
     * Precedence:
     * 1. User custom headers (arbitrary RFC 7230 headers).
     * 2. Dedicated Origin field overrides custom Origin.
     * 3. Dedicated Referer field overrides custom Referer.
     * 4. Validated session headers from browser fallback (filtered to allowed session headers).
     */
    fun resolveEffectiveHeaders(request: DownloadRequest): Map<String, String> {
        val result = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

        // 1. User custom headers (arbitrary RFC 7230 headers)
        request.customHeaders.forEach { (name, value) ->
            if (validateHeaderName(name) is HeaderValidationResult.Valid &&
                validateHeaderValue(value) is HeaderValidationResult.Valid
            ) {
                result[canonicalHeaderName(name.trim())] = value.trim()
            }
        }

        // 2. Dedicated Origin field overrides custom Origin
        if (request.origin.isNotBlank() && validateHeaderValue(request.origin) is HeaderValidationResult.Valid) {
            result["Origin"] = request.origin.trim()
        }

        // 3. Dedicated Referer field overrides custom Referer
        if (request.referer.isNotBlank() && validateHeaderValue(request.referer) is HeaderValidationResult.Valid) {
            result["Referer"] = request.referer.trim()
        }

        // 4. Resolved session headers from browser fallback (allowed session headers only)
        request.resolvedHeaders.forEach { (name, value) ->
            val trimmedName = name.trim()
            val lowerName = trimmedName.lowercase(Locale.ROOT)
            if (lowerName in ALLOWED_SESSION_HEADERS &&
                validateHeaderName(trimmedName) is HeaderValidationResult.Valid &&
                validateHeaderValue(value) is HeaderValidationResult.Valid
            ) {
                val trimmedValue = value.trim()
                if (trimmedValue.isNotBlank()) {
                    result[canonicalHeaderName(trimmedName)] = trimmedValue
                }
            }
        }

        return result
    }

    /**
     * Redacts sensitive header values from command lines, error strings, and diagnostic dumps.
     */
    fun redactSensitiveHeaderInText(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // 1. Redact CLI arguments: --add-header "Cookie: xyz" or --add-header Cookie:xyz or -H "Cookie: xyz"
        val cliHeaderRegex = Regex("(?i)(--(?:add-)?header\\s+[\"']?|-H\\s+[\"']?)([A-Za-z0-9!#$%&'*+\\-.^_`|~]+)\\s*:\\s*([^\"'\\r\\n]+)([\"']?)")
        result = cliHeaderRegex.replace(result) { match ->
            val prefix = match.groupValues[1]
            val name = match.groupValues[2]
            val suffix = match.groupValues[4]
            if (isSensitiveHeader(name)) {
                "${prefix}${name}:[REDACTED]${suffix}"
            } else {
                match.value
            }
        }

        // 2. Redact line-by-line headers: Name: Value
        result = result.lines().joinToString("\n") { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val candidateName = line.substring(0, colonIndex).trim()
                if (isSensitiveHeader(candidateName) && isValidHeaderName(candidateName)) {
                    val prefix = line.substring(0, colonIndex + 1)
                    val value = line.substring(colonIndex + 1).trim()
                    if (value.startsWith("[REDACTED]")) {
                        line
                    } else {
                        "$prefix [REDACTED]"
                    }
                } else {
                    line
                }
            } else {
                line
            }
        }

        return result
    }
}
