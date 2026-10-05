package com.mvrk.vrka.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.mvrk.vrka.HeaderValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dormant experimental FFmpeg remote-transfer implementation.
 *
 * It is not connected to the production download manager. Production FFmpeg use is local
 * post-processing, probing, and validation after yt-dlp acquisition.
 */
class FfmpegMediaEngine(private val context: Context) : MediaDownloadEngine {

    override val engineName: String = "FFmpegMediaEngine"

    override fun isAvailable(): Boolean {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpegBin = File(nativeDir, "libffmpeg.so")
        return ffmpegBin.exists() && ffmpegBin.canExecute()
    }

    override suspend fun download(
        handoffContext: MediaHandoffContext,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult = withContext(Dispatchers.IO) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpegBin = File(nativeDir, "libffmpeg.so")
        if (!ffmpegBin.exists()) {
            return@withContext EngineExecutionResult.Failed(
                IOException("libffmpeg.so not found at ${ffmpegBin.absolutePath}"),
                "Native FFmpeg library unavailable"
            )
        }

        val ffmpegUsrLib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val pythonUsrLib = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        MediaAssembly.fixUsrLibSymlinks(ffmpegUsrLib)
        MediaAssembly.fixUsrLibSymlinks(pythonUsrLib)

        val ldPath = buildList {
            if (ffmpegUsrLib.exists()) add(ffmpegUsrLib.absolutePath)
            if (pythonUsrLib.exists()) add(pythonUsrLib.absolutePath)
            add(nativeDir.absolutePath)
        }.joinToString(":")

        // Host-bind headers to candidate host to ensure sniffed session credentials match the media host
        val reqContext = RequestContext(
            targetUrl = handoffContext.url,
            pageUrl = handoffContext.originalPageUrl,
            baseHeaders = handoffContext.headers
        )
        val effectiveHeaders = reqContext.resolveHeaders(handoffContext.url, allowRange = false)

        // Format headers for FFmpeg: CRLF terminated
        val headerBuilder = StringBuilder()
        effectiveHeaders.forEach { (name, value) ->
            val cleanName = name.replace("\r", "").replace("\n", "").trim()
            val cleanVal = value.replace("\r", "").replace("\n", "").trim()
            if (cleanName.isNotBlank() && cleanVal.isNotBlank()) {
                headerBuilder.append(cleanName).append(": ").append(cleanVal).append("\r\n")
            }
        }
        val headersArg = headerBuilder.toString()

        val command = mutableListOf<String>()
        command.add(ffmpegBin.absolutePath)
        command.add("-y")
        command.add("-nostdin")
        command.add("-stats")
        command.add("-v")
        command.add("warning")

        if (headersArg.isNotEmpty()) {
            command.add("-headers")
            command.add(headersArg)
        }

        if (handoffContext.url.startsWith("http://", ignoreCase = true) || handoffContext.url.startsWith("https://", ignoreCase = true)) {
            command.add("-reconnect")
            command.add("1")
            command.add("-reconnect_streamed")
            command.add("1")
            command.add("-reconnect_delay_max")
            command.add("5")
            command.add("-rw_timeout")
            command.add("10000000") // 10s socket timeout
        }

        if (handoffContext.url.contains(".m3u8", ignoreCase = true) || handoffContext.mediaKind == EngineMediaKind.HLS) {
            command.add("-protocol_whitelist")
            command.add("file,http,https,tcp,tls,crypto,data")
            command.add("-extension_picky")
            command.add("0")
            command.add("-allowed_extensions")
            command.add("ALL")
        }

        command.add("-i")
        command.add(handoffContext.url)

        if (handoffContext.mediaKind == EngineMediaKind.PROGRESSIVE_AUDIO || handoffContext.stagingOutputFile.extension.lowercase() in listOf("mp3", "m4a", "opus", "flac", "wav")) {
            command.add("-map")
            command.add("0:a?")
            command.add("-vn")
            command.add("-c:a")
            command.add("copy")
        } else {
            command.add("-map")
            command.add("0:v:0?")
            command.add("-map")
            command.add("0:a?")
            command.add("-c")
            command.add("copy")
        }

        if (handoffContext.stagingOutputFile.extension.equals("mp4", ignoreCase = true)) {
            command.add("-movflags")
            command.add("+faststart")
        }

        val destination = handoffContext.stagingOutputFile
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        command.add(destination.absolutePath)

        onProgress(EngineDownloadProgress.Initializing("Starting media transfer"))

        var process: Process? = null
        val stderrLines = mutableListOf<String>()

        try {
            val pb = ProcessBuilder(command)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.redirectErrorStream(true) // Combined stream for stats parsing

            process = pb.start()
            val proc = process

            val reader = proc.inputStream.bufferedReader()
            var totalDuration = handoffContext.durationSeconds ?: 0.0
            var lastProgressUpdate = 0L

            while (true) {
                if (cancellationToken.isCancelled()) {
                    Log.i(TAG, "Cancellation signal received; terminating FFmpeg process")
                    destroyProcessSafely(proc)
                    destination.delete()
                    return@withContext EngineExecutionResult.Cancelled
                }

                val line = reader.readLine() ?: break
                Log.d(TAG, "FFmpeg: $line")
                stderrLines.add(line)
                if (stderrLines.size > 200) stderrLines.removeAt(0)

                // Try discovering duration from FFmpeg output if not already known
                if (totalDuration <= 0.0 && line.contains("Duration:", ignoreCase = true)) {
                    val durationMatch = DURATION_REGEX.find(line)
                    if (durationMatch != null) {
                        val hours = durationMatch.groupValues[1].toLongOrNull() ?: 0L
                        val mins = durationMatch.groupValues[2].toLongOrNull() ?: 0L
                        val secs = durationMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                        totalDuration = hours * 3600.0 + mins * 60.0 + secs
                    }
                }

                // Parse progress stats: time=HH:MM:SS.ms size=...kB speed=...x
                val timeMatch = TIME_REGEX.find(line)
                if (timeMatch != null) {
                    val h = timeMatch.groupValues[1].toLongOrNull() ?: 0L
                    val m = timeMatch.groupValues[2].toLongOrNull() ?: 0L
                    val s = timeMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                    val currentSecs = h * 3600.0 + m * 60.0 + s

                    val sizeMatch = SIZE_REGEX.find(line)
                    val sizeKb = sizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val bytesRead = sizeKb * 1024L

                    val speedMatch = SPEED_REGEX.find(line)
                    val speedStr = speedMatch?.groupValues?.get(1)

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate >= 250) {
                        lastProgressUpdate = now
                        val rawPct = if (totalDuration > 0.0) {
                            ((currentSecs / totalDuration) * 100.0).toFloat().coerceIn(0f, 99f)
                        } else null

                        val eta = if (totalDuration > 0.0 && currentSecs > 0.0) {
                            val remaining = totalDuration - currentSecs
                            remaining.toLong().coerceAtLeast(0L)
                        } else null

                        val mult = speedStr?.toDoubleOrNull()
                        val snapshot = UnifiedProgressSnapshot(
                            downloadedBytes = bytesRead,
                            totalBytes = if (totalDuration > 0.0 && rawPct != null && rawPct > 0f) (bytesRead / (rawPct / 100f)).toLong() else null,
                            percentage = rawPct,
                            durationProcessedSeconds = currentSecs,
                            totalDurationSeconds = if (totalDuration > 0.0) totalDuration else null,
                            realtimeMultiplier = mult,
                            etaSeconds = eta,
                            detail = "Downloading"
                        )

                        val displayPct = snapshot.safePercentage ?: if (bytesRead > 0L) 1f else 0f
                        onProgress(
                            EngineDownloadProgress.Downloading(
                                bytesRead = bytesRead,
                                totalBytes = snapshot.totalBytes,
                                percentage = displayPct,
                                speedBytesPerSec = null,
                                etaSeconds = eta,
                                detail = snapshot.formatCardStatusText()
                            )
                        )
                    }
                }
            }

            val exitCode = proc.waitFor()
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }

