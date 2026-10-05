package com.mvrk.vrka

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledComponentVersionReaderTest {
    @Test
    fun readsVersionFromYtDlpVersionModule() {
        val zipApp = File.createTempFile("yt-dlp-test", ".zip")
        try {
            ZipOutputStream(FileOutputStream(zipApp)).use { zip ->
                zip.putNextEntry(ZipEntry("yt_dlp/version.py"))
                zip.write("__version__ = '2026.08.19'\n".toByteArray())
                zip.closeEntry()
            }

            assertEquals("2026.08.19", InstalledComponentVersionReader.readYtDlpVersion(zipApp))
        } finally {
            zipApp.delete()
        }
    }

    @Test
    fun parsesTheVersionAssignmentWithoutHardCodingTheReleaseValue() {
        assertEquals(
            "2026.08.19",
            InstalledComponentVersionReader.parseYtDlpVersion("# generated metadata\n__version__ = \"2026.08.19\"\n"),
        )
        assertNull(InstalledComponentVersionReader.parseYtDlpVersion("__version__ = None\n"))
    }

    @Test
    fun bundledYtDlpZipAppContainsTheExpectedVersionSource() {
        val bundledZipApp = File("src/main/res/raw/ytdlp")
        assertEquals("Bundled yt-dlp version source must remain readable", true, bundledZipApp.isFile)
        assertEquals("2026.08.19", InstalledComponentVersionReader.readYtDlpVersion(bundledZipApp))
    }

    @Test
    fun readsVersionFromInputStream() {
        val bundledZipApp = File("src/main/res/raw/ytdlp")
        bundledZipApp.inputStream().use { stream ->
            assertEquals("2026.08.19", InstalledComponentVersionReader.readYtDlpVersion(stream))
        }
    }
}

