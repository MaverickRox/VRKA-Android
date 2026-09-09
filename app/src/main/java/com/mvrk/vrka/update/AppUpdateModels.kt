package com.mvrk.vrka.update

import java.io.File

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        return patch.compareTo(other.patch)
    }

    fun isNewerThan(other: SemanticVersion): Boolean = this > other

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val SEMVER_REGEX = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

        fun parseOrNull(versionStr: String?): SemanticVersion? {
            if (versionStr.isNullOrBlank()) return null
            val match = SEMVER_REGEX.matchEntire(versionStr.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            return SemanticVersion(major, minor, patch)
        }
    }
}

data class AppReleaseInfo(
    val tagName: String,
    val version: SemanticVersion,
    val name: String,
    val body: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
)

sealed interface AppUpdateCheckState {
    data object Idle : AppUpdateCheckState
    data object Checking : AppUpdateCheckState
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateCheckState
    data class UpToDate(val currentVersion: String) : AppUpdateCheckState
    data class Error(val message: String) : AppUpdateCheckState
}

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateDownloadState
    data class ReadyToInstall(val file: File) : AppUpdateDownloadState
    data object Installing : AppUpdateDownloadState
    data class Error(val message: String) : AppUpdateDownloadState
}
