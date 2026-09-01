package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

internal object DownloadRequestFactory {
    private const val outputMarker = "__VRKA_OUTPUT__"

    fun info(request: DownloadRequest): YoutubeDLRequest =
        YoutubeDLRequest(request.resolvedMediaUrl ?: request.url).apply {
            addOption("--no-warnings")
            if (!request.isPlaylist) addOption("--no-playlist")
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
                addOption("-f", "bestaudio/best")
                addOption("--extract-audio")
                addOption("--audio-format", options.audioFormat.codec)
                addOption(
                    "--audio-quality",
                    if (options.audioFormat == AudioFormat.MP3) "${options.mp3Bitrate}K" else "0",
                )
                if (options.embedThumbnail) addOption("--embed-thumbnail")
            } else {
                addOption("-f", "bestvideo+bestaudio/best[height>0]")
                val resolutionSort = options.quality.height?.let { "res:$it" }
                when {
                    options.prefer60Fps && resolutionSort != null ->
                        addOption("-S", "$resolutionSort,fps")
                    options.prefer60Fps -> addOption("-S", "res,fps")
                    resolutionSort != null -> addOption("-S", resolutionSort)
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

            addOption("--remote-components", "ejs:github")
            if (recoveryAttempt) {
                addOption("--extractor-args", "generic:impersonate")
                if (options.resolvedHeaders.keys.none { it.equals("User-Agent", true) }) {
                    addOption("--user-agent", DESKTOP_USER_AGENT)
                }
                if (options.resolvedHeaders.keys.none { it.equals("Referer", true) }) {
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
        val allowed = setOf(
            "authorization",
            "cookie",
            "origin",
            "referer",
            "user-agent",
            "x-video-expiration",
            "x-video-ip",
            "x-video-token",
        )
        request.resolvedHeaders.entries
            .filter { it.key.lowercase() in allowed && it.value.isNotBlank() }
            .take(12)
            .forEach { (name, value) ->
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

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"
}
