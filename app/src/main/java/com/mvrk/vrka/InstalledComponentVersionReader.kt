package com.mvrk.vrka

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Reads a version from the installed or bundled yt-dlp zipapp rather than assuming a hard-coded value. */
internal object InstalledComponentVersionReader {
    private val versionAssignment = Regex("""(?m)^\s*__version__\s*=\s*['\"]([^'\"]+)['\"]""")

    fun readYtDlpVersion(zipApp: File): String? = runCatching {
        ZipFile(zipApp).use { archive ->
            val versionFile = archive.getEntry("yt_dlp/version.py") ?: return@use null
            archive.getInputStream(versionFile).bufferedReader(Charsets.UTF_8).use { reader ->
                parseYtDlpVersion(reader.readText())
            }
        }
    }.getOrNull()

    fun readYtDlpVersion(stream: InputStream): String? = runCatching {
        val temp = File.createTempFile("ytdlp-read", ".tmp")
        try {
            temp.outputStream().use { out -> stream.copyTo(out) }
            readYtDlpVersion(temp)
        } finally {
            temp.delete()
        }
    }.getOrNull()


    fun readBundledYtDlpVersion(context: Context): String? = runCatching {
        context.resources.openRawResource(R.raw.ytdlp).use { stream ->
            readYtDlpVersion(stream)
        }
    }.getOrNull()

    fun parseYtDlpVersion(versionSource: String): String? =
        versionAssignment.find(versionSource)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
}

