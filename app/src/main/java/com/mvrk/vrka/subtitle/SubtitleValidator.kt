package com.mvrk.vrka.subtitle

import java.io.File
import java.util.regex.Pattern

sealed class SubtitleValidationResult {
    data class Valid(
        val format: String,
        val cueCount: Int,
        val detectedLanguage: String? = null,
    ) : SubtitleValidationResult()

    data class Invalid(val reason: String) : SubtitleValidationResult()
}

/**
 * Validates and converts subtitle files for VRKA.
 *
 * Guarantees that corrupted downloads, HTML challenge/error responses,
 * JSON error objects, and empty files are never treated as valid subtitles.
 */
object SubtitleValidator {

    private val VTT_TIMESTAMP_PATTERN = Pattern.compile(
        """(\d{2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(\d{2}:)?\d{2}:\d{2}\.\d{3}"""
    )
    private val SRT_TIMESTAMP_PATTERN = Pattern.compile(
        """\d{2}:\d{2}:\d{2}[,\.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,\.]\d{3}"""
    )
    private val ASS_EVENT_PATTERN = Pattern.compile(
        """^Dialogue:\s*\d+,\s*(\d+:\d{2}:\d{2}\.\d{2}),\s*(\d+:\d{2}:\d{2}\.\d{2})""",
        Pattern.MULTILINE or Pattern.CASE_INSENSITIVE
    )

    fun validate(file: File): SubtitleValidationResult {
        if (!file.exists() || !file.isFile) {
            return SubtitleValidationResult.Invalid("File does not exist or is not a regular file")
        }
        val length = file.length()
        if (length < 20L) {
            return SubtitleValidationResult.Invalid("File is too small (${length} bytes) to contain valid subtitles")
        }
        if (length > 10 * 1024 * 1024L) {
            return SubtitleValidationResult.Invalid("File is excessively large (${length} bytes) for a subtitle resource")
        }

        val content = runCatching {
            // Read first 256KB for validation to be memory-safe on large files
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(262144)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
        }.getOrElse {
            return SubtitleValidationResult.Invalid("Could not read file: ${it.message}")
        }

        return validateContent(content, file.extension)
    }

    fun validateContent(content: String, fileExtension: String = ""): SubtitleValidationResult {
        val trimmed = content.trim()
        if (trimmed.length < 20) {
            return SubtitleValidationResult.Invalid("Content is too short to be a valid subtitle")
        }

        val lower = trimmed.lowercase()

        // 1. Reject HTML pages (Cloudflare challenges, 403/404/500 error pages, login walls)
        if (lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.contains("<head>") ||
            lower.contains("<body>") ||
            lower.contains("<title>access denied</title>") ||
            lower.contains("<title>just a moment...</title>") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("403 forbidden")
        ) {
            return SubtitleValidationResult.Invalid("Rejected HTML response/challenge page saved as subtitle")
        }

        // 2. Reject JSON error payloads
        if (trimmed.startsWith("{") && (
                lower.contains("\"error\"") ||
                lower.contains("\"status\": 429") ||
                lower.contains("\"status\":429") ||
                lower.contains("\"message\"")
            )
        ) {
            return SubtitleValidationResult.Invalid("Rejected JSON error response saved as subtitle")
        }

        // 3. Format detection and timestamp / cue validation
        val ext = fileExtension.lowercase().removePrefix(".")

        // WebVTT
        if (ext == "vtt" || trimmed.startsWith("WEBVTT") || lower.contains("webvtt")) {
            val matcher = VTT_TIMESTAMP_PATTERN.matcher(trimmed)
            var cueCount = 0
            while (matcher.find()) {
                cueCount++
            }
            if (cueCount > 0) {
                return SubtitleValidationResult.Valid(
                    format = "vtt",
                    cueCount = cueCount,
                    detectedLanguage = extractLanguageFromHeader(trimmed),
                )
            }
        }

        // SubRip (SRT)
        val srtMatcher = SRT_TIMESTAMP_PATTERN.matcher(trimmed)
        var srtCues = 0
        while (srtMatcher.find()) {
            srtCues++
        }
        if (srtCues > 0) {
            return SubtitleValidationResult.Valid(
                format = "srt",
                cueCount = srtCues,
            )
        }

        // ASS / SSA
        if (ext in setOf("ass", "ssa") || lower.contains("[script info]") || lower.contains("[events]")) {
            val assMatcher = ASS_EVENT_PATTERN.matcher(trimmed)
            var assCues = 0
            while (assMatcher.find()) {
                assCues++
            }
            if (assCues > 0) {
                return SubtitleValidationResult.Valid(
                    format = if (ext.isNotEmpty()) ext else "ass",
                    cueCount = assCues,
                )
            }
        }

        // TTML / XML
        if ((ext in setOf("ttml", "xml") || trimmed.startsWith("<")) && lower.contains("<tt") && (lower.contains("begin=") || lower.contains("<p "))) {
            val pCount = countOccurrences(lower, "<p ")
            if (pCount > 0) {
                return SubtitleValidationResult.Valid(
                    format = "ttml",
                    cueCount = pCount,
                )
            }
        }

        return SubtitleValidationResult.Invalid("Content does not contain valid subtitle cues or supported timestamps")
    }

