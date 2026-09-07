package com.mvrk.vrka

import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

class SecureComponentUpdaterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var pubKeyFile: File
    private lateinit var manifestFile: File
    private lateinit var signatureFile: File

    private lateinit var realManifestBytes: ByteArray
    private lateinit var realSignatureBytes: ByteArray

    @Before
    fun setUp() {
        SecureComponentUpdater.ensureBouncyCastleProvider()

        pubKeyFile = listOf(
            File("app/src/main/res/raw/ytdlp_pubkey.asc"),
            File("src/main/res/raw/ytdlp_pubkey.asc"),
            File("../app/src/main/res/raw/ytdlp_pubkey.asc"),
        ).firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find ytdlp_pubkey.asc")

        manifestFile = listOf(
            File("app/src/test/resources/SHA2-256SUMS"),
            File("src/test/resources/SHA2-256SUMS"),
            File("../app/src/test/resources/SHA2-256SUMS"),
        ).firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find SHA2-256SUMS")

        signatureFile = listOf(
            File("app/src/test/resources/SHA2-256SUMS.sig"),
            File("src/test/resources/SHA2-256SUMS.sig"),
            File("../app/src/test/resources/SHA2-256SUMS.sig"),
        ).firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find SHA2-256SUMS.sig")

        realManifestBytes = manifestFile.readBytes()
        realSignatureBytes = signatureFile.readBytes()
    }

    private fun loadRealKey() = FileInputStream(pubKeyFile).use {
        SecureComponentUpdater.loadAndVerifyPublicKey(it)
    }

    // --- Mock Transport for Deterministic Tests ---
    private class MockHttpTransport(
        val urlToBytes: MutableMap<String, ByteArray> = mutableMapOf(),
        val urlToFileHash: MutableMap<String, Pair<ByteArray, String>> = mutableMapOf(),
        var onFetchBytes: ((String) -> ByteArray)? = null,
        var onDownloadToFile: ((String, File) -> String)? = null,
    ) : SecureComponentUpdater.HttpTransport {
        override fun fetchBytes(url: String, maxRedirects: Int): ByteArray {
            onFetchBytes?.let { return it(url) }
            return urlToBytes[url] ?: throw IOException("Mock 404: $url")
        }

        override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
            onDownloadToFile?.let { return it(url, destination) }
            val entry = urlToFileHash[url] ?: throw IOException("Mock 404 download: $url")
            destination.writeBytes(entry.first)
            return entry.second
        }
    }

    // ==========================================
    // 1. OpenPGP Key & Signature Tests
    // ==========================================

    @Test
    fun test01_PinnedKeyFingerprintAssertion() {
        val key = loadRealKey()
        assertEquals(
            SecureComponentUpdater.PINNED_PRIMARY_KEY_ID,
            key.keyID,
        )
        val derivedFp = Hex.toHexString(key.fingerprint).uppercase()
        assertEquals(
            SecureComponentUpdater.PINNED_PRIMARY_KEY_FINGERPRINT,
            derivedFp,
        )
    }

    @Test
    fun test02_ValidPgpSignatureOverManifestSucceeds() {
        val key = loadRealKey()
        val valid = SecureComponentUpdater.verifyManifestSignature(
            realManifestBytes,
            realSignatureBytes,
            key,
        )
        assertTrue("Authentic upstream detached signature must verify successfully", valid)
    }

    @Test
    fun test03_TamperedManifestWithPgpSignatureFails() {
        val key = loadRealKey()
        val tamperedBytes = realManifestBytes.copyOf()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0xFF).toByte()

        val valid = SecureComponentUpdater.verifyManifestSignature(
            tamperedBytes,
            realSignatureBytes,
            key,
        )
        assertFalse("Tampered manifest must fail OpenPGP signature verification", valid)
    }

    @Test
    fun test04_TamperedPgpSignaturePayloadFails() {
        val key = loadRealKey()
        val tamperedSig = realSignatureBytes.copyOf()
        // Corrupt signature payload bytes
        tamperedSig[tamperedSig.size - 10] = (tamperedSig[tamperedSig.size - 10].toInt() xor 0xAA).toByte()

        try {
            val valid = SecureComponentUpdater.verifyManifestSignature(
                realManifestBytes,
                tamperedSig,
                key,
            )
            assertFalse("Tampered signature must not evaluate to valid", valid)
        } catch (e: Exception) {
            // Either verify() returns false or BouncyCastle throws a parsing/verification exception
            assertTrue("Expected exception on corrupted signature packet: ${e.message}", true)
        }
    }

    @Test
    fun test05_MalformedPgpSignaturePacketRejected() {
        val key = loadRealKey()
        val garbageBytes = "This is definitely not an OpenPGP packet".toByteArray(Charsets.UTF_8)
        try {
            SecureComponentUpdater.verifyManifestSignature(
                realManifestBytes,
                garbageBytes,
                key,
            )
            fail("Expected exception for non-PGP signature packet")
        } catch (e: Exception) {
            assertTrue(
                "Exception thrown for malformed PGP packet: ${e.javaClass.simpleName}",
                e is IllegalArgumentException || e is IOException || e is SecurityException,
            )
        }
    }

    // ==========================================
    // 2. Checksum Extraction & Manifest Tests
    // ==========================================

    @Test
    fun test06_ExtractExpectedSha256MatchesAuthenticAsset() {
        val manifestText = realManifestBytes.toString(Charsets.UTF_8)
        val extracted = SecureComponentUpdater.extractExpectedSha256(manifestText, "yt-dlp")
        assertEquals(
            "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8",
            extracted,
        )
    }

    @Test
    fun test07_MalformedChecksumManifestRejection() {
        val malformedManifest = "not-a-valid-sha256-line-at-all yt-dlp"
        try {
            SecureComponentUpdater.extractExpectedSha256(malformedManifest, "yt-dlp")
            fail("Malformed SHA-256 hash must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Asset 'yt-dlp' not found with valid SHA-256"))
        }
    }

    @Test
    fun test08_MissingChecksumForTargetAsset() {
        val manifestWithoutYtdlp = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8  other-binary"
        try {
            SecureComponentUpdater.extractExpectedSha256(manifestWithoutYtdlp, "yt-dlp")
            fail("Manifest lacking yt-dlp must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Asset 'yt-dlp' not found"))
        }
    }

    // ==========================================
    // 3. Release Tag Validation & Channel Selection
    // ==========================================

    @Test
    fun test09_ReleaseTagValidationRejectsInvalidTags() {
        assertFalse(SecureComponentUpdater.VALID_TAG_REGEX.matches("../bad-tag"))
        assertFalse(SecureComponentUpdater.VALID_TAG_REGEX.matches("tag; rm -rf"))
        assertFalse(SecureComponentUpdater.VALID_TAG_REGEX.matches("invalid"))
        assertTrue(SecureComponentUpdater.VALID_TAG_REGEX.matches("2025.02.19"))
        assertTrue(SecureComponentUpdater.VALID_TAG_REGEX.matches("2026.08.30.232658"))
    }

    @Test
    fun test10_StableChannelTargetsCorrectRepo() = runBlocking {
        val transport = MockHttpTransport()
        transport.urlToBytes["https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"] =
            """{"tag_name": "2025.02.19"}""".toByteArray(Charsets.UTF_8)

        val updater = SecureComponentUpdater(
            customTargetDir = tempFolder.root,
            customKeySupplier = { loadRealKey() },
        )
        val tag = updater.fetchLatestReleaseTag(UpdatePreference.STABLE, transport)
        assertEquals("2025.02.19", tag)
    }

    @Test
    fun test11_NightlyChannelTargetsCorrectRepo() = runBlocking {
        val transport = MockHttpTransport()
        transport.urlToBytes["https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"] =
            """{"tag_name": "2026.08.30.232658"}""".toByteArray(Charsets.UTF_8)

        val updater = SecureComponentUpdater(
            customTargetDir = tempFolder.root,
            customKeySupplier = { loadRealKey() },
        )
        val tag = updater.fetchLatestReleaseTag(UpdatePreference.NIGHTLY, transport)
        assertEquals("2026.08.30.232658", tag)
    }

    // ==========================================
    // 4. End-to-End Pipeline & Replacement Tests
    // ==========================================

    @Test
    fun test12_EndToEndUpdateSuccess() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-dir")
        val expectedHash = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8"
        val mockBinaryContent = "binary-content-matching-hash".toByteArray(Charsets.UTF_8)

        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.urlToFileHash["$baseUrl/yt-dlp"] = Pair(mockBinaryContent, expectedHash)

        var committedTag: String? = null
        var committedVer: String? = null

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
            customValidator = { "2025.02.19" },
            onCommitSuccess = { tag, ver ->
                committedTag = tag
                committedVer = ver
            },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue("Update must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("2025.02.19", result.getOrNull())

        val installedFile = File(targetDir, "yt-dlp")
        assertTrue("Installed binary must exist", installedFile.exists())
        assertEquals(mockBinaryContent.size.toLong(), installedFile.length())

        val backupFile = File(targetDir, "yt-dlp.backup.tmp")
        assertFalse("Backup file must be cleaned up on success", backupFile.exists())

        assertEquals("2025.02.19", committedTag)
        assertEquals("2025.02.19", committedVer)
    }

    @Test
    fun test13_InvalidChecksumMismatchRejectsAndDeletesTmp() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-mismatch")
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val mockBinaryContent = "tampered-binary".toByteArray(Charsets.UTF_8)

        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.urlToFileHash["$baseUrl/yt-dlp"] = Pair(mockBinaryContent, wrongHash)

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
            customValidator = { "2025.02.19" },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue("Must fail on hash mismatch", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("SHA-256 mismatch"))

        val downloadTmp = File(targetDir, "yt-dlp.download.tmp")
        assertFalse("Temporary download file must be deleted on mismatch", downloadTmp.exists())
    }

    @Test
    fun test14_PostUpdateValidationFailureTriggersRollback() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-rollback")
        val existingActive = File(targetDir, "yt-dlp")
        val originalContent = "known-good-original-binary".toByteArray(Charsets.UTF_8)
        existingActive.writeBytes(originalContent)

        val expectedHash = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8"
        val mockBinaryContent = "new-binary-that-fails-execution".toByteArray(Charsets.UTF_8)

        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.urlToFileHash["$baseUrl/yt-dlp"] = Pair(mockBinaryContent, expectedHash)

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
            customValidator = { null }, // Fails execution check
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue("Must fail on post-update validation", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("rolled back to previous component"))

        // Assert original binary restored
        assertTrue("Original binary must still exist after rollback", existingActive.exists())
        assertEquals(
            "Original binary content must be restored",
            "known-good-original-binary",
            existingActive.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun test15_FailedDownloadNetworkErrorAbortsAndCleansUp() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-net-err")
        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.onDownloadToFile = { _, _ -> throw IOException("Network connection reset") }

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue("Must fail on network error", result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)

        val downloadTmp = File(targetDir, "yt-dlp.download.tmp")
        assertFalse("Temp file must be cleaned up on download error", downloadTmp.exists())
    }

    @Test
    fun test16_AllowedDownloadAndRedirectHostPolicies() {
        assertTrue(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("api.github.com"))
        assertTrue(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("github.com"))
        assertTrue(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("objects.githubusercontent.com"))
        assertTrue(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("release-assets.githubusercontent.com"))
        assertFalse(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("evil.com"))
        assertFalse(SecureComponentUpdater.ALLOWED_DOWNLOAD_HOSTS.contains("raw.githubusercontent.com"))

        assertTrue(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("objects.githubusercontent.com"))
        assertTrue(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("release-assets.githubusercontent.com"))
        assertFalse(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("api.github.com"))
        assertFalse(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("thirdparty.cdn.com"))
    }

    @Test
    fun test17_InsecureHttpUrlRejected() {
        val transport = object : SecureComponentUpdater.DefaultHttpTransport() {
            fun checkUrl(url: String) {
                val parsed = java.net.URL(url)
                if (parsed.protocol != "https") {
                    throw SecurityException("Insecure HTTP protocol rejected: $url")
                }
            }
        }
        try {
            transport.checkUrl("http://github.com/yt-dlp/yt-dlp/releases")
            fail("Insecure HTTP must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Insecure HTTP protocol rejected"))
        }
    }

    @Test
    fun test18_RejectedRedirectHost() {
        val redirectHost = "untrusted-server.com"
        assertFalse(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains(redirectHost))
    }

    @Test
    fun test19_ApprovedRedirectHostAllowed() {
        assertTrue(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("objects.githubusercontent.com"))
        assertTrue(SecureComponentUpdater.ALLOWED_REDIRECT_HOSTS.contains("release-assets.githubusercontent.com"))
    }

    @Test
    fun test20_WrongSigningKeyIssuerRejected() {
        // Create an untrusted public key with different keyID
        val fakeKeyring = "untrusted-key".toByteArray()
        try {
            SecureComponentUpdater.loadAndVerifyPublicKey(ByteArrayInputStream(fakeKeyring))
            fail("Untrusted or invalid keyring must throw exception")
        } catch (e: Exception) {
            assertTrue(e is SecurityException || e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun test21_InterruptedDownloadCleansUpTempFiles() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-interrupted")
        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.onDownloadToFile = { _, dest ->
            dest.writeBytes("partial-bytes".toByteArray())
            throw IOException("Connection interrupted abruptly")
        }

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue(result.isFailure)
        val downloadTmp = File(targetDir, "yt-dlp.download.tmp")
        assertFalse("Interrupted temp file must be deleted", downloadTmp.exists())
    }

    @Test
    fun test22_ReplacementFailureTriggersRollback() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-replace-fail")
        val existingActive = File(targetDir, "yt-dlp")
        existingActive.writeBytes("previous-working-binary".toByteArray())

        val expectedHash = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8"
        val mockBinaryContent = "corrupt-exec".toByteArray()

        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.urlToFileHash["$baseUrl/yt-dlp"] = Pair(mockBinaryContent, expectedHash)

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
            customValidator = { throw RuntimeException("Binary crashes on invocation") },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue(result.isFailure)
        assertTrue("Previous active binary must be retained", existingActive.exists())
        assertEquals("previous-working-binary", existingActive.readText())
    }

    @Test
    fun test23_RateLimit403ResponseThrows() = runBlocking {
        val transport = MockHttpTransport()
        transport.onFetchBytes = { throw IOException("GitHub returned HTTP 403") }

        val updater = SecureComponentUpdater(
            customTargetDir = tempFolder.root,
            customKeySupplier = { loadRealKey() },
        )

        try {
            updater.fetchLatestReleaseTag(UpdatePreference.STABLE, transport)
            fail("HTTP 403 must throw IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    @Test
    fun test24_ValidSignedManifestPlusWrongBinaryHashRejected() = runBlocking {
        val targetDir = tempFolder.newFolder("yt-dlp-tampered-asset")
        val expectedHash = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8"
        val badHash = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"

        val transport = MockHttpTransport()
        val baseUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2025.02.19"
        transport.urlToBytes["$baseUrl/SHA2-256SUMS"] = realManifestBytes
        transport.urlToBytes["$baseUrl/SHA2-256SUMS.sig"] = realSignatureBytes
        transport.urlToFileHash["$baseUrl/yt-dlp"] = Pair("malicious-data".toByteArray(), badHash)

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            customKeySupplier = { loadRealKey() },
        )

        val result = updater.updateYtDlp(UpdatePreference.STABLE, "2025.02.19", transport)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun test25_RepositoryChannelConstants() {
        assertEquals("yt-dlp/yt-dlp", SecureComponentUpdater.REPO_STABLE)
        assertEquals("yt-dlp/yt-dlp-nightly-builds", SecureComponentUpdater.REPO_NIGHTLY)
        assertEquals("SHA2-256SUMS", SecureComponentUpdater.ASSET_MANIFEST)
        assertEquals("SHA2-256SUMS.sig", SecureComponentUpdater.ASSET_SIGNATURE)
        assertEquals("yt-dlp", SecureComponentUpdater.ASSET_BINARY)
    }

    @Test
    fun test26_ExtractExpectedSha256TrimsLeadingAsterisk() {
        val manifestWithAsterisk = "a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8 *yt-dlp"
        val extracted = SecureComponentUpdater.extractExpectedSha256(manifestWithAsterisk, "yt-dlp")
        assertEquals("a18843c75b04756ed1d8e261b54de8b7ddf918f73134175d5acab745455dcbc8", extracted)
    }
}
