package com.mvrk.vrka

import java.net.URI
import java.util.Locale
import java.util.UUID

data class YtDlpCommandTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: List<String>,
)

internal data class TemplateDeletionResult(
    val templates: List<YtDlpCommandTemplate>,
    val selectedTemplateId: String,
)

internal object CustomCommandPolicy {
    const val MAX_TEMPLATES = 20
    const val MAX_ARGUMENTS = 80
    const val MAX_TEMPLATE_CHARS = 8_000

    private val blockedOptions = setOf(
        "--exec",
        "--exec-before-download",
        "--external-downloader",
        "--external-downloader-args",
        "--downloader",
        "--downloader-args",
        "--config",
        "--config-location",
        "--config-locations",
        "--plugin-dirs",
        "--load-info-json",
        "--batch-file",
        "--output",
        "--paths",
        "--output-na-placeholder",
        "--print-to-file",
        "--ffmpeg-location",
        "--postprocessor-args",
        "--ppa",
        "--cookies",
        "--cookies-from-browser",
        "--netrc",
        "--netrc-location",
        "--cache-dir",
        "--download-archive",
        "--proxy",
        "--geo-verification-proxy",
        "--no-check-certificates",
        "--prefer-insecure",
        "--write-pages",
        "--enable-file-urls",
    )

    private val valueOptions = setOf(
        "-f", "--format", "-S", "--format-sort",
        "--audio-format", "--audio-quality", "--merge-output-format", "--remux-video", "--recode-video",
        "--limit-rate", "-r", "--retries", "--fragment-retries", "--file-access-retries",
        "--extractor-retries", "--concurrent-fragments", "-N", "--sleep-interval", "--max-sleep-interval",
        "--sleep-subtitles", "--sub-langs", "--sub-format", "--sub-fallback", "--user-agent",
        "--referer", "--add-header", "--add-headers", "-H", "--extractor-args", "--compat-options",
        "--match-filters", "--dateafter", "--datebefore", "--min-filesize", "--max-filesize",
        "--min-views", "--max-views", "--playlist-start", "--playlist-end", "-I",
        "--max-downloads", "--socket-timeout", "--encoding", "--impersonate", "--xff",
        "--download-sections", "--daterange", "--date",
    )
    private val safeCustomHeaderNames = setOf(
        // Templates persist in preferences and job history, so credential-bearing headers
        // must never be accepted here. Browser-session cookies use the ephemeral handoff path.
        "accept", "accept-language", "origin", "referer", "user-agent", "x-requested-with",
    )

    fun validateTemplate(name: String, arguments: List<String>): String? {
        if (name.trim().isEmpty()) return "Give this template a name."
        if (name.length > 80) return "Template names must be 80 characters or fewer."
        if (arguments.isEmpty()) return "Add at least one yt-dlp option."
        if (arguments.size > MAX_ARGUMENTS) return "Templates can contain at most $MAX_ARGUMENTS arguments."
        if (arguments.sumOf(String::length) > MAX_TEMPLATE_CHARS) return "Template text is too long."
        var expectsValue = false
        var expectedOption = ""
        for (argument in arguments) {
            if (argument.isEmpty()) return "Template arguments cannot be empty."
            if (argument.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                return "Template arguments cannot contain control characters."
            }
            if (expectsValue) {
                if (argument.startsWith('-')) return "Option values cannot begin with another option."
                if (expectedOption in setOf("--add-header", "--add-headers", "-h") &&
                    containsUnsafeTemplateHeader(argument)
                ) {
                    return "Authorization and credential-like custom headers cannot be used in templates because yt-dlp cannot safely scope them across redirects."
                }
                expectsValue = false
                expectedOption = ""
                continue
            }
            if (!argument.startsWith('-')) {
                return "Templates may contain yt-dlp options and their values, but no extra positional URLs or arguments."
            }
            val option = argument.substringBefore('=').lowercase(Locale.ROOT)
            if (option in blockedOptions || option == "-o" || option == "-p" ||
                option.startsWith("-o") && option.length > 2 || option.startsWith("--exec") ||
                option.startsWith("--external-downloader") || option.startsWith("--downloader")
            ) {
                return "This template option is managed by VRKA or can execute another command: $option"
            }
            expectsValue = '=' !in argument && option in valueOptions
            expectedOption = if (expectsValue) option else ""
            if (option in setOf("--add-header", "--add-headers", "-h") && '=' in argument &&
                containsUnsafeTemplateHeader(argument.substringAfter('='))
            ) {
                return "Authorization and credential-like custom headers cannot be used in templates because yt-dlp cannot safely scope them across redirects."
            }
            if (argument.startsWith("-H", ignoreCase = true) && argument.length > 2) {
                return "Use a separated --add-header option; attached short-option forms are not supported."
            }
        }
        if (expectsValue) return "The final template option is missing its value."
        return null
    }

    private fun containsUnsafeTemplateHeader(argument: String): Boolean {
        val headerName = argument.substringBefore(':').trim()
        return headerName.isBlank() || headerName.lowercase(Locale.ROOT) !in safeCustomHeaderNames
    }

    /** Parses a human-edited argument line. This handles quoting only; it never invokes a shell. */
    fun parseArgumentLine(text: String): List<String> {
        require(text.length <= MAX_TEMPLATE_CHARS) { "Template text is too long." }
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        for (char in text) {
            if (escaping) {
                token.append(char)
                tokenStarted = true
                escaping = false
                continue
            }
            when {
                char == '\\' && quote != '\'' -> {
                    escaping = true
                    tokenStarted = true
                }
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> {
                    quote = char
                    tokenStarted = true
                }
                quote == null && char.isWhitespace() -> {
                    if (tokenStarted) {
                        result += token.toString()
                        token.setLength(0)
                        tokenStarted = false
                    }
                }
                else -> {
                    token.append(char)
                    tokenStarted = true
                }
            }
            require(result.size <= MAX_ARGUMENTS) { "Templates can contain at most $MAX_ARGUMENTS arguments." }
        }
        require(!escaping) { "Template ends with an incomplete escape." }
        require(quote == null) { "Template contains an unmatched quote." }
        if (tokenStarted) result += token.toString()
        require(result.size <= MAX_ARGUMENTS) { "Templates can contain at most $MAX_ARGUMENTS arguments." }
        return result
    }

    fun isHttpUrl(value: String): Boolean {
        if (value.isBlank() || value.length > 16_384 || value.any { it.isWhitespace() || it.isISOControl() }) return false
        return runCatching {
        val uri = URI(value)
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }

    fun starterTemplate(): YtDlpCommandTemplate = YtDlpCommandTemplate(
        id = "vrka-safe-starter",
        name = "Safe starter",
        arguments = listOf("--no-playlist"),
    )

    fun deleteTemplate(
        templates: List<YtDlpCommandTemplate>,
        selectedTemplateId: String,
        deleteTemplateId: String,
    ): TemplateDeletionResult {
        val updated = templates.filterNot { it.id == deleteTemplateId }
            .ifEmpty { listOf(starterTemplate()) }
        val selected = if (selectedTemplateId == deleteTemplateId) updated.first().id else selectedTemplateId
        return TemplateDeletionResult(updated, selected)
    }
}
