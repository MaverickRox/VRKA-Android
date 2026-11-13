package com.mvrk.vrka

import java.io.File

/**
 * Final-output accounting shared by the transfer manager and its regression tests.
 * Subtitles and other sidecars are never counted as media size.
 */
internal object DownloadAccounting {
    val MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "mov", "m4v", "ts",
        "mp3", "m4a", "mka", "aac", "opus", "flac", "wav", "ogg",
    )

    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    fun isMediaExtension(extension: String): Boolean =
        extension.lowercase() in MEDIA_EXTENSIONS

    fun isSubtitleExtension(extension: String): Boolean =
        extension.lowercase() in SUBTITLE_EXTENSIONS

    fun sortOutputsPrimaryFirst(files: List<File>): List<File> =
        files.sortedWith(compareBy({ !isMediaExtension(it.extension) }, { it.name }))

    fun mediaOutputBytes(files: List<File>): Long? = files
        .filter { isMediaExtension(it.extension) }
        .sumOf { file -> runCatching { file.length() }.getOrDefault(0L) }
        .takeIf { it > 0L }
}
