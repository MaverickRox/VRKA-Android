package com.mvrk.vrka

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/**
 * Reads versions from the installed or bundled component artifacts rather than assuming a
 * hard-coded value.
 *
 * The yt-dlp artifact is a self-executing zipapp: a `#!` launcher line precedes the zip data.
 * Android's `ZipFile` rejects that prefix outright ("invalid LFH signature"), and
 * `ZipInputStream` expects a local header at offset zero. Both readers therefore skip the
 * launcher preamble before parsing, which is what makes them work on device.
 */
internal object InstalledComponentVersionReader {
    private val versionAssignment = Regex("""(?m)^\s*__version__\s*=\s*['\"]([^'\"]+)['\"]""")

    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    private const val PREAMBLE_PROBE_BYTES = 8 * 1024

    fun readYtDlpVersion(zipApp: File): String? = runCatching {
        zipApp.inputStream().use { stream -> readYtDlpVersion(stream) }
    }.getOrNull()

    fun readYtDlpVersion(stream: InputStream): String? =
        readZipEntryText(stream, "yt_dlp/version.py")?.let(::parseYtDlpVersion)

    fun readBundledYtDlpVersion(context: Context): String? = runCatching {
        context.resources.openRawResource(R.raw.ytdlp).use { stream ->
            readYtDlpVersion(stream)
        }
    }.getOrNull()

    fun parseYtDlpVersion(versionSource: String): String? =
        versionAssignment.find(versionSource)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    /** Reads `manifest.json` from an extension archive without extracting the whole XPI. */
    fun readExtensionVersion(input: InputStream): String? =
        readZipEntryText(input, "manifest.json")?.let { text ->
            runCatching {
                org.json.JSONObject(text).optString("version").trim().takeIf(String::isNotBlank)
            }.getOrNull()
        }

    /** Version declared by the extension bundled in the APK, independent of install state. */
    fun readBundledExtensionVersion(context: Context, assetName: String): String? = runCatching {
        context.assets.open("extensions/$assetName").use { stream ->
            readExtensionVersion(stream)
        }
    }.getOrNull()

    private fun readZipEntryText(stream: InputStream, entryName: String): String? = runCatching {
        val pushback = PushbackInputStream(stream.buffered(), PREAMBLE_PROBE_BYTES)
        val probe = ByteArray(PREAMBLE_PROBE_BYTES)
        val read = pushback.read(probe)
        if (read <= 0) return@runCatching null
        val zipStart = probe.localHeaderOffset(read)
        if (zipStart < 0) return@runCatching null
        pushback.unread(probe, zipStart, read - zipStart)
        ZipInputStream(pushback).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    return@use zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun ByteArray.localHeaderOffset(length: Int): Int {
        for (index in 0..(length - 4)) {
            val signature = (this[index].toInt() and 0xFF) or
                ((this[index + 1].toInt() and 0xFF) shl 8) or
                ((this[index + 2].toInt() and 0xFF) shl 16) or
                ((this[index + 3].toInt() and 0xFF) shl 24)
            if (signature == LOCAL_FILE_HEADER_SIGNATURE) return index
        }
        return -1
    }
}
