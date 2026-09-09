package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

internal object DownloadRequestFactory {
    private const val outputMarker = "__VRKA_OUTPUT__"
    private var nativeLibraryDir: String? = null

    fun initNativeLibraryDir(dir: String?) {
        nativeLibraryDir = dir
    }

    fun getQuickJsPath(): String? {
        val f = nativeLibraryDir?.let { File(it, "libqjs.so") }
        return if (f != null && f.exists()) f.absolutePath else null
    }

    private fun addJsRuntime(request: YoutubeDLRequest) {
        val qjsPath = getQuickJsPath()
        if (qjsPath != null) {
            request.addOption("--js-runtimes", "quickjs:$qjsPath")
        }
        request.addOption("--remote-components", "ejs:github")
    }

    fun info(request: DownloadRequest): YoutubeDLRequest =
        YoutubeDLRequest(request.resolvedMediaUrl ?: request.url).apply {
            addOption("--no-warnings")
            addOption("--legacy-server-connect")
            if (!request.isPlaylist) addOption("--no-playlist")
            addJsRuntime(this)
            addSessionContext(request)
        }

    fun download(
        job: DownloadJob,
        stagingDirectory: File,
        recoveryAttempt: Boolean = false,
    ): YoutubeDLRequest {
        val options = job.request
        val source = options.resolvedMediaUrl ?: options.url
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
                val format = when (options.audioFormat) {
                    AudioFormat.OPUS -> "bestaudio[acodec^=opus]/bestaudio/best"
                    else -> "bestaudio/best"
                }
                addOption("-f", format)
                addOption("--extract-audio")
                addOption("--audio-format", options.audioFormat.codec)
                if (options.audioFormat == AudioFormat.MP3) {
                    val bitrate = options.mp3Bitrate.coerceIn(128, 320)
                    addOption("--audio-quality", "${bitrate}K")
                }
                if (options.embedThumbnail && options.audioFormat != AudioFormat.WAV) addOption("--embed-thumbnail")
            } else {
                val format = if (options.resolvedMediaUrl != null) {
                    "bestvideo+bestaudio/best"
                } else {
                    buildVideoFormat(options.quality.height, options.prefer60Fps)
                }
                addOption("-f", format)
                if (options.prefer60Fps) {
                    addOption("-S", "res,fps")
                }
                addOption("--merge-output-format", "mp4")
            }

            if (options.embedMetadata) addOption("--embed-metadata")
            if (options.downloadSubtitles) {
                addOption("--write-subs")
                if (options.automaticCaptions) addOption("--write-auto-subs")
                addOption("--sub-langs", options.subtitleLanguages.ifBlank { "en.*" })
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

            addJsRuntime(this)
            if (recoveryAttempt) {
                addOption("--extractor-args", "generic:impersonate")
            }
            val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(options)
            if (recoveryAttempt || options.resolvedMediaUrl != null) {
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
        if (request.mode == MediaMode.AUDIO) return request.customArguments
        val optionsWithValues = setOf(
            "-f",
            "--format",
            "--audio-format",
            "--audio-quality",
            "--merge-output-format",
            "--remux-video",
            "--recode-video",
        )
        val flags = setOf("-x", "--extract-audio")
        val result = mutableListOf<String>()
        var skipValue = false
        request.customArguments.forEach { argument ->
            if (skipValue) {
                skipValue = false
            } else if (argument in optionsWithValues) {
                skipValue = true
            } else if (argument in flags || optionsWithValues.any { argument.startsWith("$it=") }) {
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
}
