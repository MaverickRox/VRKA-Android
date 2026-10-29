package com.mvrk.vrka.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuntimeSourceTest {

    @Test
    fun `normalizes accepted repository forms`() {
        assertEquals("owner/repo", CustomRuntimeSource.normalizeRepo("owner/repo"))
        assertEquals("owner/repo", CustomRuntimeSource.normalizeRepo("  https://github.com/owner/repo/  "))
        assertEquals("owner/repo", CustomRuntimeSource.normalizeRepo("https://github.com/owner/repo.git"))
        assertEquals("owner/repo", CustomRuntimeSource.normalizeRepo("github.com/owner/repo"))
    }

    @Test
    fun `rejects non github hosts and malformed paths`() {
        assertNull(CustomRuntimeSource.normalizeRepo("http://github.com/owner/repo"))
        assertNull(CustomRuntimeSource.normalizeRepo("https://gitlab.com/owner/repo"))
        assertNull(CustomRuntimeSource.normalizeRepo("https://github.com.evil.example/owner/repo"))
        assertNull(CustomRuntimeSource.normalizeRepo("owner/repo/extra"))
        assertNull(CustomRuntimeSource.normalizeRepo("owner"))
        assertNull(CustomRuntimeSource.normalizeRepo("owner/../repo"))
        assertNull(CustomRuntimeSource.normalizeRepo("javascript:alert(1)"))
        assertNull(CustomRuntimeSource.normalizeRepo(""))
    }

    @Test
    fun `parses releases and prefers stable compatible assets`() {
        val releases = CustomRuntimeSource.parseReleases(SAMPLE_RELEASES)
        assertEquals(2, releases.size)
        val selected = CustomRuntimeSource.selectCompatible(releases)
        assertEquals("2026.09.01", selected?.tag)
        assertEquals(CustomRuntimeSource.ASSET_NAME, selected?.asset?.name)
        assertTrue(selected?.asset?.downloadUrl?.startsWith("https://github.com/") == true)
    }

    @Test
    fun `prerelease is used only when no stable release is compatible`() {
        val release = CustomRuntimeSource.selectCompatible(
            CustomRuntimeSource.parseReleases(SAMPLE_PRERELEASE_ONLY),
        )
        assertEquals("2026.10.01-rc1", release?.tag)
        assertTrue(release?.prerelease == true)
    }

    @Test
    fun `releases without the python zipapp asset are skipped`() {
        val releases = CustomRuntimeSource.parseReleases(SAMPLE_PLATFORM_ONLY)
        assertTrue(releases.isEmpty())
        assertNull(CustomRuntimeSource.selectCompatible(releases))
    }

    @Test
    fun `draft releases and unsafe tags are ignored`() {
        val releases = CustomRuntimeSource.parseReleases(SAMPLE_DRAFT_AND_UNSAFE)
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `asset outside github download host is ignored`() {
        val releases = CustomRuntimeSource.parseReleases(SAMPLE_FOREIGN_HOST)
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `unusable asset size makes release incompatible`() {
        val release = CustomRuntimeSource.selectCompatible(
            CustomRuntimeSource.parseReleases(SAMPLE_OVERSIZED),
        )
        assertNull(release)
        assertFalse(CustomRuntimeSource.MAX_RUNTIME_BYTES <= 0)
    }

    private companion object {
        const val SAMPLE_RELEASES = """
        [
          {
            "tag_name": "2026.10.01-rc1",
            "prerelease": true,
            "draft": false,
            "published_at": "2026-10-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.10.01-rc1/yt-dlp", "size": 3000000}
            ]
          },
          {
            "tag_name": "2026.09.01",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-09-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp_linux", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.09.01/yt-dlp_linux", "size": 20000000},
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.09.01/yt-dlp", "size": 3000000}
            ]
          }
        ]
        """

        const val SAMPLE_PRERELEASE_ONLY = """
        [
          {
            "tag_name": "2026.10.01-rc1",
            "prerelease": true,
            "draft": false,
            "published_at": "2026-10-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.10.01-rc1/yt-dlp", "size": 3000000}
            ]
          }
        ]
        """

        const val SAMPLE_PLATFORM_ONLY = """
        [
          {
            "tag_name": "2026.09.01",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-09-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp_linux", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.09.01/yt-dlp_linux", "size": 20000000}
            ]
          }
        ]
        """

        const val SAMPLE_DRAFT_AND_UNSAFE = """
        [
          {
            "tag_name": "2026.09.01",
            "prerelease": false,
            "draft": true,
            "published_at": "2026-09-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.09.01/yt-dlp", "size": 3000000}
            ]
          },
          {
            "tag_name": "../../escape",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-08-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/x/yt-dlp", "size": 3000000}
            ]
          }
        ]
        """

        const val SAMPLE_FOREIGN_HOST = """
        [
          {
            "tag_name": "2026.09.01",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-09-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://evil.example/yt-dlp", "size": 3000000}
            ]
          }
        ]
        """

        const val SAMPLE_OVERSIZED = """
        [
          {
            "tag_name": "2026.09.01",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-09-01T00:00:00Z",
            "assets": [
              {"name": "yt-dlp", "browser_download_url": "https://github.com/fork/yt-dlp/releases/download/2026.09.01/yt-dlp", "size": 999999999}
            ]
          }
        ]
        """
    }
}
