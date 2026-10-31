package com.mvrk.vrka.update.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestTest {

    private fun validManifestJson(): String = """
        {
          "schema": "https://vrka.org/schema/release-manifest/v1.json",
          "schema_version": 1,
          "metadata_revision": 1,
          "channel": "stable",
          "repository": {
            "owner": "MaverickRox",
            "name": "VRKA-Android"
          },
          "release_tag": "v5.1.0",
          "application_id": "com.mvrk.vrka",
          "version_name": "5.1.0",
          "version_code": 50100,
          "source_commit": "1794d38f4c2505d5cf07705b1f23cc10581f1341",
          "issued_at": "2026-11-01T00:00:00Z",
          "expires_at": "2027-11-01T00:00:00Z",
          "min_updater_version": "5.0.0",
          "apk": {
            "filename": "VRKA-Android-v5.1.0.apk",
            "size_bytes": 157192535,
            "sha256": "ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a",
            "certificate_sha256": "9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA",
            "min_sdk": 26,
            "target_sdk": 36,
            "supported_abis": ["arm64-v8a"]
          }
        }
    """.trimIndent()

    @Test
    fun testValidManifestParsesAndSerializesCorrectly() {
        val json = validManifestJson()
        val manifest = ReleaseManifest.fromJson(json)

        assertEquals("https://vrka.org/schema/release-manifest/v1.json", manifest.schema)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(1L, manifest.metadataRevision)
        assertEquals("stable", manifest.channel)
        assertEquals("MaverickRox", manifest.repository.owner)
        assertEquals("VRKA-Android", manifest.repository.name)
        assertEquals("v5.1.0", manifest.releaseTag)
        assertEquals("com.mvrk.vrka", manifest.applicationId)
        assertEquals("5.1.0", manifest.versionName)
        assertEquals(50100L, manifest.versionCode)
        assertEquals("1794d38f4c2505d5cf07705b1f23cc10581f1341", manifest.sourceCommit)
        assertEquals("VRKA-Android-v5.1.0.apk", manifest.apk.filename)
        assertEquals(157192535L, manifest.apk.sizeBytes)
        assertEquals("ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a", manifest.apk.sha256)
        assertEquals("9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA", manifest.apk.certificateSha256)
        assertEquals(listOf("arm64-v8a"), manifest.apk.supportedAbis)

        // Test round-trip serialization
        val serialized = manifest.toJson()
        val parsedAgain = ReleaseManifest.fromJson(serialized)
        assertEquals(manifest, parsedAgain)
    }

    @Test
    fun testRejectsMalformedJson() {
        assertThrows(Exception::class.java) {
            ReleaseManifest.fromJson("{ not valid json }")
        }
    }

    @Test
    fun testRejectsMissingRepository() {
        val modified = validManifestJson().replace(Regex(""""repository"\s*:\s*\{[^}]+\},"""), "")
        assertThrows(ManifestValidationException::class.java) {
            ReleaseManifest.fromJson(modified)
        }
    }

    @Test
    fun testRejectsMissingApkObject() {
        val modified = validManifestJson().replace(Regex(""""apk"\s*:\s*\{[^}]+\}"""), "")
        assertThrows(ManifestValidationException::class.java) {
            ReleaseManifest.fromJson(modified)
        }
    }

    @Test
    fun testDomainValidationAcceptsValidManifest() {
        val manifest = ReleaseManifest.fromJson(validManifestJson())
        val verifier = ReleaseManifestVerifier()

        // Current time: 2026-11-01T01:00:00Z (within 1 hour of issued_at)
        val testNow = 1793494800000L // 2026-11-01T01:00:00Z
        verifier.validateManifestData(
            manifest = manifest,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L, // upgrading from 5.0.2
            currentStoredRevision = 1L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = testNow,
        )
    }

    @Test
    fun testDomainValidationRejectsUnsupportedSchema() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(schema = "https://evil.org/schema/v99.json")
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Unsupported manifest schema"))
    }

    @Test
    fun testDomainValidationRejectsNonStableChannel() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(channel = "nightly")
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Unsupported release channel"))
    }

    @Test
    fun testDomainValidationRejectsRepositoryMismatch() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(repository = RepositoryInfo("Impostor", "VRKA-Android"))
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Repository mismatch"))
    }

    @Test
    fun testDomainValidationRejectsReleaseTagMismatch() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(releaseTag = "v5.2.0")
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Release tag mismatch"))
    }

    @Test
    fun testDomainValidationRejectsApplicationIdMismatch() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(applicationId = "com.evil.malware")
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Application ID mismatch"))
    }

    @Test
    fun testDomainValidationRejectsMetadataRevisionRollback() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(metadataRevision = 2L)
        val verifier = ReleaseManifestVerifier()
        // Stored revision is 3, candidate is 2 -> rollback attempt
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 3L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Metadata revision rollback detected"))
    }

    @Test
    fun testDomainValidationAcceptsHigherMetadataRevision() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(metadataRevision = 5L)
        val verifier = ReleaseManifestVerifier()
        val testNow = 1793494800000L
        verifier.validateManifestData(
            manifest = manifest,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L,
            currentStoredRevision = 4L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = testNow,
        )
    }

    @Test
    fun testDomainValidationRejectsVersionCodeDowngrade() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(versionCode = 50000L)
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L, // installed is 50002
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Version code downgrade rejected"))
    }

    @Test
    fun testDomainValidationRejectsMismatchedApkFilename() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            apk = ReleaseManifest.fromJson(validManifestJson()).apk.copy(filename = "wrong-name.apk")
        )
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Manifest APK filename"))
    }

    @Test
    fun testDomainValidationRejectsZeroOrNegativeApkSize() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            apk = ReleaseManifest.fromJson(validManifestJson()).apk.copy(sizeBytes = 0L)
        )
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Invalid manifest APK size"))
    }

    @Test
    fun testDomainValidationRejectsMalformedSha256() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            apk = ReleaseManifest.fromJson(validManifestJson()).apk.copy(sha256 = "invalid_hash")
        )
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Invalid manifest APK SHA-256 hash"))
    }

    @Test
    fun testDomainValidationRejectsMismatchedCertificateSha256() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            apk = ReleaseManifest.fromJson(validManifestJson()).apk.copy(certificateSha256 = "DEADBEEF".repeat(8))
        )
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Signing certificate mismatch"))
    }

    @Test
    fun testDomainValidationRejectsMissingArm64Abi() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            apk = ReleaseManifest.fromJson(validManifestJson()).apk.copy(supportedAbis = listOf("x86_64"))
        )
        val verifier = ReleaseManifestVerifier()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("Manifest does not declare support for required ABI 'arm64-v8a'"))
    }

    @Test
    fun testDomainValidationRejectsFutureIssuedTimestamp() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            issuedAt = "2026-11-01T12:00:00Z"
        )
        val verifier = ReleaseManifestVerifier()
        // Current time: 2026-11-01T00:00:00Z (more than 1 hour in the past compared to issuedAt)
        val testNow = 1793491200000L
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
                currentTimeMillis = testNow,
            )
        }
        assertTrue(err.message!!.contains("Manifest issued_at timestamp is in the future"))
    }

    @Test
    fun testDomainValidationRejectsExpiredMetadata() {
        val manifest = ReleaseManifest.fromJson(validManifestJson()).copy(
            expiresAt = "2026-10-01T00:00:00Z"
        )
        val verifier = ReleaseManifestVerifier()
        val testNow = 1793494800000L // 2026-11-01 (later than expiresAt)
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
                currentTimeMillis = testNow,
            )
        }
        assertTrue(err.message!!.contains("Release metadata has expired"))
    }
}
