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
) {
    fun toFormattedString(): String = buildString {
        appendLine("--- VRKA DIAGNOSTIC REPORT ---")
        appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestamp))}")
        appendLine("Job ID: $jobId")
        appendLine("Title: $title")
        appendLine("Stage: $stage")
        appendLine("Category: $failureCategory")
        appendLine("Quality: $quality")
        appendLine("Method: $acquisitionMethod")
        appendLine("URL: $url")
        appendLine("Summary: $summary")
        if (detail.isNotBlank()) {
            appendLine("Details:")
            appendLine(detail)
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
        val sanitized = entry.copy(
            url = sanitizeUrl(entry.url),
            summary = sanitizeText(entry.summary).take(MAX_SUMMARY_LENGTH),
            detail = sanitizeText(entry.detail).take(MAX_DETAIL_LENGTH),
        )
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

    private suspend fun load() {
        mutex.withLock {
            val loaded = loadFromDisk()
            _entries.value = loaded
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
                    )
                )
            }
            list.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
        }.getOrNull()

        if (androidParsed != null && androidParsed.isNotEmpty()) return androidParsed

        return parseEntriesFallback(text)
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
                    append("    \"acquisitionMethod\": ").append(quote(entry.acquisitionMethod)).append("\n")
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
        private const val FILE_NAME = "diagnostics.json"

        private val SENSITIVE_QUERY_PARAMS = setOf(
            "token", "key", "sig", "signature", "auth", "session",
            "pass", "secret", "expires", "access_token", "refresh_token",
            "code", "client_secret", "id_token",
        )

        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)Authorization:\s*Bearer\s+[^\r\n,;]+"""),
            Regex("""(?i)Bearer\s+[A-Za-z0-9\-_=.]+"""),
            Regex("""(?i)(cookie|set-cookie|authorization|proxy-authorization|x-api-key|api-key|apikey|token|signature|key|secret|password|passwd|pwd)\s*[:=]\s*[^\r\n,;]+"""),
            Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),
        )

        fun sanitizeUrl(rawUrl: String): String {
            if (rawUrl.isBlank()) return ""
            return runCatching {
                val trimmed = rawUrl.trim()
                val queryIndex = trimmed.indexOf('?')
                if (queryIndex < 0) return@runCatching trimmed
                val base = trimmed.substring(0, queryIndex)
                val query = trimmed.substring(queryIndex + 1)
                val pairs = query.split('&').map { pair ->
                    val eq = pair.indexOf('=')
                    if (eq < 0) {
                        val key = pair.lowercase()
                        if (SENSITIVE_QUERY_PARAMS.any { key.contains(it) }) "$pair=[REDACTED]" else pair
                    } else {
                        val key = pair.substring(0, eq)
                        val value = pair.substring(eq + 1)
                        val lowerKey = key.lowercase()
                        if (SENSITIVE_QUERY_PARAMS.any { lowerKey.contains(it) }) {
                            "$key=[REDACTED]"
                        } else {
                            "$key=$value"
                        }
                    }
                }
                "$base?${pairs.joinToString("&")}"
            }.getOrDefault(rawUrl.take(250))
        }

        fun sanitizeText(text: String): String {
            if (text.isBlank()) return ""
            var sanitized = HeaderValidation.redactSensitiveHeaderInText(text)
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
    }
}
