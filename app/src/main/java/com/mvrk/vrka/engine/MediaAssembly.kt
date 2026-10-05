package com.mvrk.vrka.engine

import java.net.URI

/**
 * HLS and media assembly helpers.
 */
object MediaAssembly {

    fun durationMilliseconds(file: java.io.File, context: android.content.Context): Long? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    fun isH264Codec(codec: String): Boolean =
        codec.trim().lowercase() in setOf("h264", "avc", "avc1", "video/avc") ||
            codec.trim().lowercase().startsWith("avc1.")

    /** Returns the first video MIME/codec name, or null when the file cannot be inspected. */
    fun videoCodecName(
        file: java.io.File,
        context: android.content.Context,
        onProcessStarted: (Process) -> Unit = {},
    ): String? {
        if (!file.isFile || file.length() == 0L) return null
        val mediaExtractorCodec = runCatching {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    mime.takeIf { it.startsWith("video/", ignoreCase = true) }
                }
            } finally {
                extractor.release()
            }
        }.getOrNull()
        if (!mediaExtractorCodec.isNullOrBlank()) return mediaExtractorCodec

        return runCatching {
            val probe = java.io.File(context.applicationInfo.nativeLibraryDir, "libffprobe.so")
            if (!probe.exists() || !probe.canExecute()) return null
            val command = listOf(
                probe.absolutePath,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.absolutePath,
            )
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            configureFfmpegEnvironment(processBuilder, context)
            val process = processBuilder.start()
            onProcessStarted(process)
            val output = readProcessOutput(process, timeoutMs = 10_000L, maxChars = 4_096) ?: return null
            output.trim().takeIf { process.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()
    }

    fun buildH264CompatibilityCommand(
        context: android.content.Context,
        inputFile: java.io.File,
        outputFile: java.io.File,
    ): List<String> = listOf(
        java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
        "-y",
        "-nostdin",
        "-hide_banner",
        "-v", "warning",
        "-progress", "pipe:1",
        "-nostats",
        "-i", inputFile.absolutePath,
        "-map", "0:v:0",
        "-map", "0:a?",
        "-map", "0:s?",
        "-map_metadata", "0",
        "-map_chapters", "0",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        "-c:a", "copy",
        "-c:s", "copy",
        "-f", "matroska",
        outputFile.absolutePath,
    )

    fun hasH264Encoder(
        context: android.content.Context,
        onProcessStarted: (Process) -> Unit = {},
    ): Boolean {
        return runCatching {
            val ffmpeg = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (!ffmpeg.exists() || !ffmpeg.canExecute()) return@runCatching false
            val processBuilder = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-encoders")
                .redirectErrorStream(true)
            configureFfmpegEnvironment(processBuilder, context)
            val process = processBuilder.start()
            onProcessStarted(process)
            val output = readProcessOutput(process, timeoutMs = 15_000L, maxChars = 2 * 1024 * 1024)
                ?: return@runCatching false
            process.exitValue() == 0 && output.contains("libx264", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** Drains subprocess output while enforcing a deadline, so probes cannot hang on a full pipe. */
    internal fun readProcessOutput(process: Process, timeoutMs: Long, maxChars: Int): String? {
        val output = StringBuilder()
        val outputLock = Any()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(outputLock) {
                            if (output.length < maxChars) {
                                output.append(buffer, 0, minOf(count, maxChars - output.length))
                            }
                        }
                    }
                }
            }
        }, "VRKA-media-probe").apply {
            isDaemon = true
            start()
        }
        val finished = try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return null
        }
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        runCatching { reader.join(1_000L) }
        if (!finished) return null
        return synchronized(outputLock) { output.toString() }
    }

    /**
     * Resolves a relative segment or variant URL against its parent playlist URL using standard URI resolution.
     */
    fun resolveSegmentUrl(seg: String, playlistUrl: String): String {
        val trimmed = seg.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val baseUri = URI(playlistUrl)
            baseUri.resolve(trimmed).toString()
        } catch (_: Throwable) {
            val base = playlistUrl.split("?")[0].substringBeforeLast('/', playlistUrl)
            "$base/$trimmed"
        }
    }

    /**
     * Determines whether a given URL or MIME type indicates an HLS playlist.
     */
    fun isHlsPlaylist(url: String, contentType: String = ""): Boolean {
        val loweredType = contentType.lowercase()
        val path = url.split("?")[0].lowercase()
        return "mpegurl" in loweredType || path.endsWith(".m3u8")
    }

    /**
     * Determines whether a given URL or content-type indicates a DASH MPD manifest.
     */
    fun isDashManifest(url: String, contentType: String = ""): Boolean {
        val loweredType = contentType.lowercase()
        val path = url.split("?")[0].lowercase()
        return "dash+xml" in loweredType || path.endsWith(".mpd")
    }

    /**
     * Determines appropriate subtitle codec based on target container and source subtitle format.
     */
    fun getSubtitleCodecForContainer(containerExt: String, subtitleExt: String = "srt"): String {
        val normContainer = containerExt.lowercase().removePrefix(".")
        val normSub = subtitleExt.lowercase().removePrefix(".")
        return when (normContainer) {
            "mp4", "m4v", "mov" -> "mov_text"
            "mkv", "matroska" -> if (normSub in setOf("ass", "ssa")) "ass" else "subrip"
            "webm" -> "webvtt"
            else -> "mov_text"
        }
    }

    /**
     * Constructs a synchronization-safe FFmpeg command to remux media without re-encoding,
     * regenerating presentation timestamps to prevent audio/video drift.
     */
    fun buildRemuxCommand(
        ffmpegBinPath: String,
        inputPath: String,
        outputPath: String,
    ): List<String> {
        return listOf(
            ffmpegBinPath,
            "-y",
            "-fflags", "+genpts",
            "-i", inputPath,
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-movflags", "+faststart",
            outputPath,
        )
    }

    /**
     * Constructs a synchronization-safe FFmpeg command to embed a subtitle file into a video,
     * maintaining stream-copy for video and audio (preventing transcoding and preserving timestamps),
     * and converting subtitle stream to container-compatible timed text.
     */
    fun buildSubtitleEmbedCommand(
        ffmpegBinPath: String,
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        subtitleCodec: String = "mov_text",
        language: String = "eng",
    ): List<String> {
        return listOf(
            ffmpegBinPath,
            "-y",
            "-fflags", "+genpts",
            "-i", videoPath,
            "-i", subtitlePath,
            "-c:v", "copy",
            "-c:a", "copy",
            "-c:s", subtitleCodec,
            "-map", "0:v:0",
            "-map", "0:a?",
            "-map", "1:0",
            "-metadata:s:s:0", "language=$language",
            "-avoid_negative_ts", "make_zero",
            "-movflags", "+faststart",
            outputPath,
        )
    }

    fun buildRemuxCommand(
        context: android.content.Context,
        inputFile: java.io.File,
        outputFile: java.io.File,
    ): List<String> = buildRemuxCommand(
        ffmpegBinPath = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
        inputPath = inputFile.absolutePath,
        outputPath = outputFile.absolutePath,
    )

    fun buildSubtitleEmbedCommand(
        context: android.content.Context,
        videoFile: java.io.File,
        subtitleFile: java.io.File,
        outputFile: java.io.File,
        language: String = "eng",
    ): List<String> = buildSubtitleEmbedCommand(
        ffmpegBinPath = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
        videoPath = videoFile.absolutePath,
        subtitlePath = subtitleFile.absolutePath,
        outputPath = outputFile.absolutePath,
        subtitleCodec = getSubtitleCodecForContainer(outputFile.extension, subtitleFile.extension),
        language = language,
    )

    /**
     * Resolves unlinked symlink stubs extracted as regular text files by youtubedl-android.
     * Replaces small text files referencing other shared objects with true symbolic links or copies.
     */
    fun fixUsrLibSymlinks(usrLibDir: java.io.File) {
        if (!usrLibDir.exists() || !usrLibDir.isDirectory) return
        val files = usrLibDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.length() in 1..256) {
                val targetName = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull() ?: continue
                if (!targetName.startsWith("\u007fELF") && !targetName.contains("\n") && !targetName.contains("\r")) {
                    val targetFile = java.io.File(usrLibDir, targetName)
                    if (targetFile.exists() && targetFile.isFile && targetFile.length() > file.length()) {
                        file.delete()
                        val symlinkSuccess = runCatching {
                            android.system.Os.symlink(targetName, file.absolutePath)
                            true
                        }.getOrDefault(false)
                        if (!symlinkSuccess || !file.exists()) {
                            runCatching {
                                targetFile.copyTo(file, overwrite = true)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Executes FFmpeg binary with proper library environment variables and drained output streams.
     */
    fun executeFfmpeg(
        command: List<String>,
        context: android.content.Context,
        onProcessStarted: (Process) -> Unit = {},
        onOutputLine: (String) -> Unit = {},
    ): Boolean {
        return runCatching {
            val pb = ProcessBuilder(command)
            configureFfmpegEnvironment(pb, context)
            pb.redirectErrorStream(true)
            val process = pb.start()
            onProcessStarted(process)
            val outputLines = java.util.ArrayDeque<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    onOutputLine(line)
                    outputLines.addLast(line)
                    while (outputLines.size > 500) outputLines.removeFirst()
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                android.util.Log.w("VRKA-MediaAssembly", "FFmpeg non-zero exit ($exitCode): ${outputLines.joinToString("\n").takeLast(400)}")
            }
            exitCode == 0
        }.getOrDefault(false)
    }

    private fun configureFfmpegEnvironment(
        processBuilder: ProcessBuilder,
        context: android.content.Context,
    ) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val ffmpegUsrLib = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val pythonUsrLib = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        fixUsrLibSymlinks(ffmpegUsrLib)
        fixUsrLibSymlinks(pythonUsrLib)
        processBuilder.environment()["LD_LIBRARY_PATH"] = buildList {
            if (ffmpegUsrLib.exists()) add(ffmpegUsrLib.absolutePath)
            if (pythonUsrLib.exists()) add(pythonUsrLib.absolutePath)
            add(nativeDir)
        }.joinToString(":")
    }

    /**
     * Dual verification: Probes media file with Android framework MediaExtractor and ffprobe
     * to verify whether a genuine timed text / subtitle stream exists inside the container.
     */
    fun verifySubtitlePresence(
        file: java.io.File,
        context: android.content.Context,
        onProcessStarted: (Process) -> Unit = {},
    ): Boolean {
        if (!file.isFile || file.length() == 0L) return false

        // 1. Android framework MediaExtractor verification
        val extractorResult = runCatching {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val count = extractor.trackCount
                for (i in 0 until count) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty().lowercase()
                    if (mime.startsWith("text/") ||
                        mime.contains("subrip") ||
                        mime.contains("vtt") ||
                        mime.contains("subtitle") ||
                        mime.contains("caption") ||
                        mime.contains("cea-608") ||
                        mime.contains("cea-708") ||
                        mime.contains("quicktime_text") ||
                        mime == "application/x-subrip" ||
                        mime == "application/vtt"
                    ) {
                        return@runCatching true
                    }
                }
                false
            } finally {
                runCatching { extractor.release() }
            }
        }.getOrDefault(false)

        if (extractorResult) return true

        // 2. ffprobe fallback verification
        return runCatching {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val probeBin = java.io.File(nativeDir, "libffprobe.so")
            if (!probeBin.exists() || !probeBin.canExecute()) return false
            val ffmpegUsrLib = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
            val pythonUsrLib = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
            fixUsrLibSymlinks(ffmpegUsrLib)
            fixUsrLibSymlinks(pythonUsrLib)

            val ldPath = buildList {
                if (ffmpegUsrLib.exists()) add(ffmpegUsrLib.absolutePath)
                if (pythonUsrLib.exists()) add(pythonUsrLib.absolutePath)
                add(nativeDir)
            }.joinToString(":")

            val pb = ProcessBuilder(
                probeBin.absolutePath,
                "-v", "error",
                "-select_streams", "s",
                "-show_entries", "stream=codec_type,codec_name",
                "-of", "default=noprint_wrappers=1",
                file.absolutePath,
            )
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.redirectErrorStream(true)
            val process = pb.start()
            onProcessStarted(process)
            val output = readProcessOutput(process, timeoutMs = 10_000L, maxChars = 16_384) ?: return false
            process.exitValue() == 0 && output.contains("codec_type=subtitle", ignoreCase = true)
        }.getOrDefault(false)
    }
}
