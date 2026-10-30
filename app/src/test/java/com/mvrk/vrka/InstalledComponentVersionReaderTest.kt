package com.mvrk.vrka

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun readsVersionFromAShebangPrefixedStream() {
        // The shipped zipapp is a self-executing archive: a `#!` line precedes the zip data.
        // Android rejects that prefix when the file is opened as a ZipFile, so the reader must
        // skip the launcher line itself.
        val archive = File.createTempFile("ytdlp-shebang", ".zip")
        val prefixed = File.createTempFile("ytdlp-prefixed", ".pyz")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("yt_dlp/version.py"))
                zip.write("__version__ = '2026.08.19'\n".toByteArray())
                zip.closeEntry()
            }
            prefixed.outputStream().use { out ->
                out.write("#!/usr/bin/env python3\n".toByteArray())
                archive.inputStream().use { it.copyTo(out) }
            }
            prefixed.inputStream().use { stream ->
                assertEquals("2026.08.19", InstalledComponentVersionReader.readYtDlpVersion(stream))
            }
            assertEquals("2026.08.19", InstalledComponentVersionReader.readYtDlpVersion(prefixed))
        } finally {
            archive.delete()
            prefixed.delete()
        }
    }

    @Test
    fun readsExtensionVersionFromTheManifestEntryOnly() {
        val archive = File.createTempFile("ublock-test", ".xpi")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"manifest_version":2,"name":"uBlock Origin","version":"1.74.0"}""".toByteArray())
                zip.closeEntry()
                // A large trailing entry proves the reader stops at the manifest boundary.
                zip.putNextEntry(ZipEntry("content.js"))
                zip.write(ByteArray(64 * 1024) { 'x'.code.toByte() })
                zip.closeEntry()
            }

            archive.inputStream().use { stream ->
                assertEquals("1.74.0", InstalledComponentVersionReader.readExtensionVersion(stream))
            }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun missingManifestYieldsNullInsteadOfAGuess() {
        val archive = File.createTempFile("no-manifest", ".xpi")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("install.rdf"))
                zip.write("<RDF/>".toByteArray())
                zip.closeEntry()
            }
            archive.inputStream().use { stream ->
                assertNull(InstalledComponentVersionReader.readExtensionVersion(stream))
            }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun bundledUBlockXpiExposesItsManifestVersion() {
        val bundledXpi = File("src/main/assets/extensions/ublock.xpi")
        assertEquals("Bundled uBlock XPI must remain readable", true, bundledXpi.isFile)
        val version = bundledXpi.inputStream().use { stream ->
            InstalledComponentVersionReader.readExtensionVersion(stream)
        }
        assertNotNull("Bundled uBlock manifest must declare a version", version)
        assertEquals("1.74.0", version)
    }
}

