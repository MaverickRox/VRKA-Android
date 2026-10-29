/**
 * Custom yt-dlp runtime source validation and release selection.
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.runtime

import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeKind { STOCK, CUSTOM }

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class ReleaseCandidate(
    val tag: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val asset: ReleaseAsset,
)

data class CustomRuntime(
    val id: String,
    val repo: String,
    val label: String,
    val tag: String,
    val assetName: String,
    val sha256: String,
    val version: String,
    val installedAt: Long,
    val verifiedAt: Long,
)

/**
 * The python zipapp asset published by yt-dlp forks is named exactly [ASSET_NAME].
 * Platform-specific binaries (yt-dlp_linux, .exe, etc.) cannot run inside the
 * bundled Android CPython, so releases without a compatible asset are skipped.
 */
object CustomRuntimeSource {
    const val ASSET_NAME = "yt-dlp"
    const val MAX_RUNTIME_BYTES = 64L * 1024 * 1024

    private val REPO_PART = Regex("""^[A-Za-z0-9_.-]{1,100}$""")
    private val TAG = Regex("""^[A-Za-z0-9._-]{1,64}$""")

    /**
     * Accepts `owner/repo`, `https://github.com/owner/repo`, or a trailing `.git`
     * form. Anything outside github.com is rejected; no other hosts are contacted.
     */
    fun normalizeRepo(input: String): String? {
        val trimmed = input.trim()
            .removePrefix("https://")
            .removeSuffix("/")
            .removeSuffix(".git")
        val path = when {
            trimmed.startsWith("github.com/") -> trimmed.removePrefix("github.com/")
            trimmed.contains("://") || trimmed.startsWith("git@") || trimmed.contains(':') -> return null
            else -> trimmed
        }
        val parts = path.split('/')
        if (parts.size != 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (!REPO_PART.matches(owner) || !REPO_PART.matches(repo)) return null
        if (owner == "." || owner == ".." || repo == "." || repo == "..") return null
        return "$owner/$repo"
    }

    fun releasesApiUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases?per_page=30"

    fun parseReleases(json: String): List<ReleaseCandidate> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val release = array.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                val tag = release.optString("tag_name").trim()
                if (!TAG.matches(tag)) continue
                val asset = compatibleAsset(release.optJSONArray("assets")) ?: continue
                add(
                    ReleaseCandidate(
                        tag = tag,
                        prerelease = release.optBoolean("prerelease", false),
                        publishedAt = release.optString("published_at"),
                        asset = asset,
                    )
                )
            }
        }.sortedByDescending(ReleaseCandidate::publishedAt)
    }

    /**
     * Prefers the newest stable release, falling back to the newest prerelease.
     * Releases whose only assets are platform binaries are never selected.
     */
    fun selectCompatible(releases: List<ReleaseCandidate>): ReleaseCandidate? =
        releases.firstOrNull { !it.prerelease && it.asset.isUsable() }
            ?: releases.firstOrNull { it.asset.isUsable() }

    private fun compatibleAsset(assets: JSONArray?): ReleaseAsset? {
        if (assets == null) return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").trim() != ASSET_NAME) continue
            val url = asset.optString("browser_download_url").trim()
            if (!url.startsWith("https://github.com/")) continue
            return ReleaseAsset(
                name = ASSET_NAME,
                downloadUrl = url,
                size = asset.optLong("size", 0L),
            )
        }
        return null
    }

    private fun ReleaseAsset.isUsable(): Boolean =
        size > 0L && size <= MAX_RUNTIME_BYTES
}