            if (exitCode != 0) {
                val errorSummary = stderrLines.takeLast(25).joinToString("\n")
                destination.delete()
                return@withContext EngineExecutionResult.Failed(
                    IOException("FFmpeg exited with non-zero code $exitCode:\n$errorSummary"),
                    "FFmpeg download error ($exitCode)"
                )
            }

            if (!destination.exists() || destination.length() == 0L) {
                destination.delete()
                return@withContext EngineExecutionResult.Failed(
                    IOException("FFmpeg completed but target file is empty or missing"),
                    "Output file verification failed: empty payload"
                )
            }

            // Container validation: Ensure container has at least one valid track
            val validationSuccess = validateContainerIntegrity(destination)
            if (!validationSuccess) {
                destination.delete()
                return@withContext EngineExecutionResult.Failed(
                    IOException("Downloaded container failed integrity validation; no playable streams found"),
                    "Corrupt or empty media container"
                )
            }

            onProgress(EngineDownloadProgress.Finalizing("Media transfer and remux verified"))
            return@withContext EngineExecutionResult.Success(destination, destination.length())

        } catch (e: Throwable) {
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }
            destination.delete()
            return@withContext EngineExecutionResult.Failed(e, "FFmpeg execution failed: ${e.message}")
        } finally {
            process?.let { destroyProcessSafely(it) }
        }
    }

    fun remuxTsToMp4(inputFile: File, outputFile: File): Boolean {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpegBin = File(nativeDir, "libffmpeg.so")
        if (!ffmpegBin.exists() || !ffmpegBin.canExecute()) return false

        val ffmpegUsrLib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val pythonUsrLib = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        MediaAssembly.fixUsrLibSymlinks(ffmpegUsrLib)
        MediaAssembly.fixUsrLibSymlinks(pythonUsrLib)

        val ldPath = buildList {
            if (ffmpegUsrLib.exists()) add(ffmpegUsrLib.absolutePath)
            if (pythonUsrLib.exists()) add(pythonUsrLib.absolutePath)
            add(nativeDir.absolutePath)
        }.joinToString(":")

        val command = listOf(
            ffmpegBin.absolutePath,
            "-y",
            "-nostdin",
            "-i", inputFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )

        return try {
            val pb = ProcessBuilder(command)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            val proc = pb.start()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            if (finished && proc.exitValue() == 0) {
                Log.i(TAG, "Successfully remuxed ${inputFile.name} to ${outputFile.name}")
                true
            } else {
                destroyProcessSafely(proc)
                Log.w(TAG, "Remux failed with exit value: ${if (finished) proc.exitValue() else "timeout"}")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Remux failed: ${t.message}")
            false
        }
    }

    private fun destroyProcessSafely(process: Process) {
        runCatching {
            process.destroy()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }

    /**
     * Probes container to verify at least one audio or video stream exists.
     */
    private fun validateContainerIntegrity(file: File): Boolean {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val count = extractor.trackCount
                for (i in 0 until count) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.ROOT)
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        return@runCatching true
                    }
                }
                false
            } finally {
                runCatching { extractor.release() }
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "VRKA-FfmpegMediaEngine"
        private val DURATION_REGEX = Regex("""Duration:\s*(\d{2}):(\d{2}):(\d{2}\.\d+)""")
        private val TIME_REGEX = Regex("""time=(\d{2}):(\d{2}):(\d{2}\.\d+)""")
        private val SIZE_REGEX = Regex("""size=\s*(\d+)kB""")
        private val SPEED_REGEX = Regex("""speed=\s*([0-9.]+)x""")
    }
}
