package com.mvrk.vrka.subtitle

import android.content.Context
import android.util.Log
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequestFactory
import com.mvrk.vrka.HeaderValidation
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.engine.MediaAssembly
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class SubtitleResult {
    data class Success(
        val subtitleFile: File,
        val format: String,
        val language: String,
        val isEmbedded: Boolean = false,
    ) : SubtitleResult()

    data class Failed(
        val reason: String,
        val httpStatus: Int? = null,
        val isRateLimited: Boolean = false,
    ) : SubtitleResult()

    data object Skipped : SubtitleResult()
}

/**
 * Standalone subtitle helper code that is not wired into the production download flow.
 * Normal subtitle acquisition is handled by yt-dlp request options. Browser Fallback
 * currently does not fetch subtitles.
 */
class VrkaSubtitleManager(private val context: Context? = null) {

    private val _events = MutableStateFlow<List<SubtitleEvent>>(emptyList())
    val events: StateFlow<List<SubtitleEvent>> = _events.asStateFlow()

    fun recordEvent(event: SubtitleEvent) {
        Log.i(
            TAG,
            "[SUBTITLE EVENT] ${event.type} - ${event.format} (${event.language}): " +
                "${com.mvrk.vrka.DiagnosticStore.sanitizeText(event.detail)} " +
                "[${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(event.url)}]",
        )
        _events.value = (_events.value + event).takeLast(50)
    }

    /**
     * Resolves clean, non-wildcard language tags to prevent YouTube from returning
     * 50+ auto-translated streams that trigger HTTP 429 rate limiting.
     */
    fun resolveSubtitleLanguages(requested: String, automaticCaptions: Boolean): String {
        val clean = requested.trim()
        val baseLangs = if (clean.isBlank() || clean == "en.*" || clean == ".*") {
            listOf("en")
        } else {
            clean.split(",")
                .map { it.trim().removeSuffix(".*").removeSuffix("*") }
                .filter { it.isNotBlank() }
                .distinct()
        }

        return if (automaticCaptions) {
            val variants = mutableListOf<String>()
            baseLangs.forEach { lang ->
                variants.add(lang)
                variants.add("$lang-orig")
            }
            variants.distinct().joinToString(",")
        } else {
            baseLangs.joinToString(",")
        }
    }

