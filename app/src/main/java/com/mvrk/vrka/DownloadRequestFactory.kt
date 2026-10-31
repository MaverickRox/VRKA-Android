package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

internal object DownloadRequestFactory {
    private const val outputMarker = "__VRKA_OUTPUT__"
    private var nativeLibraryDir: String? = null
    private var cacheDir: String? = null

    fun initNativeLibraryDir(dir: String?) {
        nativeLibraryDir = dir
    }

    /**
     * ytdl-android forces `--no-cache-dir` unless a request supplies its own cache path. That
     * disabled yt-dlp's player/extractor cache, so every preflight and transfer re-fetched the
     * same player data. An app-private cache directory restores reuse without touching cookies.
     */
    fun initCacheDir(dir: String?) {
        cacheDir = dir
    }

    fun applyCacheDir(request: YoutubeDLRequest) {
        cacheDir?.let { request.addOption("--cache-dir", it) }
    }

    internal fun shouldAttemptBrowserFallback(
        request: DownloadRequest,
        hasFailure: Boolean,
        cancelled: Boolean,
    ): Boolean = hasFailure && request.resolvedMediaUrl == null && !cancelled

    internal fun browserFallbackRequest(
        request: DownloadRequest,
        mediaUrl: String,
        headers: Map<String, String>,
    ): DownloadRequest {
        require(CustomCommandPolicy.isHttpUrl(mediaUrl)) { "Browser fallback candidate must be a valid http or https URL." }
        return request.copy(
            // Keep the public page as the job's user-facing source. The private candidate is only
            // an internal transfer input and must not inherit custom command/header state.
            customArguments = emptyList(),
            commandTemplateId = null,
            commandTemplateName = "",
            commandTemplateArguments = emptyList(),
            referer = "",
            origin = "",
            customHeaders = emptyMap(),
            resolvedMediaUrl = mediaUrl,
            resolvedHeaders = headers,
            resolvedHeadersOrigin = HeaderValidation.originOf(mediaUrl).orEmpty(),
        )
    }

    fun getQuickJsPath(): String? {
        val f = nativeLibraryDir?.let { File(it, "libqjs.so") }
        return if (f != null && f.exists()) f.absolutePath else null
    }

    // ytdl-android always appends `--js-runtimes quickjs:<nativeLibraryDir>/libqjs.so`
    // itself; repeating it here only made the command longer. The bundled yt-dlp also ships
    // yt_dlp_ejs, so no `ejs:github` round-trip is needed.

    fun formatSelectorFor(request: DownloadRequest): String =
        if (request.mode == MediaMode.AUDIO) {
            audioFormatSelector(request.audioFormat)
        } else {
            buildVideoFormat(request.quality.height, request.prefer60Fps)
        }

    fun info(request: DownloadRequest): YoutubeDLRequest =
        YoutubeDLRequest(request.resolvedMediaUrl ?: request.url).apply {
            addOption("--no-warnings")
            addOption("--legacy-server-connect")
            if (!request.isPlaylist) addOption("--no-playlist")
            applyCacheDir(this)
            addSessionContext(request)
        }

    /**
     * Metadata-only preflight. Deliberately omits `-f`: yt-dlp then dumps every available
     * video, audio and subtitle stream in one pass, which is exactly what the shared metadata
     * repository needs. The format-specific size is calculated locally from that table, so a
     * quality or audio change never launches another extraction.
     *
     * Network waits are bounded here rather than by raising the caller's timeout: a stalled
     * socket or a broken extractor retry loop must fail with a useful state instead of
     * occupying the single preflight slot until the outer deadline.
     */
    fun sizeEstimate(request: DownloadRequest): YoutubeDLRequest =
        YoutubeDLRequest(request.resolvedMediaUrl ?: request.url).apply {
            addOption("--no-warnings")
            addOption("--simulate")
            addOption("--dump-single-json")
            addOption("--socket-timeout", METADATA_SOCKET_TIMEOUT_SECONDS)
            addOption("--retries", METADATA_RETRIES)
            addOption("--extractor-retries", METADATA_EXTRACTOR_RETRIES)
            if (request.isPlaylist) {
                request.playlistStart?.let { addOption("--playlist-start", it) }
                request.playlistEnd?.let { addOption("--playlist-end", it) }
            } else {
                addOption("--no-playlist")
            }
            applyCacheDir(this)
            addSessionContext(request)
        }

