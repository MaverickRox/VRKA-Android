package com.mvrk.vrka

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-install version detection, exercised against the artifacts actually packaged in the
 * APK. A fresh install has no cached version and no runtime record, so the bundled metadata is
 * the only thing standing between "Installed: <version>" and "Installed: Unavailable".
 */
@RunWith(AndroidJUnit4::class)
class ComponentFirstInstallVersionTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledYtDlpZipAppYieldsAReleaseVersionOnDevice() {
        val bundled = InstalledComponentVersionReader.readBundledYtDlpVersion(context)
        assertNotNull("The bundled yt-dlp zipapp must expose yt_dlp/version.py", bundled)
        assertTrue("Bundled yt-dlp version must look like a release: $bundled", RELEASE.matches(bundled!!))
    }

    @Test
    fun bundledUBlockXpiYieldsItsManifestVersionOnDevice() {
        val bundled = InstalledComponentVersionReader.readBundledExtensionVersion(context, "ublock.xpi")
        assertNotNull("The bundled uBlock XPI must expose manifest.json", bundled)
        assertTrue("Bundled uBlock version must look like a release: $bundled", SEMVER.matches(bundled!!))
    }

    @Test
    fun firstInstallResolvesFromMetadataWithoutAnyCache() {
        val resolved = ComponentVersionPriority.resolve(
            { null },
            { InstalledComponentVersionReader.readBundledYtDlpVersion(context) },
            { null },
            { null },
        )
        assertNotNull("First install must resolve the shipped yt-dlp version", resolved)
        assertFalse(
            "A resolved version must never be the Unknown placeholder",
            resolved.equals("Unknown", ignoreCase = true),
        )
        assertEquals(
            InstalledComponentVersionReader.readBundledYtDlpVersion(context),
            resolved,
        )
    }

    @Test
    fun firstInstallUBlockResolutionMatchesTheBundledManifest() {
        val resolved = ComponentVersionPriority.resolve(
            { null },
            { InstalledComponentVersionReader.readBundledExtensionVersion(context, "ublock.xpi") },
        )
        assertEquals(
            InstalledComponentVersionReader.readBundledExtensionVersion(context, "ublock.xpi"),
            resolved,
        )
    }

    private companion object {
        val RELEASE = Regex("""\d{4}\.\d{2}\.\d{2}(?:\.\d+)?""")
        val SEMVER = Regex("""\d+\.\d+(?:\.\d+)*""")
    }
}