    private fun extractLanguageFromHeader(vtt: String): String? {
        val lines = vtt.lines().take(20)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Language:", ignoreCase = true)) {
                return trimmed.substringAfter(":").trim().ifBlank { null }
            }
        }
        return null
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (idx < text.length) {
            val next = text.indexOf(sub, idx)
            if (next == -1) break
            count++
            idx = next + sub.length
        }
        return count
    }

    /**
     * Converts clean WebVTT content to SubRip (SRT) format.
     * Replaces millisecond dots with commas, strips WEBVTT header and cue styling,
     * and generates sequential 1-based cue indices.
     */
    fun vttToSrt(vttContent: String): String {
        val lines = vttContent.lines()
        val srtBuilder = StringBuilder()

        var cueIndex = 1
        var inHeader = true
        var expectingText = false
        val currentCueLines = mutableListOf<String>()
        var currentTimestamps: String? = null

        fun flushCue() {
            if (currentTimestamps != null && currentCueLines.isNotEmpty()) {
                srtBuilder.append(cueIndex++).append("\r\n")
                srtBuilder.append(currentTimestamps).append("\r\n")
                currentCueLines.forEach { line ->
                    srtBuilder.append(stripVttTags(line)).append("\r\n")
                }
                srtBuilder.append("\r\n")
            }
            currentCueLines.clear()
            currentTimestamps = null
            expectingText = false
        }

        for (rawLine in lines) {
            val line = rawLine.trim()

            if (inHeader) {
                if (line.isEmpty()) {
                    inHeader = false
                }
                continue
            }

            // Check if this line is a timestamp line: HH:MM:SS.mmm --> HH:MM:SS.mmm or MM:SS.mmm --> MM:SS.mmm
            if (line.contains("-->")) {
                flushCue()

                // Normalize timestamp: MM:SS.mmm -> 00:MM:SS.mmm, replace . with ,
                val parts = line.split("-->").map(String::trim)
                if (parts.size >= 2) {
                    val start = formatSrtTimestamp(parts[0].substringBefore(" "))
                    val end = formatSrtTimestamp(parts[1].substringBefore(" "))
                    currentTimestamps = "$start --> $end"
                    expectingText = true
                    continue
                }
            }

            if (expectingText) {
                if (line.isEmpty()) {
                    flushCue()
                } else if (!line.startsWith("NOTE") && !line.startsWith("STYLE")) {
                    currentCueLines.add(line)
                }
            }
        }
        flushCue()

        return srtBuilder.toString().trim()
    }

    private fun formatSrtTimestamp(ts: String): String {
        val clean = ts.trim()
        val timeWithComma = clean.replace('.', ',')
        val timeParts = timeWithComma.substringBefore(',').split(':')
        val millis = timeWithComma.substringAfter(',', "000").padEnd(3, '0').take(3)

        val formattedTime = when (timeParts.size) {
            2 -> "00:${timeParts[0].padStart(2, '0')}:${timeParts[1].padStart(2, '0')}"
            3 -> "${timeParts[0].padStart(2, '0')}:${timeParts[1].padStart(2, '0')}:${timeParts[2].padStart(2, '0')}"
            else -> "00:00:00"
        }
        return "$formattedTime,$millis"
    }

    private fun stripVttTags(text: String): String {
        return text.replace(Regex("""<[^>]*>"""), "").trim()
    }
}
