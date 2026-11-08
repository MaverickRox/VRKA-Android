package com.mvrk.vrka

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persisted diagnostic schema. `sourceUrl`, `candidateUrl` and `jobAttempt` are additive
 * optional fields: records written by older builds decode with empty/zero values, and the
 * URL fields are sanitized before any write.
 */
@Immutable
data class DiagnosticEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val jobId: String,
    val title: String,
    val stage: String,
    val failureCategory: String,
    val summary: String,
    val detail: String,
    val url: String,
    val quality: String,
    val acquisitionMethod: String,
    val runtime: String = "",
    /** Failure-only original submitted source URL (already privacy-sanitized). */
    val sourceUrl: String = "",
    /** Failure-only Browser Fallback candidate URL (sanitized). */
    val candidateUrl: String = "",
    val jobAttempt: Int = 0,
) {
    fun toFormattedString(includeSourceUrl: Boolean = false): String = buildString {
        appendLine("--- VRKA DIAGNOSTIC REPORT ---")
        appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestamp))}")
        appendLine("Job ID: $jobId")
        if (jobAttempt > 0) appendLine("Attempt: $jobAttempt")
        appendLine("Title: ${DiagnosticStore.sanitizeText(title).take(200)}")
        appendLine("Stage: $stage")
        appendLine("Category: $failureCategory")
        appendLine("Quality: $quality")
        appendLine("Method: $acquisitionMethod")
        if (runtime.isNotBlank()) appendLine("Runtime: ${DiagnosticStore.sanitizeText(runtime).take(160)}")
        // The full source URL is opt-in at export time. The safe URL remains the default.
        val displayedUrl = if (includeSourceUrl && sourceUrl.isNotBlank()) sourceUrl else url
        val displayedUrlSafe = if (includeSourceUrl && sourceUrl.isNotBlank()) {
            DiagnosticStore.sanitizeSourceUrl(displayedUrl)
        } else {
            DiagnosticStore.sanitizeUrl(displayedUrl)
        }
        appendLine("URL: $displayedUrlSafe")
        if (candidateUrl.isNotBlank()) {
            appendLine("Candidate: ${DiagnosticStore.sanitizeUrl(candidateUrl)}")
        }
        appendLine("Summary: ${DiagnosticStore.sanitizeText(summary)}")
        if (detail.isNotBlank()) {
            appendLine("Details:")
            appendLine(DiagnosticStore.sanitizeText(detail))
        }
        appendLine("------------------------------")
    }
}

