package com.mvrk.vrka

import java.io.File
import java.util.zip.ZipFile

/** Reads a version from the installed yt-dlp zipapp rather than assuming the bundled version. */
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

    fun parseYtDlpVersion(versionSource: String): String? =
        versionAssignment.find(versionSource)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
}
