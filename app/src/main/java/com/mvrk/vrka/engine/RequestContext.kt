package com.mvrk.vrka.engine

import com.mvrk.vrka.HeaderValidation
import java.net.URI

/**
 * Host- and origin-scoped request context.
 *
 * Ensures security isolation while respecting browser-derived context:
 * 1. Statically strips incoming "Range" headers by default to prevent single-fragment corruption.
 * 2. Retains browser-captured headers when requesting the exact target URL.
 * 3. Binds session cookies and Authorization tokens to the exact observed target host.
 * 4. Preserves an observed Referer/Origin without reconstructing browser request headers.
 * 5. Prevents credential leakage across third-party CDNs and signed URL endpoints.
 */
data class RequestContext(
    val targetUrl: String,
    val pageUrl: String? = null,
    val baseHeaders: Map<String, String> = emptyMap(),
    val cookies: List<Map<String, String>> = emptyList(),
) {
    private val targetOrigin: String = HeaderValidation.originOf(targetUrl).orEmpty()

    /**
     * Resolves effective request headers for [requestUrl].
     *
     * @param requestUrl the specific endpoint being requested (playlist, segment, media).
     * @param allowRange if false (default), strips any "Range" headers to ensure full media stream acquisition.
     * @return safe, origin-scoped header map preferring browser-captured values.
     */
    fun resolveHeaders(
        requestUrl: String,
        allowRange: Boolean = false
    ): Map<String, String> {
        val reqOrigin = HeaderValidation.originOf(requestUrl)
        val isExactOrigin = !reqOrigin.isNullOrBlank() && reqOrigin == targetOrigin

        val result = mutableMapOf<String, String>()

        // 1. Base headers filtering
        for ((key, value) in baseHeaders) {
            val lowerKey = key.lowercase()

            // Range header handling: strip unless explicitly allowed
            if (lowerKey == "range" && !allowRange) {
                continue
            }

            // Sensitive headers are retained only for the exact observed target origin (scheme + host + port).
            if (lowerKey in SENSITIVE_HEADERS || HeaderValidation.isSensitiveHeader(key)) {
                if (isExactOrigin) {
                    result[key] = value
                }
            } else {
                result[key] = value
            }
        }

        // 2. Cookie resolution from cookie jar if available and origin matches
        if (isExactOrigin && cookies.isNotEmpty() && !result.containsKey("Cookie")) {
            val cookieStrings = cookies.mapNotNull { cookie ->
                val name = cookie["name"]
                val value = cookie["value"]
                if (!name.isNullOrBlank() && value != null) "$name=$value" else null
            }
            if (cookieStrings.isNotEmpty()) {
                result["Cookie"] = cookieStrings.joinToString("; ")
            }
        }

        // 3. Referer injection for cross-origin CDN media (never across HTTPS -> HTTP scheme downgrade):
        // Prefers pageUrl if present; otherwise fallback to targetUrl
        if (!result.containsKey("Referer")) {
            val ref = pageUrl?.takeIf { it.isNotBlank() } ?: targetUrl
            val downgradedToHttp = ref.startsWith("https://", ignoreCase = true) &&
                requestUrl.startsWith("http://", ignoreCase = true)
            if (ref.isNotBlank() && !downgradedToHttp) {
                result["Referer"] = ref
            }
        }

        // 5. Origin validation & character sanitization
        return HeaderValidation.bindHeadersToHost(
            headers = result,
            targetUrl = requestUrl,
            contextUrl = targetUrl
        )
    }

    companion object {
        private val SENSITIVE_HEADERS = setOf(
            "cookie",
            "authorization",
            "proxy-authorization",
            "x-csrf-token",
            "x-xsrf-token",
            "x-auth-token"
        )

        fun extractHost(url: String): String {
            return try {
                URI(url).host?.lowercase() ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        fun extractOrigin(url: String): String = HeaderValidation.originOf(url).orEmpty()

        fun isSameDomainOrSubdomain(host1: String, host2: String): Boolean =
            host1.isNotBlank() && host2.isNotBlank() && host1.equals(host2, ignoreCase = true)
    }
}
