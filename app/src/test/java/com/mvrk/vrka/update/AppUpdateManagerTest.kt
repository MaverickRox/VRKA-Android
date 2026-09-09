package com.mvrk.vrka.update

import com.mvrk.vrka.ui.parseMarkdownToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testSemanticVersionParsing() {
        val v1 = SemanticVersion.parseOrNull("v4.5.1")
        assertNotNull(v1)
        assertEquals(4, v1!!.major)
        assertEquals(5, v1.minor)
        assertEquals(1, v1.patch)

        val v2 = SemanticVersion.parseOrNull("4.5.2")
        assertNotNull(v2)
        assertEquals(4, v2!!.major)
        assertEquals(5, v2.minor)
        assertEquals(2, v2.patch)

        val v3 = SemanticVersion.parseOrNull("v4.5")
        assertNotNull(v3)
        assertEquals(4, v3!!.major)
        assertEquals(5, v3.minor)
        assertEquals(0, v3.patch)

        val v4 = SemanticVersion.parseOrNull("v4.5.10")
        assertNotNull(v4)
        assertEquals(4, v4!!.major)
        assertEquals(5, v4.minor)
        assertEquals(10, v4.patch)

        val v5 = SemanticVersion.parseOrNull("5.0.0-rc1")
        assertNotNull(v5)
        assertEquals(5, v5!!.major)
        assertEquals(0, v5.minor)
        assertEquals(0, v5.patch)

        assertNull(SemanticVersion.parseOrNull(null))
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("invalid.version"))
    }

    @Test
    fun testSemanticVersionComparison() {
        val current = SemanticVersion(4, 5, 1)

        // Same version
        assertEquals(0, current.compareTo(SemanticVersion(4, 5, 1)))
        assertFalse(current.isNewerThan(SemanticVersion(4, 5, 1)))

        // Patch increments
        assertTrue(SemanticVersion(4, 5, 2).isNewerThan(current))
        assertFalse(current.isNewerThan(SemanticVersion(4, 5, 2)))

        // Two-digit patch increments (4.5.9 < 4.5.10)
        assertTrue(SemanticVersion(4, 5, 10).isNewerThan(SemanticVersion(4, 5, 9)))
        assertFalse(SemanticVersion(4, 5, 9).isNewerThan(SemanticVersion(4, 5, 10)))

        // Minor increments
        assertTrue(SemanticVersion(4, 6, 0).isNewerThan(current))
        assertTrue(SemanticVersion(4, 6, 0).isNewerThan(SemanticVersion(4, 5, 99)))

        // Major increments (4.9.0 < 5.0.0)
        assertTrue(SemanticVersion(5, 0, 0).isNewerThan(SemanticVersion(4, 9, 0)))
        assertTrue(SemanticVersion(5, 0, 0).isNewerThan(current))

        // Older versions
        assertFalse(SemanticVersion(4, 5, 0).isNewerThan(current))
        assertFalse(SemanticVersion(4, 4, 9).isNewerThan(current))
        assertFalse(SemanticVersion(3, 9, 9).isNewerThan(current))
    }

    @Test
    fun testParseReleaseJsonValidSingleRelease() {
        val json = """
            {
                "tag_name": "v4.5.2",
                "name": "VRKA Android 4.5.2",
                "body": "## Improvements\n- Bug fixes and stability improvements\n- Better update checks",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-09-10T12:00:00Z",
                "assets": [
                    {
                        "name": "checksums.sha256",
                        "size": 128,
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/checksums.sha256"
                    },
                    {
                        "name": "VRKA-Android-v4.5.2.apk",
                        "size": 25000000,
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk"
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdateManager.parseReleaseJson(json)
        assertNotNull(release)
        assertEquals("v4.5.2", release!!.tagName)
        assertEquals(SemanticVersion(4, 5, 2), release.version)
        assertEquals("VRKA Android 4.5.2", release.name)
        assertTrue(release.body.contains("Bug fixes"))
        assertEquals("VRKA-Android-v4.5.2.apk", release.apkFileName)
        assertEquals(25000000L, release.apkSizeBytes)
        assertEquals(
            "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk",
            release.apkDownloadUrl,
        )
    }

    @Test
    fun testParseReleaseJsonFiltersDraftsAndPrereleases() {
        val draftJson = """
            {
                "tag_name": "v4.5.2",
                "draft": true,
                "prerelease": false,
                "assets": [
                    {
                        "name": "VRKA-Android-v4.5.2.apk",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk"
                    }
                ]
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(draftJson))

        val prereleaseJson = """
            {
                "tag_name": "v4.5.2-beta",
                "draft": false,
                "prerelease": true,
                "assets": [
                    {
                        "name": "VRKA-Android-v4.5.2.apk",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk"
                    }
                ]
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(prereleaseJson))
    }

    @Test
    fun testParseReleaseJsonFromArrayFindsFirstStable() {
        val arrayJson = """
            [
                {
                    "tag_name": "v4.6.0-draft",
                    "draft": true,
                    "prerelease": false,
                    "assets": []
                },
                {
                    "tag_name": "v4.6.0-rc1",
                    "draft": false,
                    "prerelease": true,
                    "assets": []
                },
                {
                    "tag_name": "v4.5.2",
                    "draft": false,
                    "prerelease": false,
                    "name": "Stable 4.5.2",
                    "body": "First stable in list",
                    "published_at": "2026-09-09T00:00:00Z",
                    "assets": [
                        {
                            "name": "VRKA-Android-v4.5.2.apk",
                            "size": 30000000,
                            "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk"
                        }
                    ]
                }
            ]
        """.trimIndent()

        val release = AppUpdateManager.parseReleaseJson(arrayJson)
        assertNotNull(release)
        assertEquals("v4.5.2", release!!.tagName)
        assertEquals("Stable 4.5.2", release.name)
        assertEquals("VRKA-Android-v4.5.2.apk", release.apkFileName)
    }

    @Test
    fun testParseReleaseJsonRequiresApkAsset() {
        val noApkJson = """
            {
                "tag_name": "v4.5.2",
                "draft": false,
                "prerelease": false,
                "assets": [
                    {
                        "name": "source.tar.gz",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/source.tar.gz"
                    }
                ]
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(noApkJson))
    }

    @Test
    fun testMarkdownParser() {
        val markdown = """
            # Header 1
            ## Header 2
            ### Header 3
            - Bullet 1 with **bold** text
            * Bullet 2 with `inline code`
            > Blockquote note
            Link to [VRKA](https://github.com/MaverickRox/VRKA-Android)
        """.trimIndent()

        val annotated = parseMarkdownToAnnotatedString(markdown)
        val text = annotated.text

        assertTrue(text.contains("Header 1"))
        assertTrue(text.contains("Header 2"))
        assertTrue(text.contains("Header 3"))
        assertTrue(text.contains("• Bullet 1 with bold text"))
        assertTrue(text.contains("• Bullet 2 with  inline code "))
        assertTrue(text.contains("│ Blockquote note"))
        assertTrue(text.contains("Link to VRKA"))
    }

    @Test
    fun test24HourGateLogic() {
        val now = 1000000000000L
        val within24h = now - (12 * 60 * 60 * 1000L) // 12 hours ago
        val past24h = now - (25 * 60 * 60 * 1000L) // 25 hours ago

        assertTrue((now - within24h) < AppUpdateManager.TWENTY_FOUR_HOURS_MS)
        assertFalse((now - past24h) < AppUpdateManager.TWENTY_FOUR_HOURS_MS)
    }

    @Test
    fun testParseReleaseJsonRejectsUnrelatedAndNonMatchingApkAssets() {
        val invalidAssetsJson = """
            {
                "tag_name": "v4.5.2",
                "draft": false,
                "prerelease": false,
                "assets": [
                    {
                        "name": "other-app.apk",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/other-app.apk"
                    },
                    {
                        "name": "VRKA-Android-arm64-v8a.apk",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-arm64-v8a.apk"
                    },
                    {
                        "name": "VRKA-Android-v4.5.2.zip",
                        "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.zip"
                    }
                ]
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(invalidAssetsJson))
    }

    @Test
    fun testValidateHttpsUrlApprovedHosts() {
        val approvedUrls = listOf(
            "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest",
            "https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.1/VRKA-Android-v4.5.1.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/12345/file.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/67890/file.apk",
            "https://raw.githubusercontent.com/MaverickRox/VRKA-Android/main/README.md",
        )

        for (urlStr in approvedUrls) {
            val validated = AppUpdateManager.validateHttpsUrl(urlStr)
            assertEquals("https", validated.protocol)
            assertTrue(AppUpdateManager.isApprovedHost(validated.host))
        }
    }

    @Test(expected = SecurityException::class)
    fun testValidateHttpsUrlRejectsInsecureHttp() {
        AppUpdateManager.validateHttpsUrl("http://github.com/MaverickRox/VRKA-Android/releases/latest")
    }

    @Test(expected = SecurityException::class)
    fun testValidateHttpsUrlRejectsArbitraryHost() {
        AppUpdateManager.validateHttpsUrl("https://evil-attacker.com/malicious.apk")
    }

    @Test(expected = SecurityException::class)
    fun testValidateHttpsUrlRejectsHostSpoofing() {
        AppUpdateManager.validateHttpsUrl("https://github.com.evil.com/fake.apk")
    }

    @Test
    fun testResolveRedirectUrl() {
        val base = java.net.URL("https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/VRKA-Android-v4.5.2.apk")

        val absolute = AppUpdateManager.resolveRedirectUrl(base, "https://objects.githubusercontent.com/asset.apk")
        assertEquals("https://objects.githubusercontent.com/asset.apk", absolute)

        val relative = AppUpdateManager.resolveRedirectUrl(base, "/MaverickRox/VRKA-Android/releases/download/v4.5.2/redirected.apk")
        assertEquals("https://github.com/MaverickRox/VRKA-Android/releases/download/v4.5.2/redirected.apk", relative)
    }
}
