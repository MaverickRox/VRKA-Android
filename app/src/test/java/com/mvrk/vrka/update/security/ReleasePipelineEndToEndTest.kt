package com.mvrk.vrka.update.security

import com.mvrk.vrka.update.AppUpdateManager
import org.bouncycastle.openpgp.PGPPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReleasePipelineEndToEndTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var pinnedPublicKey: PGPPublicKey
    private lateinit var verifier: ReleaseManifestVerifier

    private val v520ManifestJson = """
        {
          "schema": "https://vrka.org/schema/release-manifest/v1.json",
          "schema_version": 1,
          "metadata_revision": 2,
          "channel": "stable",
          "repository": {
            "owner": "MaverickRox",
            "name": "VRKA-Android"
          },
          "release_tag": "v5.2.0",
          "application_id": "com.mvrk.vrka",
          "version_name": "5.2.0",
          "version_code": 50200,
          "source_commit": "abcdef1234567890abcdef1234567890abcdef12",
          "issued_at": "2026-11-01T00:00:00Z",
          "expires_at": "2027-11-01T00:00:00Z",
          "min_updater_version": "5.0.0",
          "apk": {
            "filename": "VRKA-Android-v5.2.0.apk",
            "size_bytes": 158000000,
            "sha256": "44a034b5c7e1f4a01234567890abcdef1234567890abcdef1234567890abcdef",
            "certificate_sha256": "9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA",
            "min_sdk": 26,
            "target_sdk": 36,
            "supported_abis": ["arm64-v8a"]
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        ReleaseManifestVerifier.ensureBouncyCastleProvider()
        val pubKeyFile = File("src/main/res/raw/vrka_release_pubkey.asc")
        val input = if (pubKeyFile.exists()) {
            FileInputStream(pubKeyFile)
        } else {
            File("app/src/main/res/raw/vrka_release_pubkey.asc").inputStream()
        }
        pinnedPublicKey = ReleaseManifestVerifier.loadAndVerifyPublicKey(input)
        verifier = ReleaseManifestVerifier(customPublicKeySupplier = { pinnedPublicKey })
    }

    @Test
    fun testFutureReleaseV520ManifestVerificationAndDomainValidation() {
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val manifestBytes = v520ManifestJson.toByteArray()
            val sigBytes = ReleaseManifestSigner.signDetached(
                dataBytes = manifestBytes,
                secretKeyStream = FileInputStream(secretKeyFile),
            )

            // Current installed app is v5.1.0 (versionCode 50100, revision 1)
            val manifest = verifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = sigBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50100L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.2.0",
                currentTimeMillis = 1793494800000L, // 2026-11-01T01:00:00Z
            )

            assertEquals("5.2.0", manifest.versionName)
            assertEquals(50200L, manifest.versionCode)
            assertEquals(2L, manifest.metadataRevision)
            assertEquals("v5.2.0", manifest.releaseTag)
            assertEquals("VRKA-Android-v5.2.0.apk", manifest.apk.filename)
            assertEquals(
                "9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA",
                manifest.apk.certificateSha256,
            )
        }
    }

    @Test
    fun testMockGitHubReleaseAssetDiscovery() {
        val mockReleaseJson = """
            {
              "tag_name": "v5.2.0",
              "name": "VRKA v5.2.0",
              "body": "VRKA v5.2.0 release notes",
              "assets": [
                {
                  "name": "VRKA-Android-v5.2.0.apk",
                  "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v5.2.0/VRKA-Android-v5.2.0.apk",
                  "size": 158000000
                },
                {
                  "name": "VRKA-Android-v5.2.0.manifest.json",
                  "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v5.2.0/VRKA-Android-v5.2.0.manifest.json",
                  "size": 750
                },
                {
                  "name": "VRKA-Android-v5.2.0.manifest.json.asc",
                  "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v5.2.0/VRKA-Android-v5.2.0.manifest.json.asc",
                  "size": 833
                },
                {
                  "name": "SHA256SUMS",
                  "browser_download_url": "https://github.com/MaverickRox/VRKA-Android/releases/download/v5.2.0/SHA256SUMS",
                  "size": 256
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseReleaseJson(mockReleaseJson)
        assertNotNull(parsed)
        assertEquals("v5.2.0", parsed!!.tagName)
        assertEquals("VRKA-Android-v5.2.0.apk", parsed.apkFileName)
        assertEquals(158000000L, parsed.apkSizeBytes)
        assertNotNull(parsed.manifestDownloadUrl)
        assertTrue(parsed.manifestDownloadUrl!!.endsWith("VRKA-Android-v5.2.0.manifest.json"))
        assertNotNull(parsed.signatureDownloadUrl)
        assertTrue(parsed.signatureDownloadUrl!!.endsWith("VRKA-Android-v5.2.0.manifest.json.asc"))
    }

    @Test
    fun testDownloadStagingLifecycleAndFilenameVerificationSeparation() {
        val downloadDir = tempFolder.newFolder("downloads")
        val tempFile = File(downloadDir, "app-update-50200.download.tmp")
        val targetFile = File(downloadDir, "VRKA-Android-v5.2.0.apk")

        // Create a minimal valid zip file representing an APK
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("dummy manifest".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dummy dex".toByteArray())
            zos.closeEntry()
        }

        assertTrue(tempFile.exists())
        assertEquals("app-update-50200.download.tmp", tempFile.name)

        // 1. Verify that checking staged filename on the temp file FAILS (this was the original bug!)
        val canonicalExpectedName = "VRKA-Android-v5.2.0.apk"
        val tempFileNameMatchesStaged = (tempFile.canonicalFile.name == canonicalExpectedName)
        assertFalse(
            "Original defect check: tempFile name does not match canonical name",
            tempFileNameMatchesStaged,
        )

        // 2. Perform atomic move / rename from tempFile to targetFile (as done by AppUpdateDownloadWorker)
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )

        assertFalse(tempFile.exists())
        assertTrue(targetFile.exists())

        // 3. Staged verification on final file SUCCEEDS
        val stagedFileNameMatches = (targetFile.canonicalFile.name == canonicalExpectedName)
        assertTrue(
            "Staged verification succeeds on renamed file",
            stagedFileNameMatches,
        )
    }

    @Test
    fun testAntiRollbackAndAntiDowngradeProtection() {
        val manifest = ReleaseManifest.fromJson(v520ManifestJson)

        // Installed version is 50300 (attempting downgrade to 50200)
        assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50300L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.2.0",
                currentTimeMillis = 1793494800000L,
            )
        }

        // Stored metadata revision is 3 (attempting rollback to revision 2)
        assertThrows(ManifestValidationException::class.java) {
            verifier.validateManifestData(
                manifest = manifest,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50100L,
                currentStoredRevision = 3L,
                expectedReleaseTag = "v5.2.0",
                currentTimeMillis = 1793494800000L,
            )
        }
    }
}