    /**
     * The metadata command as ytdl-android will execute it, with header values removed.
     * Cookies and authorization tokens must never reach diagnostics, so only header names and
     * the fact that a value was supplied are recorded. ytdl-android itself appends the QuickJS
     * runtime flag, which is included here so the recorded command matches the real process.
     */
    internal fun diagnosticCommand(request: YoutubeDLRequest): String {
        val args = redactHeaderValues(request.buildCommand())
        val runtime = getQuickJsPath()?.let { " --js-runtimes quickjs:$it" }.orEmpty()
        return ("yt-dlp " + args.joinToString(" ") + runtime).take(MAX_DIAGNOSTIC_COMMAND_CHARS)
    }

    private fun redactHeaderValues(args: List<String>): List<String> {
        val result = ArrayList<String>(args.size)
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (argument == "--add-header" && index + 1 < args.size) {
                result += argument
                result += redactHeader(args[index + 1])
                index += 2
                continue
            }
            if (argument.startsWith("--add-header=")) {
                result += "--add-header=" + redactHeader(argument.removePrefix("--add-header="))
            } else {
                result += argument
            }
            index++
        }
        return result
    }

    private fun redactHeader(value: String): String =
        value.substringBefore(':').ifBlank { "header" } + ":<redacted>"

    internal fun audioFormatSelector(format: AudioFormat): String = when (format) {
        AudioFormat.OPUS -> "bestaudio[acodec^=opus]/bestaudio/best"
        else -> "bestaudio/best"
    }

    fun download(
        job: DownloadJob,
        stagingDirectory: File,
        includeSubtitles: Boolean = true,
    ): YoutubeDLRequest {
        val options = job.request
        val source = options.resolvedMediaUrl ?: options.url
        if (!options.commandTemplateId.isNullOrBlank()) {
            return customCommand(job, stagingDirectory)
        }
        return YoutubeDLRequest(source).apply {
            addOption("--newline")
            addOption("--progress")
            addOption("--no-mtime")
            addOption("--no-overwrites")
            addOption("--legacy-server-connect")
            addOption("--concurrent-fragments", "4")
            addOption("--print", "before_dl:__VRKA_TITLE__%(title)s")
            addOption("--print", "after_move:$outputMarker%(filepath)s")
            val stagingTemplate = if (options.isPlaylist) {
                "%(playlist_index|0)03d-%(title).96B.%(ext)s"
            } else {
                "media.%(ext)s"
            }
            addOption("-o", File(stagingDirectory, stagingTemplate).absolutePath)

            if (options.isPlaylist) {
                options.playlistStart?.let { addOption("--playlist-start", it) }
                options.playlistEnd?.let { addOption("--playlist-end", it) }
            } else {
                addOption("--no-playlist")
            }

            if (options.mode == MediaMode.AUDIO) {
                addOption("-f", formatSelectorFor(options))
                addOption("--extract-audio")
                addOption("--audio-format", options.audioFormat.codec)
                if (options.audioFormat == AudioFormat.MP3) {
                    val bitrate = options.mp3Bitrate.coerceIn(128, 320)
                    addOption("--audio-quality", "${bitrate}K")
                }
                if (options.embedThumbnail && options.audioFormat != AudioFormat.WAV) addOption("--embed-thumbnail")
            } else {
                addOption("-f", formatSelectorFor(options))
                if (options.prefer60Fps) {
                    addOption("-S", "res,fps")
                }
                addOption("--merge-output-format", "mp4")
            }

            if (options.embedMetadata) addOption("--embed-metadata")
            val effectiveIncludeSubtitles = includeSubtitles && options.resolvedMediaUrl == null
            if (effectiveIncludeSubtitles && options.downloadSubtitles) {
                addOption("--write-subs")
                if (options.automaticCaptions) {
                    addOption("--write-auto-subs")
                    val subLangs = if (options.subtitleLanguages.isBlank() || options.subtitleLanguages == "en.*") {
                        "en,en-orig,en-US,en-GB"
                    } else {
                        options.subtitleLanguages
                    }
                    addOption("--sub-langs", subLangs)
                    addOption("--sleep-subtitles", "2")
                } else {
                    val subLangs = if (options.subtitleLanguages.isBlank()) {
                        "en.*,en,en-US,en-GB"
                    } else {
                        options.subtitleLanguages
                    }
                    addOption("--sub-langs", subLangs)
                    addOption("--sleep-subtitles", "1")
                }
                addOption("--sub-format", "srt/best")
                if (options.mode == MediaMode.VIDEO && options.embedSubtitles) {
                    addOption("--embed-subs")
                }
            }
            if (options.sponsorBlock) {
                addOption(
                    "--sponsorblock-remove",
                    options.sponsorCategories.ifBlank { "sponsor" },
                )
            }

            trimSection(options)?.let {
                addOption("--download-sections", it)
                addOption("--force-keyframes-at-cuts")
            }

            if (options.customArguments.none { it == "--no-cache-dir" }) applyCacheDir(this)
            val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(options)
            if (options.resolvedMediaUrl != null) {
                if (effectiveHeaders.keys.none { it.equals("User-Agent", true) }) {
                    addOption("--user-agent", DESKTOP_USER_AGENT)
                }
                if (effectiveHeaders.keys.none { it.equals("Referer", true) }) {
                    addOption("--referer", options.url)
                }
            }
            addSessionContext(options)
            if (options.customArguments.isNotEmpty()) {
                addCommands(safeCustomArguments(options).take(40))
            }
        }
    }

    internal fun customCommand(job: DownloadJob, stagingDirectory: File): YoutubeDLRequest {
        val options = job.request
        require(CustomCommandPolicy.isHttpUrl(options.url)) { "The custom-command URL must be a valid http or https URL." }
        val validation = CustomCommandPolicy.validateTemplate(
            options.commandTemplateName,
            options.commandTemplateArguments,
        )
        require(validation == null) { validation.orEmpty() }

        val outputTemplate = "%(playlist_index|0)03d-%(title).96B.%(ext)s"
        return YoutubeDLRequest(listOf(options.url)).apply {
            addOption("--ignore-config")
            addOption("--no-mtime")
            addOption("--no-overwrites")
            addOption("-o", File(stagingDirectory, outputTemplate).absolutePath)
            addOption("--print", "before_dl:__VRKA_TITLE__%(title)s")
            addCommands(options.commandTemplateArguments)
            // VRKA-owned output/progress settings are appended after user arguments.
            addOption("--newline")
            addOption("--progress")
        }
    }

    fun outputPaths(responseText: String): List<String> =
        responseText.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(outputMarker) }
            .map { it.removePrefix(outputMarker).trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

    private fun YoutubeDLRequest.addSessionContext(request: DownloadRequest) {
        val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(request)
        effectiveHeaders.forEach { (name, value) ->
            addCommands(listOf("--add-header", "$name:$value"))
        }
    }

    private fun safeCustomArguments(request: DownloadRequest): List<String> {
        val optionsWithValues = setOf(
            "-f",
            "--format",
            "--audio-format",
            "--audio-quality",
            "--merge-output-format",
            "--remux-video",
            "--recode-video",
            "--impersonate",
        )
        val dangerousOptionsWithValues = setOf(
            "-o", "--output", "--paths", "--exec", "--exec-before-download",
            "--external-downloader", "--external-downloader-args", "--config-location",
            "--config-locations", "--plugin-dirs", "--load-info-json", "--batch-file",
            "--ffmpeg-location", "--print-to-file",
        )
        val flags = setOf("-x", "--extract-audio")
        val result = mutableListOf<String>()
        var skipNext = false
        request.customArguments.forEach { argument ->
            if (skipNext) {
                skipNext = false
            } else if (argument in dangerousOptionsWithValues) {
                skipNext = true
            } else if (dangerousOptionsWithValues.any { argument.startsWith("$it=") } ||
                argument.startsWith("--exec") || argument.startsWith("--external-downloader")
            ) {
                Unit
            } else if (argument in optionsWithValues) {
                skipNext = true
            } else if (argument in flags ||
                optionsWithValues.any { argument.startsWith("$it=") } ||
                argument.contains("impersonate", ignoreCase = true)
            ) {
                Unit
            } else {
                result += argument
            }
        }
        return result
    }

    private fun trimSection(request: DownloadRequest): String? {
        val start = request.trimStart.trim()
        val end = request.trimEnd.trim()
        if (start.isEmpty() && end.isEmpty()) return null
        return "*${start.ifEmpty { "0" }}-${end.ifEmpty { "inf" }}"
    }

    fun buildVideoFormat(height: Int?, prefer60Fps: Boolean): String {
        val heightFilter = if (height != null && height > 0) "[height<=$height]" else ""
        val tiers = mutableListOf<String>()
        if (prefer60Fps) {
            tiers.add("bestvideo$heightFilter[fps>=60]+bestaudio")
        }
        tiers.add("bestvideo$heightFilter+bestaudio")
        if (heightFilter.isNotEmpty()) {
            tiers.add("best$heightFilter")
        }
        tiers.add("best")
        return tiers.joinToString("/")
    }

    internal const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"

    private const val METADATA_SOCKET_TIMEOUT_SECONDS = "10"
    private const val METADATA_RETRIES = "2"
    private const val METADATA_EXTRACTOR_RETRIES = "1"
    private const val MAX_DIAGNOSTIC_COMMAND_CHARS = 1_200
}