    /**
     * Downloads subtitles independently using native yt-dlp with conservative delay
     * and independent retry on HTTP 429. Never crashes or aborts the parent media job.
     */
    suspend fun downloadSubtitlesDirect(
        job: DownloadJob,
        stagingDirectory: File,
    ): SubtitleResult = withContext(Dispatchers.IO) {
        val options = job.request
        if (!options.downloadSubtitles) return@withContext SubtitleResult.Skipped

        val sourceUrl = options.resolvedMediaUrl ?: options.url
        val baseManual = resolveSubtitleLanguages(options.subtitleLanguages, automaticCaptions = false)
        val manualLangs = if (baseManual == "en") {
            "en.*,en,en-US,en-GB"
        } else {
            baseManual.split(",").flatMap { listOf("$it.*", it) }.distinct().joinToString(",")
        }

        recordEvent(
            SubtitleEvent(
                type = SubtitleEventType.SUBTITLE_DISCOVERED,
                url = sourceUrl,
                language = manualLangs,
                format = "srt/vtt",
                source = "native_yt-dlp",
                detail = "Checking manual subtitles ($manualLangs)",
            )
        )

        // Step 1: Query manual/creator subtitles only (without --write-auto-subs)
        var result = executeYtdlpSubtitle(
            job = job,
            sourceUrl = sourceUrl,
            stagingDirectory = stagingDirectory,
            languages = manualLangs,
            autoSubs = false,
            sleepSeconds = 1,
        )

        if (result is SubtitleResult.Success) {
            return@withContext result
        }

        // If rate limited on step 1, pause briefly before retrying
        if (result is SubtitleResult.Failed && result.isRateLimited) {
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_FAILED,
                    url = sourceUrl,
                    language = manualLangs,
                    errorCategory = "RATE_LIMITED",
                    httpStatus = 429,
                    detail = "Subtitle request was rate-limited (HTTP 429). Retrying after pause...",
                )
            )
            delay(2000L)
            result = executeYtdlpSubtitle(
                job = job,
                sourceUrl = sourceUrl,
                stagingDirectory = stagingDirectory,
                languages = "en,en-US",
                autoSubs = false,
                sleepSeconds = 2,
            )
            if (result is SubtitleResult.Success) {
                return@withContext result
            }
        }

        // Step 2: If no manual subtitles found and user enabled automaticCaptions, try auto captions
        if (options.automaticCaptions && !(result is SubtitleResult.Failed && result.isRateLimited)) {
            val autoLangs = resolveSubtitleLanguages(options.subtitleLanguages, automaticCaptions = true)
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_DOWNLOADING,
                    url = sourceUrl,
                    language = autoLangs,
                    source = "native_yt-dlp",
                    detail = "Manual subtitles not found; attempting automatic captions ($autoLangs)",
                )
            )
            delay(1000L)
            result = executeYtdlpSubtitle(
                job = job,
                sourceUrl = sourceUrl,
                stagingDirectory = stagingDirectory,
                languages = autoLangs,
                autoSubs = true,
                sleepSeconds = 2,
            )
        }

        result
    }

    private fun executeYtdlpSubtitle(
        job: DownloadJob,
        sourceUrl: String,
        stagingDirectory: File,
        languages: String,
        autoSubs: Boolean,
        sleepSeconds: Int,
    ): SubtitleResult {
        recordEvent(
            SubtitleEvent(
                type = SubtitleEventType.SUBTITLE_DOWNLOADING,
                url = sourceUrl,
                language = languages,
                source = "native_yt-dlp",
                detail = "Executing subtitle extraction (sleep=$sleepSeconds s, autoSubs=$autoSubs)",
            )
        )

        val subRequest = YoutubeDLRequest(sourceUrl).apply {
            addOption("--newline")
            addOption("--no-playlist")
            addOption("--legacy-server-connect")
            addOption("--skip-download")
            addOption("--write-subs")
            if (autoSubs) addOption("--write-auto-subs")
            addOption("--sub-langs", languages)
            addOption("--sub-format", "vtt/srt/best")
            val ffmpegBin = context?.let { File(it.applicationInfo.nativeLibraryDir, "libffmpeg.so") }
            if (ffmpegBin != null && ffmpegBin.exists()) {
                addOption("--ffmpeg-location", ffmpegBin.absolutePath)
            }
            addOption("--sleep-subtitles", sleepSeconds.toString())
            addOption("-o", File(stagingDirectory, "media.%(ext)s").absolutePath)

            val qjs = DownloadRequestFactory.getQuickJsPath()
            if (qjs != null) {
                addOption("--js-runtimes", "quickjs:$qjs")
            }
            // The bundled yt_dlp_ejs makes `--remote-components ejs:github` redundant; keeping it
            // added a GitHub round-trip to every subtitle extraction.
            DownloadRequestFactory.applyCacheDir(this)
            addOption("--extractor-args", "youtube:player_client=android,web")

            val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(job.request)
            effectiveHeaders.forEach { (k, v) ->
                addOption("--add-header", "$k:$v")
            }
            if (effectiveHeaders.keys.none { it.equals("User-Agent", true) }) {
                addOption("--user-agent", DownloadRequestFactory.DESKTOP_USER_AGENT)
            }
        }

        val outputLines = mutableListOf<String>()
        val response = runCatching {
            YoutubeDL.getInstance().execute(subRequest, "${job.id}_sub") { _, _, line ->
                outputLines.add(line)
                if (outputLines.size > 100) outputLines.removeAt(0)
            }
        }
        val outCombined = (outputLines + listOfNotNull(response.getOrNull()?.out, response.getOrNull()?.err)).joinToString("\n")
        Log.i(
            TAG,
            "yt-dlp subtitle output (${com.mvrk.vrka.DiagnosticStore.sanitizeUrl(sourceUrl)}):\n" +
                com.mvrk.vrka.DiagnosticStore.sanitizeText(outCombined),
        )

        val foundFiles = stagingDirectory.walkTopDown().filter { file ->
            file.isFile && (
                file.name.endsWith(".srt", ignoreCase = true) ||
                file.name.endsWith(".vtt", ignoreCase = true) ||
                file.name.endsWith(".ass", ignoreCase = true)
            )
        }.toList()

        if (foundFiles.isNotEmpty()) {
            val bestFile = foundFiles.maxByOrNull { it.length() } ?: foundFiles.first()
            val validation = SubtitleValidator.validate(bestFile)
            if (validation is SubtitleValidationResult.Valid) {
                recordEvent(
                    SubtitleEvent(
                        type = SubtitleEventType.SUBTITLE_DOWNLOADED,
                        url = sourceUrl,
                        language = validation.detectedLanguage ?: languages,
                        format = validation.format,
                        filename = bestFile.name,
                        source = "native_yt-dlp",
                        detail = "Validated ${validation.cueCount} subtitle cues (${bestFile.length()} bytes)",
                    )
                )
                return SubtitleResult.Success(
                    subtitleFile = bestFile,
                    format = validation.format,
                    language = validation.detectedLanguage ?: languages.substringBefore(","),
                )
            } else {
                val reason = (validation as SubtitleValidationResult.Invalid).reason
                bestFile.delete()
                return SubtitleResult.Failed("Subtitle file rejected by validator: $reason")
            }
        }

        val rawErr = response.exceptionOrNull()?.message ?: response.getOrNull()?.err.orEmpty()
        val err = if (rawErr.isNotBlank()) {
            rawErr
        } else {
            response.getOrNull()?.out.orEmpty().takeLast(300).ifBlank {
                outputLines.takeLast(10).joinToString(" ")
            }
        }
        val is429 = err.contains("429") || err.contains("Too Many Requests", ignoreCase = true)
        recordEvent(
            SubtitleEvent(
                type = SubtitleEventType.SUBTITLE_FAILED,
                url = sourceUrl,
                language = languages,
                errorCategory = if (is429) "RATE_LIMITED" else "DOWNLOAD_ERROR",
                httpStatus = if (is429) 429 else null,
                detail = if (is429) "HTTP 429 Too Many Requests on subtitle endpoint" else "No subtitle files produced ($err)",
            )
        )
        return SubtitleResult.Failed(
            reason = if (is429) "HTTP 429 Too Many Requests" else "Subtitle download failed: $err",
            httpStatus = if (is429) 429 else null,
            isRateLimited = is429,
        )
    }

    /**
     * Dormant helper for a browser-discovered subtitle; no production detector or
     * download path currently calls this method.
     */
    suspend fun downloadBrowserSubtitle(
        candidate: BrowserSubtitleCandidate,
        stagingDirectory: File,
    ): SubtitleResult = withContext(Dispatchers.IO) {
        recordEvent(
            SubtitleEvent(
                type = SubtitleEventType.SUBTITLE_DOWNLOADING,
                url = candidate.url,
                language = candidate.language,
                format = candidate.format,
                source = candidate.source,
                detail = "Downloading browser-discovered subtitle",
            )
        )

        val targetExt = candidate.format.trim().lowercase(java.util.Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .ifBlank { "vtt" }
        val targetFile = File(stagingDirectory, "browser_sub.$targetExt")

        val fetchResult = runCatching {
            fetchBrowserSubtitleWithScopedRedirects(candidate, targetFile)
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            targetFile.delete()
        }

        val (statusCode, errorMsg) = fetchResult.getOrElse {
            return@withContext SubtitleResult.Failed("Connection failed: ${it.message}")
        }

        if (errorMsg != null || !targetFile.exists() || targetFile.length() == 0L) {
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_FAILED,
                    url = candidate.url,
                    language = candidate.language,
                    format = candidate.format,
                    httpStatus = statusCode,
                    detail = errorMsg ?: "Empty response",
                )
            )
            return@withContext SubtitleResult.Failed(errorMsg ?: "Empty response", httpStatus = statusCode)
        }

        val validation = SubtitleValidator.validate(targetFile)
        if (validation is SubtitleValidationResult.Valid) {
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_DOWNLOADED,
                    url = candidate.url,
                    language = candidate.language,
                    format = validation.format,
                    filename = targetFile.name,
                    source = candidate.source,
                    detail = "Validated ${validation.cueCount} subtitle cues from browser player",
                )
            )
            SubtitleResult.Success(
                subtitleFile = targetFile,
                format = validation.format,
                language = candidate.language.ifBlank { "eng" },
            )
        } else {
            val reason = (validation as SubtitleValidationResult.Invalid).reason
            targetFile.delete()
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_FAILED,
                    url = candidate.url,
                    language = candidate.language,
                    format = candidate.format,
                    detail = "Browser subtitle failed validation: $reason",
                )
            )
            SubtitleResult.Failed("Browser subtitle validation failed: $reason")
        }
    }

    /**
     * Embeds a validated subtitle file into a target video container using FFmpeg.
     * Converts WebVTT to SRT for MP4 (mov_text) if required.
     */
    suspend fun embedSubtitleIntoVideo(
        videoFile: File,
        subtitleFile: File,
        language: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !subtitleFile.exists()) return@withContext false

        recordEvent(
            SubtitleEvent(
                type = SubtitleEventType.SUBTITLE_CONVERTING,
                url = videoFile.name,
                language = language,
                format = subtitleFile.extension,
                filename = subtitleFile.name,
                detail = "Preparing subtitle for container embedding (${videoFile.extension})",
            )
        )

        // If target container is MP4 and subtitle is VTT, convert to SRT for clean mov_text muxing
        val effectiveSubFile = if (videoFile.extension.equals("mp4", ignoreCase = true) &&
            subtitleFile.extension.equals("vtt", ignoreCase = true)
        ) {
            val vttText = subtitleFile.readText(Charsets.UTF_8)
            val srtText = SubtitleValidator.vttToSrt(vttText)
            val srtFile = File(subtitleFile.parentFile, "${subtitleFile.nameWithoutExtension}.srt")
            srtFile.writeText(srtText, Charsets.UTF_8)
            srtFile
        } else {
            subtitleFile
        }

        val ctx = context ?: return@withContext false
        val embeddedOutput = File(videoFile.parentFile, "embedded_${videoFile.name}")
        val embedCmd = MediaAssembly.buildSubtitleEmbedCommand(
            context = ctx,
            videoFile = videoFile,
            subtitleFile = effectiveSubFile,
            outputFile = embeddedOutput,
            language = convertIso2ToIso3(language),
        )

        val success = runCatching {
            MediaAssembly.executeFfmpeg(embedCmd, ctx)
        }.getOrDefault(false)

        val presenceVerified = if (success && embeddedOutput.exists() && embeddedOutput.length() > 0) {
            MediaAssembly.verifySubtitlePresence(embeddedOutput, ctx)
        } else false

        if (success && embeddedOutput.exists() && embeddedOutput.length() > 0 && presenceVerified) {
            videoFile.delete()
            effectiveSubFile.delete()
            if (effectiveSubFile != subtitleFile) subtitleFile.delete()
            val renamed = embeddedOutput.renameTo(videoFile)
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_EMBED_VERIFIED,
                    url = videoFile.name,
                    language = language,
                    format = effectiveSubFile.extension,
                    filename = videoFile.name,
                    detail = "Subtitle stream verified and embedded into container",
                )
            )
            renamed
        } else {
            embeddedOutput.delete()
            recordEvent(
                SubtitleEvent(
                    type = SubtitleEventType.SUBTITLE_FAILED,
                    url = videoFile.name,
                    language = language,
                    errorCategory = "EMBED_FAILED",
                    detail = if (!success) "FFmpeg subtitle embedding failed" else "Verification failed: output container does not contain subtitle stream",
                )
            )
            false
        }
    }

    fun convertIso2ToIso3(rawCode: String): String {
        val clean = rawCode.trim().lowercase().split("-", "_")[0]
        return when (clean) {
            "en" -> "eng"
            "es" -> "spa"
            "fr" -> "fra"
            "de" -> "deu"
            "ja" -> "jpn"
            "zh" -> "zho"
            "ru" -> "rus"
            "it" -> "ita"
            "pt" -> "por"
            "ko" -> "kor"
            "hi" -> "hin"
            "cs" -> "ces"
            else -> if (rawCode.length == 3) rawCode.lowercase() else "eng"
        }
    }

    private fun fetchBrowserSubtitleWithScopedRedirects(
        candidate: BrowserSubtitleCandidate,
        targetFile: File,
    ): Pair<Int, String?> {
        com.mvrk.vrka.HeaderValidation.originOf(candidate.url)
            ?: throw IllegalArgumentException("Rejected invalid or non-HTTP(S) subtitle URL")

        val initialHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        initialHeaders.putAll(
            com.mvrk.vrka.HeaderValidation.parseHeaderPairs(candidate.headers.entries.map { it.key to it.value })
                .filterKeys { it.lowercase(java.util.Locale.ROOT) !in FORBIDDEN_TRANSPORT_HEADERS }
        )
        if (!initialHeaders.containsKey("User-Agent")) {
            initialHeaders["User-Agent"] = DownloadRequestFactory.DESKTOP_USER_AGENT
        }
        if (!initialHeaders.containsKey("Referer") && candidate.pageUrl.isNotBlank()) {
            val validReferer = com.mvrk.vrka.HeaderValidation.originOf(candidate.pageUrl) != null &&
                com.mvrk.vrka.HeaderValidation.validateHeaderValue(candidate.pageUrl) is com.mvrk.vrka.HeaderValidationResult.Valid &&
                !(candidate.pageUrl.startsWith("https://", ignoreCase = true) &&
                    candidate.url.startsWith("http://", ignoreCase = true))
            if (validReferer) {
                initialHeaders["Referer"] = candidate.pageUrl.trim()
            }
        }

        var currentUrl = candidate.url
        var activeHeaders: Map<String, String> = initialHeaders
        var credentialsAllowed = true

        for (redirectCount in 0..MAX_BROWSER_SUBTITLE_REDIRECTS) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                activeHeaders.forEach { (k, v) ->
                    connection.setRequestProperty(k, v)
                }

                connection.connect()
                val code = connection.responseCode
                if (code in REDIRECT_STATUS_CODES) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        return code to "HTTP $code redirect missing Location header"
                    }
                    if (redirectCount == MAX_BROWSER_SUBTITLE_REDIRECTS) {
                        return code to "Exceeded maximum redirect depth ($MAX_BROWSER_SUBTITLE_REDIRECTS)"
                    }
                    val resolvedNext = com.mvrk.vrka.HeaderValidation.resolveRedirectUrl(currentUrl, location)
                        ?: return code to "Rejected invalid or unsafe redirect target"
                    val (nextHeaders, nextCredsAllowed) = com.mvrk.vrka.HeaderValidation.scopeHeadersForHop(
                        headers = activeHeaders,
                        fromUrl = currentUrl,
                        toUrl = resolvedNext,
                        credentialsStillAllowed = credentialsAllowed,
                    )
                    activeHeaders = nextHeaders
                    credentialsAllowed = nextCredsAllowed
                    currentUrl = resolvedNext
                    continue
                }

                if (code in 200..299) {
                    val declaredLength = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    if (declaredLength > MAX_BROWSER_SUBTITLE_BYTES) {
                        return code to "Subtitle payload exceeds ${MAX_BROWSER_SUBTITLE_BYTES / (1024 * 1024)} MiB limit"
                    }
                    writeBoundedSubtitleFile(connection.inputStream, targetFile, MAX_BROWSER_SUBTITLE_BYTES)
                    return code to null
                }

                return code to "HTTP error $code"
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        return 0 to "Exceeded maximum redirect depth ($MAX_BROWSER_SUBTITLE_REDIRECTS)"
    }

    private fun writeBoundedSubtitleFile(input: java.io.InputStream, targetFile: File, maxBytes: Long) {
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0L
        var completed = false
        try {
            input.use { stream ->
                targetFile.outputStream().use { out ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > maxBytes) {
                            throw java.io.IOException(
                                "Subtitle payload exceeds ${maxBytes / (1024 * 1024)} MiB limit"
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            completed = true
        } finally {
            if (!completed) {
                targetFile.delete()
            }
        }
    }

    companion object {
        private const val TAG = "VRKA-SubtitleManager"
        private const val MAX_BROWSER_SUBTITLE_REDIRECTS = 5
        private const val MAX_BROWSER_SUBTITLE_BYTES = 8L * 1024 * 1024
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_TRANSPORT_HEADERS = setOf(
            "host",
            "content-length",
            "connection",
            "proxy-connection",
            "transfer-encoding",
            "upgrade",
        )
    }
}