class DiagnosticStore(
    private val filesDir: File,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(context.filesDir, scope)

    private val mutex = Mutex()
    private val file = File(filesDir, FILE_NAME)
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            load()
        }
    }

    suspend fun record(entry: DiagnosticEntry) {
        val sanitized = sanitizeEntry(entry)
        mutex.withLock {
            val current = loadFromDisk()
            val updated = (listOf(sanitized) + current.filterNot { it.id == sanitized.id })
                .take(MAX_ENTRIES)
            saveToDisk(updated)
            _entries.value = updated
        }
    }

    suspend fun clear() {
        mutex.withLock {
            if (file.exists()) {
                file.delete()
            }
            _entries.value = emptyList()
        }
    }

    /**
     * A retry that eventually succeeds must not keep a source URL from an earlier failed
     * attempt. The failure entry itself remains useful; only its URLs are removed.
     */
    suspend fun clearUrlsForJob(jobId: String) {
        if (jobId.isBlank()) return
        mutex.withLock {
            val current = loadFromDisk()
            var changed = false
            val updated = current.map { entry ->
                if (entry.jobId == jobId &&
                    (entry.url.isNotBlank() || entry.sourceUrl.isNotBlank() || entry.candidateUrl.isNotBlank())
                ) {
                    changed = true
                    entry.copy(url = "", sourceUrl = "", candidateUrl = "")
                } else {
                    entry
                }
            }
            if (changed) {
                saveToDisk(updated)
                _entries.value = updated
            }
        }
    }

    private suspend fun load() {
        mutex.withLock {
            val loaded = loadFromDisk()
            _entries.value = loaded
            // Rewrite legacy diagnostic files after sanitization so redacted URLs and
            // secrets do not remain recoverable in the persisted JSON.
            if (file.exists()) saveToDisk(loaded)
        }
    }

    private fun loadFromDisk(): List<DiagnosticEntry> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        if (text.isBlank() || text.trim() == "[]") return emptyList()

        val androidParsed = runCatching {
            val array = JSONArray(text)
            val list = ArrayList<DiagnosticEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DiagnosticEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        jobId = obj.optString("jobId", ""),
                        title = obj.optString("title", ""),
                        stage = obj.optString("stage", "Unknown"),
                        failureCategory = obj.optString("failureCategory", "UNKNOWN"),
                        summary = obj.optString("summary", ""),
                        detail = obj.optString("detail", ""),
                        url = obj.optString("url", ""),
                        quality = obj.optString("quality", ""),
                        acquisitionMethod = obj.optString("acquisitionMethod", "Native yt-dlp"),
                        runtime = obj.optString("runtime", ""),
                        sourceUrl = obj.optString("sourceUrl", ""),
                        candidateUrl = obj.optString("candidateUrl", ""),
                        jobAttempt = obj.optInt("jobAttempt", 0),
                    )
                )
            }
            list.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
        }.getOrNull()

        val loaded = androidParsed ?: parseEntriesFallback(text)
        return loaded.map(::sanitizeEntry).sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
    }

    private fun sanitizeEntry(entry: DiagnosticEntry): DiagnosticEntry {
        // Browser-fallback failures used to keep the exact URL for diagnosis. Signed media
        // URLs carry temporary credentials in their query (and sometimes in a path segment),
        // so every entry is sanitized before it is persisted or exported.
        return entry.copy(
            title = sanitizeText(entry.title).take(200),
            url = sanitizeUrl(entry.url),
            sourceUrl = if (entry.sourceUrl.isBlank()) "" else sanitizeSourceUrl(entry.sourceUrl),
            candidateUrl = if (entry.candidateUrl.isBlank()) "" else sanitizeUrl(entry.candidateUrl),
            summary = sanitizeText(entry.summary).take(MAX_SUMMARY_LENGTH),
            detail = sanitizeText(entry.detail).take(MAX_DETAIL_LENGTH),
            runtime = sanitizeText(entry.runtime).take(160),
        )
    }

    private fun parseEntriesFallback(text: String): List<DiagnosticEntry> {
        val entries = mutableListOf<DiagnosticEntry>()
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(text)) {
            val objBody = match.groupValues[1]
            fun extractString(key: String): String {
                val matchKey = Regex("""\"$key\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""").find(objBody)
                return matchKey?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.replace("\\n", "\n")
                    ?.replace("\\r", "\r")
                    ?.replace("\\t", "\t")
                    .orEmpty()
            }
            fun extractLong(key: String, default: Long): Long {
                val matchKey = Regex("""\"$key\"\s*:\s*(\d+)""").find(objBody)
                return matchKey?.groupValues?.get(1)?.toLongOrNull() ?: default
            }

            val id = extractString("id").ifBlank { UUID.randomUUID().toString() }
            val timestamp = extractLong("timestamp", System.currentTimeMillis())
            val jobId = extractString("jobId")
            val title = extractString("title")
            val stage = extractString("stage").ifBlank { "Unknown" }
            val failureCategory = extractString("failureCategory").ifBlank { "UNKNOWN" }
            val summary = extractString("summary")
            val detail = extractString("detail")
            val url = extractString("url")
            val quality = extractString("quality")
            val acquisitionMethod = extractString("acquisitionMethod").ifBlank { "Native yt-dlp" }
            val runtime = extractString("runtime")
            val sourceUrl = extractString("sourceUrl")
            val candidateUrl = extractString("candidateUrl")
            val jobAttempt = extractLong("jobAttempt", 0L).toInt()

            entries.add(
                DiagnosticEntry(
                    id = id,
                    timestamp = timestamp,
                    jobId = jobId,
                    title = title,
                    stage = stage,
                    failureCategory = failureCategory,
                    summary = summary,
                    detail = detail,
                    url = url,
                    quality = quality,
                    acquisitionMethod = acquisitionMethod,
                    runtime = runtime,
                    sourceUrl = sourceUrl,
                    candidateUrl = candidateUrl,
                    jobAttempt = jobAttempt,
                )
            )
        }
        return entries.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
    }

    private fun saveToDisk(entries: List<DiagnosticEntry>) {
        runCatching {
            val jsonText = entries.take(MAX_ENTRIES).joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n]",
            ) { entry ->
                buildString {
                    append("  {\n")
                    append("    \"id\": ").append(quote(entry.id)).append(",\n")
                    append("    \"timestamp\": ").append(entry.timestamp).append(",\n")
                    append("    \"jobId\": ").append(quote(entry.jobId)).append(",\n")
                    append("    \"title\": ").append(quote(entry.title.take(200))).append(",\n")
                    append("    \"stage\": ").append(quote(entry.stage)).append(",\n")
                    append("    \"failureCategory\": ").append(quote(entry.failureCategory)).append(",\n")
                    append("    \"summary\": ").append(quote(entry.summary.take(MAX_SUMMARY_LENGTH))).append(",\n")
                    append("    \"detail\": ").append(quote(entry.detail.take(MAX_DETAIL_LENGTH))).append(",\n")
                    append("    \"url\": ").append(quote(entry.url)).append(",\n")
                    append("    \"quality\": ").append(quote(entry.quality)).append(",\n")
                    append("    \"acquisitionMethod\": ").append(quote(entry.acquisitionMethod)).append(",\n")
                    append("    \"runtime\": ").append(quote(entry.runtime)).append(",\n")
                    append("    \"sourceUrl\": ").append(quote(entry.sourceUrl)).append(",\n")
                    append("    \"candidateUrl\": ").append(quote(entry.candidateUrl)).append(",\n")
                    append("    \"jobAttempt\": ").append(entry.jobAttempt).append("\n")
                    append("  }")
                }
            }
            val tempFile = File(filesDir, "$FILE_NAME.tmp")
            tempFile.writeText(jsonText, Charsets.UTF_8)
            if (tempFile.exists()) {
                val tempPath = tempFile.toPath()
                val destPath = file.toPath()
                runCatching {
                    java.nio.file.Files.move(
                        tempPath,
                        destPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                }.recoverCatching {
                    java.nio.file.Files.move(
                        tempPath,
                        destPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }.onFailure {
                    file.delete()
                    tempFile.renameTo(file)
                }
            }
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    companion object {
        const val MAX_ENTRIES = 50
        const val MAX_SUMMARY_LENGTH = 500
        const val MAX_DETAIL_LENGTH = 4000
        const val BROWSER_FALLBACK_METHOD = "Browser Fallback"
        private const val FILE_NAME = "diagnostics.json"

        private val SAFE_QUERY_PARAMS = setOf(
            "v", "t", "list", "index", "start", "end", "page", "lang",
            "quality", "itag", "format", "width", "height", "fps", "type", "mime",
        )

        private val SENSITIVE_KEYWORDS = listOf(
            "token", "auth", "signature", "sign", "key", "session", "policy",
            "credential", "secret", "password", "hmac", "sig", "jwt", "bearer",
            "expire", "ipbits",
        )

        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)Authorization:\s*Bearer\s+[^\r\n,;]+"""),
            Regex("""(?i)Bearer\s+[A-Za-z0-9\-_=.]+"""),
            Regex("""(?i)(cookie|set-cookie|authorization|proxy-authorization|x-api-key|api-key|apikey|token|signature|key|secret|password|passwd|pwd)\s*[:=]\s*[^\r\n,;&]+"""),
            Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),
        )

        fun sanitizeUrl(rawUrl: String): String =
            sanitizeUrlInternal(rawUrl, onlyKnownSafeParams = true)

        /**
         * Failure-only source URLs may retain useful non-sensitive query parameters (for
         * example a video id) while credential-like names are still redacted.
         */
        fun sanitizeSourceUrl(rawUrl: String): String =
            sanitizeUrlInternal(rawUrl, onlyKnownSafeParams = false)

        private fun sanitizeUrlInternal(rawUrl: String, onlyKnownSafeParams: Boolean): String {
            if (rawUrl.isBlank()) return ""
            return runCatching {
                val uri = java.net.URI(rawUrl.trim())
                val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
                    ?.takeIf { it == "http" || it == "https" }
                    ?: return@runCatching "[private URL]"
                var host = uri.host?.lowercase(java.util.Locale.ROOT)
                    ?.takeIf(String::isNotBlank)
                    ?: return@runCatching "[private URL]"
                if (':' in host && !host.startsWith("[")) host = "[$host]"
                val port = uri.port.takeIf { it in 1..65535 && it != (if (scheme == "https") 443 else 80) }
                    ?.let { ":$it" }.orEmpty()
                val origin = "$scheme://$host$port"
                val path = sanitizePath(uri.rawPath)
                val query = uri.rawQuery ?: return@runCatching "$origin$path"
                val pairs = query.split('&').map { pair ->
                    val separator = pair.indexOf('=')
                    val rawKey = if (separator < 0) pair else pair.substring(0, separator)
                    val key = decodedQueryKey(rawKey)
                    val preserve = if (onlyKnownSafeParams) {
                        key in SAFE_QUERY_PARAMS && !isSensitiveKey(key)
                    } else {
                        !isSensitiveKey(key)
                    }
                    if (preserve) pair else "$rawKey=[REDACTED]"
                }
                "$origin$path?${pairs.joinToString("&")}"
            }.getOrDefault("[private URL]")
        }

        /**
         * Preserves the route so diagnostics remain actionable, but redacts segments that
         * carry credentials (for example `/signature/...` or a long opaque token).
         */
        private fun sanitizePath(rawPath: String?): String {
            if (rawPath.isNullOrBlank()) return "/"
            val segments = rawPath.split('/').map { segment ->
                val decoded = runCatching {
                    java.net.URLDecoder.decode(segment, "UTF-8")
                }.getOrDefault(segment)
                when {
                    isSensitiveKey(decoded) -> "[REDACTED]"
                    looksLikeOpaqueToken(decoded) -> "[REDACTED]"
                    else -> segment
                }
            }
            return segments.joinToString("/").ifBlank { "/" }
        }

        private fun decodedQueryKey(rawKey: String): String {
            if (rawKey.isBlank()) return ""
            return runCatching {
                java.net.URLDecoder.decode(rawKey, "UTF-8")
            }.getOrDefault(rawKey).lowercase(java.util.Locale.ROOT)
        }

        private fun isSensitiveKey(key: String): Boolean {
            val lower = key.lowercase(java.util.Locale.ROOT)
            return SENSITIVE_KEYWORDS.any(lower::contains)
        }

        private fun looksLikeOpaqueToken(value: String): Boolean {
            if (value.length < 32) return false
            val tokenChars = value.count { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            return tokenChars.toDouble() / value.length >= 0.9 && value.any(Char::isDigit)
        }

        fun sanitizeText(text: String): String {
            if (text.isBlank()) return ""
            var sanitized = HeaderValidation.redactSensitiveHeaderInText(text)
            sanitized = sanitizeEmbeddedUrls(sanitized)
            for (pattern in SECRET_PATTERNS) {
                sanitized = sanitized.replace(pattern) { match ->
                    val matchedText = match.value
                    if (matchedText.contains(":") || matchedText.contains("=")) {
                        val prefix = matchedText.substringBefore(':').substringBefore('=')
                        val delimiter = if (matchedText.contains(':')) ":" else "="
                        "$prefix$delimiter [REDACTED]"
                    } else {
                        "[REDACTED]"
                    }
                }
            }
            return sanitized
        }

        private val EMBEDDED_URL = Regex("""(?i)https?:\\?/\\?/[^\s"'<>]+""")

        /**
         * Replaces every URL embedded in prose, JSON-like text or an error tail with its
         * sanitized form. yt-dlp and FFmpeg errors frequently contain signed CDN URLs whose
         * query carries temporary credentials.
         */
        private fun sanitizeEmbeddedUrls(text: String): String = EMBEDDED_URL.replace(text) { match ->
            val normalized = match.value
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
            val trailing = normalized.takeLastWhile { it in ".,;:)]}" }
            val core = normalized.dropLast(trailing.length)
            sanitizeUrl(core) + trailing
        }
    }
}
