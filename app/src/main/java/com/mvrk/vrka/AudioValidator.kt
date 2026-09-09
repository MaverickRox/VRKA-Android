package com.mvrk.vrka

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.File

internal data class AudioStreamInfo(
    val hasAudio: Boolean,
    val formatName: String = "",
    val codecName: String = "",
    val durationMs: Long = 0L,
    val bitrateBps: Long = 0L,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
)

internal object AudioValidator {
    fun validateAudioOutput(
        file: File,
        format: AudioFormat,
        expectedBitrateKbps: Int?,
        context: Context,
    ): AudioStreamInfo {
        require(file.isFile && file.length() > 0) {
            "Audio output file is missing or empty: ${file.name}"
        }

        val info = probeWithFfprobe(file, context) ?: probeWithMediaMetadataRetriever(file)

        check(info.hasAudio) {
            "Audio validation failed: Output file ${file.name} does not contain a recognized audio stream."
        }
        check(info.durationMs > 0) {
            "Audio validation failed: Output file ${file.name} duration is zero or invalid (${info.durationMs}ms)."
        }

        val ext = file.extension.lowercase()
        when (format) {
            AudioFormat.MP3 -> {
                check(ext == "mp3") {
                    "Audio validation failed: MP3 output must have .mp3 extension, got .$ext"
                }
                if (info.codecName.isNotBlank()) {
                    check(info.codecName.contains("mp3", ignoreCase = true) || info.codecName.contains("mpeg", ignoreCase = true)) {
                        "Audio validation failed: Expected MP3 codec, got ${info.codecName}"
                    }
                }
                if (expectedBitrateKbps != null && info.bitrateBps > 0) {
                    val actualKbps = info.bitrateBps / 1000
                    Log.i("VRKA", "MP3 stream validated: actual=${actualKbps}kbps, requested=${expectedBitrateKbps}kbps, duration=${info.durationMs}ms")
                }
            }
            AudioFormat.OPUS -> {
                check(ext in setOf("opus", "ogg")) {
                    "Audio validation failed: Opus output must have .opus or .ogg extension, got .$ext"
                }
                if (info.codecName.isNotBlank()) {
                    check(info.codecName.contains("opus", ignoreCase = true)) {
                        "Audio validation failed: Expected Opus codec, got ${info.codecName}"
                    }
                }
                Log.i("VRKA", "Opus stream validated: codec=${info.codecName}, duration=${info.durationMs}ms, sampleRate=${info.sampleRate}")
            }
            AudioFormat.WAV -> {
                check(ext == "wav") {
                    "Audio validation failed: WAV output must have .wav extension, got .$ext"
                }
                if (info.codecName.isNotBlank()) {
                    check(
                        info.codecName.contains("pcm", ignoreCase = true) ||
                            info.codecName.contains("wav", ignoreCase = true)
                    ) {
                        "Audio validation failed: Expected uncompressed PCM audio for WAV, got ${info.codecName}"
                    }
                }
                Log.i("VRKA", "WAV stream validated: codec=${info.codecName}, duration=${info.durationMs}ms, sampleRate=${info.sampleRate}, bitDepth=${info.bitDepth}")
            }
        }
        return info
    }

    private fun probeWithFfprobe(file: File, context: Context): AudioStreamInfo? {
        return runCatching {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val probeBin = File(nativeDir, "libffprobe.so")
            if (!probeBin.exists() || !probeBin.canExecute()) return null
            val ffmpegUsrLib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")

            val pb = ProcessBuilder(
                probeBin.absolutePath,
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,codec_type,sample_rate,bits_per_raw_sample,bit_rate:format=format_name,duration,bit_rate",
                "-of", "default=noprint_wrappers=1",
                file.absolutePath,
            )
            val ldPath = if (ffmpegUsrLib.exists()) "${nativeDir}:${ffmpegUsrLib.absolutePath}" else nativeDir
            pb.environment()["LD_LIBRARY_PATH"] = ldPath

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.isBlank()) return null
            val map = output.lines()
                .filter { it.contains("=") }
                .associate {
                    val key = it.substringBefore("=").trim()
                    val value = it.substringAfter("=").trim()
                    key to value
                }

            val codecType = map["codec_type"]
            val codecName = map["codec_name"].orEmpty()
            val formatName = map["format_name"].orEmpty()
            val durationSec = map["duration"]?.toDoubleOrNull() ?: 0.0
            val durationMs = (durationSec * 1000).toLong()
            val bitrateBps = map["bit_rate"]?.toLongOrNull() ?: 0L
            val sampleRate = map["sample_rate"]?.toIntOrNull() ?: 0
            val bitDepth = map["bits_per_raw_sample"]?.toIntOrNull() ?: 0

            val hasAudio = codecType == "audio" || codecName.isNotBlank()
            AudioStreamInfo(
                hasAudio = hasAudio,
                formatName = formatName,
                codecName = codecName,
                durationMs = durationMs,
                bitrateBps = bitrateBps,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
            )
        }.getOrNull()
    }

    private fun probeWithMediaMetadataRetriever(file: File): AudioStreamInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ext = file.extension.lowercase()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" ||
                ext in setOf("mp3", "opus", "wav", "m4a", "ogg")
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0

            val codecName = when {
                mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) || ext == "mp3" -> "mp3"
                mime.contains("opus", ignoreCase = true) || ext in setOf("opus", "ogg") -> "opus"
                mime.contains("wav", ignoreCase = true) || ext == "wav" -> "pcm"
                else -> mime
            }

            AudioStreamInfo(
                hasAudio = hasAudio,
                formatName = mime,
                codecName = codecName,
                durationMs = durationMs,
                bitrateBps = bitrateBps,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
            )
        } finally {
            retriever.release()
        }
    }
}
