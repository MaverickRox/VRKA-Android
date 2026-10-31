package com.mvrk.vrka.update.security

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseManifest(
    val schema: String,
    val schemaVersion: Int,
    val metadataRevision: Long,
    val channel: String,
    val repository: RepositoryInfo,
    val releaseTag: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val sourceCommit: String,
    val issuedAt: String,
    val expiresAt: String,
    val minUpdaterVersion: String? = null,
    val apk: ApkManifestInfo,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", schema)
        root.put("schema_version", schemaVersion)
        root.put("metadata_revision", metadataRevision)
        root.put("channel", channel)

        val repoObj = JSONObject()
        repoObj.put("owner", repository.owner)
        repoObj.put("name", repository.name)
        root.put("repository", repoObj)

        root.put("release_tag", releaseTag)
        root.put("application_id", applicationId)
        root.put("version_name", versionName)
        root.put("version_code", versionCode)
        root.put("source_commit", sourceCommit)
        root.put("issued_at", issuedAt)
        root.put("expires_at", expiresAt)
        if (minUpdaterVersion != null) {
            root.put("min_updater_version", minUpdaterVersion)
        }

        val apkObj = JSONObject()
        apkObj.put("filename", apk.filename)
        apkObj.put("size_bytes", apk.sizeBytes)
        apkObj.put("sha256", apk.sha256)
        apkObj.put("certificate_sha256", apk.certificateSha256)
        apkObj.put("min_sdk", apk.minSdk)
        apkObj.put("target_sdk", apk.targetSdk)

        val abisArray = JSONArray()
        apk.supportedAbis.forEach { abisArray.put(it) }
        apkObj.put("supported_abis", abisArray)

        root.put("apk", apkObj)
        return root.toString(2)
    }

    companion object {
        const val CURRENT_SCHEMA = "https://vrka.org/schema/release-manifest/v1.json"
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_MANIFEST_SIZE_BYTES = 64 * 1024 // 64 KB limit

        fun fromJson(jsonStr: String): ReleaseManifest {
            val json = JSONObject(jsonStr)

            val schema = json.optString("schema", "")
            val schemaVersion = json.optInt("schema_version", -1)
            val metadataRevision = json.optLong("metadata_revision", -1L)
            val channel = json.optString("channel", "")

            val repoJson = json.optJSONObject("repository")
                ?: throw ManifestValidationException("Manifest missing 'repository' object")
            val repoOwner = repoJson.optString("owner", "")
            val repoName = repoJson.optString("name", "")

            val releaseTag = json.optString("release_tag", "")
            val applicationId = json.optString("application_id", "")
            val versionName = json.optString("version_name", "")
            val versionCode = json.optLong("version_code", -1L)
            val sourceCommit = json.optString("source_commit", "")
            val issuedAt = json.optString("issued_at", "")
            val expiresAt = json.optString("expires_at", "")
            val minUpdaterVersion = if (json.has("min_updater_version")) json.optString("min_updater_version") else null

            val apkJson = json.optJSONObject("apk")
                ?: throw ManifestValidationException("Manifest missing 'apk' object")
            val apkFilename = apkJson.optString("filename", "")
            val apkSizeBytes = apkJson.optLong("size_bytes", -1L)
            val apkSha256 = apkJson.optString("sha256", "")
            val certSha256 = apkJson.optString("certificate_sha256", "")
            val minSdk = apkJson.optInt("min_sdk", 26)
            val targetSdk = apkJson.optInt("target_sdk", 36)

            val abisArray = apkJson.optJSONArray("supported_abis")
            val supportedAbis = if (abisArray != null) {
                (0 until abisArray.length()).map { abisArray.getString(it) }
            } else {
                emptyList()
            }

            return ReleaseManifest(
                schema = schema,
                schemaVersion = schemaVersion,
                metadataRevision = metadataRevision,
                channel = channel,
                repository = RepositoryInfo(repoOwner, repoName),
                releaseTag = releaseTag,
                applicationId = applicationId,
                versionName = versionName,
                versionCode = versionCode,
                sourceCommit = sourceCommit,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                minUpdaterVersion = minUpdaterVersion,
                apk = ApkManifestInfo(
                    filename = apkFilename,
                    sizeBytes = apkSizeBytes,
                    sha256 = apkSha256,
                    certificateSha256 = certSha256,
                    minSdk = minSdk,
                    targetSdk = targetSdk,
                    supportedAbis = supportedAbis,
                ),
            )
        }
    }
}

data class RepositoryInfo(
    val owner: String,
    val name: String,
)

data class ApkManifestInfo(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val certificateSha256: String,
    val minSdk: Int = 26,
    val targetSdk: Int = 36,
    val supportedAbis: List<String> = listOf("arm64-v8a"),
)
