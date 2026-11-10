package com.mvrk.vrka.update.security

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.util.Date

class ReleaseManifestVerifierTest {

    private lateinit var verifier: ReleaseManifestVerifier
    private lateinit var pinnedVerifier: ReleaseManifestVerifier
    private lateinit var testKey: TestPgpKeys.KeyMaterial
    private lateinit var pinnedPublicKey: PGPPublicKey

    private val testManifestJson = """
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
        pinnedVerifier = ReleaseManifestVerifier(customPublicKeySupplier = { pinnedPublicKey })
        testKey = TestPgpKeys.generate()
        verifier = TestPgpKeys.verifierFor(testKey)
    }

    @Test
    fun testPinnedPublicKeyLoadedSuccessfully() {
        assertNotNull(pinnedPublicKey)
        assertEquals(ReleaseManifestVerifier.PINNED_RELEASE_KEY_ID, pinnedPublicKey.keyID)
        val fp = org.bouncycastle.util.encoders.Hex.toHexString(pinnedPublicKey.fingerprint).uppercase()
        assertEquals(ReleaseManifestVerifier.PINNED_RELEASE_KEY_FINGERPRINT, fp)
        assertTrue(
            "Hermetic tests must not reuse the production key",
            testKey.publicKey.keyID != ReleaseManifestVerifier.PINNED_RELEASE_KEY_ID,
        )
    }

    @Test
    fun testEmptyPayloadsRejected() {
        assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = ByteArray(0),
                signatureBytes = "some sig".toByteArray(),
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }

        assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = testManifestJson.toByteArray(),
                signatureBytes = ByteArray(0),
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
    }

    @Test
    fun testManifestExceedingMaximumSizeRejected() {
        val oversized = ByteArray(ReleaseManifest.MAX_MANIFEST_SIZE_BYTES + 1)
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = oversized,
                signatureBytes = "dummy".toByteArray(),
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
            )
        }
        assertTrue(err.message!!.contains("exceeds maximum size limit"))
    }

    @Test
    fun testMalformedSignatureBytesRejected() {
        val garbageSig = "This is not a PGP signature".toByteArray()
        assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = garbageSig,
                publicKey = testKey.publicKey,
            )
        }
    }

    @Test
    fun testTruncatedSignatureBytesRejected() {
        val validSig = TestPgpKeys.sign(testManifestJson.toByteArray(), testKey)
        val truncated = validSig.copyOf(validSig.size / 2)
        assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = truncated,
                publicKey = testKey.publicKey,
            )
        }
    }

    @Test
    fun testTamperedManifestDetectedAndRejected() {
        val originalBytes = testManifestJson.toByteArray()
        val sig = TestPgpKeys.sign(originalBytes, testKey)

        // Verify original passes
        verifier.verifyDetachedSignature(originalBytes, sig, testKey.publicKey)

        // Tamper 1 byte
        val tamperedBytes = testManifestJson.replace("50100", "50101").toByteArray()
        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(tamperedBytes, sig, testKey.publicKey)
        }
        assertTrue(err.message!!.contains("invalid signature"))
    }

    @Test
    fun testWrongKeySignatureRejected() {
        // Generate an ephemeral RSA key pair and sign with it
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pgpKp = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, Date())

        val signerBuilder = JcaPGPContentSignerBuilder(pgpKp.publicKey.algorithm, HashAlgorithmTags.SHA256)
            .setProvider("BC")
        val sigGen = PGPSignatureGenerator(signerBuilder)
        sigGen.init(PGPSignature.BINARY_DOCUMENT, pgpKp.privateKey)
        sigGen.update(testManifestJson.toByteArray())
        val sig = sigGen.generate()

        val baos = ByteArrayOutputStream()
        ArmoredOutputStream(baos).use { aos ->
            sig.encode(aos)
        }
        val wrongKeySigBytes = baos.toByteArray()

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = wrongKeySigBytes,
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("does not match trusted key ID"))
    }

    @Test
    fun testInsecureHashAlgorithmRejected() {
        // Deliberately sign with SHA-1 using the temporary test key.
        val weakSigBytes = TestPgpKeys.sign(
            data = testManifestJson.toByteArray(),
            key = testKey,
            hashAlgorithm = HashAlgorithmTags.SHA1,
        )

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = weakSigBytes,
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("Insecure hash algorithm rejected"))
    }

    @Test
    fun testSha224IsRejectedByTheStrictAllowlist() {
        val sha224Signature = TestPgpKeys.sign(
            data = testManifestJson.toByteArray(),
            key = testKey,
            hashAlgorithm = HashAlgorithmTags.SHA224,
        )

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = sha224Signature,
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("Insecure hash algorithm rejected"))
    }

    @Test
    fun testSha384AndSha512AreAccepted() {
        for (algorithm in listOf(HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512)) {
            val signature = TestPgpKeys.sign(
                data = testManifestJson.toByteArray(),
                key = testKey,
                hashAlgorithm = algorithm,
            )
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = signature,
                publicKey = testKey.publicKey,
            )
        }
    }

    @Test
    fun testUnhashedOnlyIssuerFingerprintIsRejected() {
        val signature = TestPgpKeys.signWithIssuerFingerprint(
            data = testManifestJson.toByteArray(),
            key = testKey,
            hashedFingerprintKey = null,
            unhashedFingerprintKey = testKey.publicKey,
        )

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = signature,
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("hashed signature subpackets"))
    }

    @Test
    fun testMismatchedHashedIssuerFingerprintIsRejected() {
        val otherKey = TestPgpKeys.generate()
        val signature = TestPgpKeys.signWithIssuerFingerprint(
            data = testManifestJson.toByteArray(),
            key = testKey,
            hashedFingerprintKey = otherKey.publicKey,
            issuerKeyId = testKey.publicKey.keyID,
        )

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = signature,
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("does not match trusted key fingerprint"))
    }

    @Test
    fun testEndToEndVerifyAndParseManifestWithRealSignature() {
        val manifestBytes = testManifestJson.toByteArray()
        val sigBytes = TestPgpKeys.sign(manifestBytes, testKey)

        val parsed = verifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = sigBytes,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L,
            currentStoredRevision = 1L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = 1793494800000L, // 2026-11-01T01:00:00Z
        )

        assertNotNull(parsed)
        assertEquals("5.1.0", parsed.versionName)
        assertEquals(50100L, parsed.versionCode)
        assertEquals("v5.1.0", parsed.releaseTag)
        assertEquals("VRKA-Android-v5.1.0.apk", parsed.apk.filename)
    }

    @Test
    fun testFutureIssuedTimestampBeyondToleranceRejected() {
        val futureManifest = testManifestJson.replace("2026-11-01T00:00:00Z", "2026-11-01T05:00:00Z")
        val manifestBytes = futureManifest.toByteArray()
        val sigBytes = TestPgpKeys.sign(manifestBytes, testKey)

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = sigBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
                currentTimeMillis = 1793494800000L, // 2026-11-01T01:00:00Z (issued is 4h in future)
            )
        }
        assertTrue(err.message!!.contains("in the future"))
    }

    @Test
    fun testClockSkewWithinToleranceAccepted() {
        // issued_at is 5 minutes (300 seconds) in the future relative to client currentTimeMillis
        val clientTime = 1793494800000L // 2026-11-01T01:00:00Z
        val futureFiveMinutes = "2026-11-01T01:05:00Z"
        val skewManifest = testManifestJson.replace("2026-11-01T00:00:00Z", futureFiveMinutes)
        val manifestBytes = skewManifest.toByteArray()
        val sigBytes = TestPgpKeys.sign(manifestBytes, testKey)

        val parsed = verifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = sigBytes,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L,
            currentStoredRevision = 1L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = clientTime,
        )
        assertNotNull(parsed)
        assertEquals("v5.1.0", parsed.releaseTag)
    }

    @Test
    fun impossibleIssuedDateIsRejectedAfterSignatureVerification() {
        val invalidManifest = testManifestJson.replace(
            "2026-11-01T00:00:00Z",
            "2026-02-30T00:00:00Z",
        )
        val manifestBytes = invalidManifest.toByteArray()
        val signature = TestPgpKeys.sign(manifestBytes, testKey)

        val error = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = signature,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 1L,
                expectedReleaseTag = "v5.1.0",
                currentTimeMillis = 1793494800000L,
            )
        }
        assertTrue(error.message!!.contains("Invalid 'issued_at'"))
    }

    @Test
    fun testIso8601UtcParsingAndConversion() {
        // Valid UTC timestamp with 'Z'
        val parsedUtc = ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14Z")
        assertNotNull(parsedUtc)

        // Non-UTC or malformed formats without 'Z' must return null
        val parsedMalformed = ReleaseManifestVerifier.parseIso8601("2026-11-01 06:40:14")
        assertEquals(null, parsedMalformed)

        val parsedInvalid = ReleaseManifestVerifier.parseIso8601("invalid-timestamp")
        assertEquals(null, parsedInvalid)
    }

    @Test
    fun testMetadataRevision3MonotonicUpgradeAccepted() {
        val rev3Manifest = testManifestJson.replace("\"metadata_revision\": 1", "\"metadata_revision\": 3")
        val manifestBytes = rev3Manifest.toByteArray()
        val sigBytes = TestPgpKeys.sign(manifestBytes, testKey)

        // Revision 3 accepted when stored is 2
        val parsed = verifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = sigBytes,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L,
            currentStoredRevision = 2L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = 1793494800000L,
        )
        assertEquals(3L, parsed.metadataRevision)

        // Revision 3 rejected when stored is 4 (anti-rollback)
        assertThrows(ManifestValidationException::class.java) {
            verifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = sigBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50002L,
                currentStoredRevision = 4L,
                expectedReleaseTag = "v5.1.0",
                currentTimeMillis = 1793494800000L,
            )
        }
    }

    @Test
    fun publishedManifestDetachedSignatureVerifiesWithPinnedPublicKey() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.0.manifest.json"),
            File("../VRKA-Android-v5.1.0.manifest.json"),
            File("../../VRKA-Android-v5.1.0.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("The published v5.1.0 manifest must be present in the repository", manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        assertTrue("The published detached signature must be present", signatureFile.exists())

        val manifestBytes = manifestFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)
            ?: error("Published manifest has an invalid issued_at")

        val parsed = pinnedVerifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = signatureFile.readBytes(),
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50002L,
            currentStoredRevision = 1L,
            expectedReleaseTag = "v5.1.0",
            currentTimeMillis = issuedEpoch + 1_000L,
            publicKey = pinnedPublicKey,
        )

        assertEquals("5.1.0", parsed.versionName)
        assertEquals(157_202_797L, parsed.apk.sizeBytes)
        assertEquals("5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa", parsed.apk.sha256)
    }

    @Test
    fun publishedV511ManifestDetachedSignatureVerifiesWithPinnedPublicKey() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.1.manifest.json"),
            File("../VRKA-Android-v5.1.1.manifest.json"),
            File("../../VRKA-Android-v5.1.1.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("The published v5.1.1 manifest must be present in the repository", manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        assertTrue("The published detached signature must be present", signatureFile.exists())

        val manifestBytes = manifestFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)
            ?: error("Published manifest has an invalid issued_at")

        val parsed = pinnedVerifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = signatureFile.readBytes(),
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50100L,
            currentStoredRevision = 3L,
            expectedReleaseTag = "v5.1.1",
            currentTimeMillis = issuedEpoch + 1_000L,
            publicKey = pinnedPublicKey,
        )

        assertEquals("5.1.1", parsed.versionName)
        assertEquals(50101L, parsed.versionCode)
        assertEquals(4L, parsed.metadataRevision)
        assertEquals(157_231_575L, parsed.apk.sizeBytes)
        assertEquals("781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8", parsed.apk.sha256)
    }

    @Test
    fun publishedV512ManifestDetachedSignatureVerifiesWithPinnedPublicKey() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.2.manifest.json"),
            File("../VRKA-Android-v5.1.2.manifest.json"),
            File("../../VRKA-Android-v5.1.2.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("The published v5.1.2 manifest must be present in the repository", manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        assertTrue("The published detached signature must be present", signatureFile.exists())

        val manifestBytes = manifestFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)
            ?: error("Published manifest has an invalid issued_at")

        val parsed = pinnedVerifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = signatureFile.readBytes(),
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50101L,
            currentStoredRevision = 4L,
            expectedReleaseTag = "v5.1.2",
            currentTimeMillis = issuedEpoch + 1_000L,
            publicKey = pinnedPublicKey,
        )

        assertEquals("5.1.2", parsed.versionName)
        assertEquals(50102L, parsed.versionCode)
        assertEquals(5L, parsed.metadataRevision)
        assertEquals(157_243_017L, parsed.apk.sizeBytes)
        assertEquals("3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a", parsed.apk.sha256)
    }

    @Test
    fun publishedV513ManifestDetachedSignatureVerifiesWithPinnedPublicKey() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.3.manifest.json"),
            File("../VRKA-Android-v5.1.3.manifest.json"),
            File("../../VRKA-Android-v5.1.3.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("The v5.1.3 manifest must be present in the repository", manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        assertTrue("The detached signature must be present", signatureFile.exists())

        val manifestBytes = manifestFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)
            ?: error("The v5.1.3 manifest has an invalid issued_at")

        val parsed = pinnedVerifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = signatureFile.readBytes(),
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50102L,
            currentStoredRevision = 5L,
            expectedReleaseTag = "v5.1.3",
            currentTimeMillis = issuedEpoch + 1_000L,
            publicKey = pinnedPublicKey,
        )

        assertEquals("5.1.3", parsed.versionName)
        assertEquals(50103L, parsed.versionCode)
        assertEquals(6L, parsed.metadataRevision)
        assertEquals("90de6b8e69b2b801eed4c838fe510fe91a29a80e", parsed.sourceCommit)
        assertEquals(157_428_789L, parsed.apk.sizeBytes)
        assertEquals("c9f58d3a97d728315b51fc92f43b5f54b88ae4ad9c25b3b22ea0cd8338549ddf", parsed.apk.sha256)
    }

    @Test
    fun testValidV511ToV512UpgradeAcceptedByManifestVerifier() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.2.manifest.json"),
            File("../VRKA-Android-v5.1.2.manifest.json"),
            File("../../VRKA-Android-v5.1.2.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("The published v5.1.2 manifest must be present in the repository", manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        val manifestBytes = manifestFile.readBytes()
        val signatureBytes = signatureFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)!!

        // Upgrading an installed v5.1.1 (versionCode 50101, storedRevision 4) to v5.1.2 (versionCode 50102, revision 5) succeeds
        val accepted = pinnedVerifier.verifyAndParseManifest(
            manifestBytes = manifestBytes,
            signatureBytes = signatureBytes,
            currentInstalledPackage = "com.mvrk.vrka",
            currentInstalledVersionCode = 50101L,
            currentStoredRevision = 4L,
            expectedReleaseTag = "v5.1.2",
            currentTimeMillis = issuedEpoch + 1_000L,
            publicKey = pinnedPublicKey,
        )
        assertEquals(50102L, accepted.versionCode)
        assertEquals(5L, accepted.metadataRevision)
    }

    @Test
    fun testManifestVerifierRejectsLowerVersionDowngrade() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.2.manifest.json"),
            File("../VRKA-Android-v5.1.2.manifest.json"),
            File("../../VRKA-Android-v5.1.2.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        val manifestBytes = manifestFile.readBytes()
        val signatureBytes = signatureFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)!!

        // Downgrade protection: rejected if installed versionCode > manifest versionCode
        val downgradeErr = assertThrows(ManifestValidationException::class.java) {
            pinnedVerifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = signatureBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50103L,
                currentStoredRevision = 4L,
                expectedReleaseTag = "v5.1.2",
                currentTimeMillis = issuedEpoch + 1_000L,
                publicKey = pinnedPublicKey,
            )
        }
        assertTrue(downgradeErr.message!!.contains("downgrade rejected"))
    }

    @Test
    fun testManifestVerifierRejectsRevisionRollback() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.2.manifest.json"),
            File("../VRKA-Android-v5.1.2.manifest.json"),
            File("../../VRKA-Android-v5.1.2.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        val manifestBytes = manifestFile.readBytes()
        val signatureBytes = signatureFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)!!

        // Rollback protection: rejected if stored revision is higher than manifest revision (stored 6 > manifest 5)
        val rollbackErr = assertThrows(ManifestValidationException::class.java) {
            pinnedVerifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = signatureBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50101L,
                currentStoredRevision = 6L,
                expectedReleaseTag = "v5.1.2",
                currentTimeMillis = issuedEpoch + 1_000L,
                publicKey = pinnedPublicKey,
            )
        }
        assertTrue(rollbackErr.message!!.contains("rollback detected"))
    }

    @Test
    fun testManifestVerifierRejectsInvalidPackageOrTagMetadata() {
        val manifestFile = listOf(
            File("VRKA-Android-v5.1.2.manifest.json"),
            File("../VRKA-Android-v5.1.2.manifest.json"),
            File("../../VRKA-Android-v5.1.2.manifest.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(manifestFile)
        val signatureFile = File(manifestFile!!.path + ".asc")
        val manifestBytes = manifestFile.readBytes()
        val signatureBytes = signatureFile.readBytes()
        val manifestObj = ReleaseManifest.fromJson(String(manifestBytes))
        val issuedEpoch = ReleaseManifestVerifier.parseIso8601(manifestObj.issuedAt)!!

        // Application package mismatch
        val packageErr = assertThrows(ManifestValidationException::class.java) {
            pinnedVerifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = signatureBytes,
                currentInstalledPackage = "com.untrusted.spoofed",
                currentInstalledVersionCode = 50101L,
                currentStoredRevision = 4L,
                expectedReleaseTag = "v5.1.2",
                currentTimeMillis = issuedEpoch + 1_000L,
                publicKey = pinnedPublicKey,
            )
        }
        assertTrue(packageErr.message!!.contains("Application ID mismatch"))

        // Release tag mismatch
        val tagErr = assertThrows(ManifestValidationException::class.java) {
            pinnedVerifier.verifyAndParseManifest(
                manifestBytes = manifestBytes,
                signatureBytes = signatureBytes,
                currentInstalledPackage = "com.mvrk.vrka",
                currentInstalledVersionCode = 50101L,
                currentStoredRevision = 4L,
                expectedReleaseTag = "v5.1.3",
                currentTimeMillis = issuedEpoch + 1_000L,
                publicKey = pinnedPublicKey,
            )
        }
        assertTrue(tagErr.message!!.contains("Release tag mismatch"))
    }

    @Test
    fun testMissingIssuerFingerprintSubpacketRejected() {
        val decoder = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(testKey.secretKeyRing))
        val collection = org.bouncycastle.openpgp.PGPSecretKeyRingCollection(
            decoder,
            org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator().setProvider("BC"),
        )
        val secretKey = collection.asSequence()
            .flatMap { it.secretKeys.asSequence() }
            .first { it.isSigningKey }
        val privateKey = secretKey.extractPrivateKey(
            org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder().setProvider("BC").build("".toCharArray()),
        )
        val generator = org.bouncycastle.openpgp.PGPSignatureGenerator(
            org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder(
                secretKey.publicKey.algorithm,
                org.bouncycastle.bcpg.HashAlgorithmTags.SHA256,
            ).setProvider("BC"),
        )
        generator.init(org.bouncycastle.openpgp.PGPSignature.BINARY_DOCUMENT, privateKey)
        generator.update(testManifestJson.toByteArray())
        val sig = generator.generate()
        val baos = java.io.ByteArrayOutputStream()
        org.bouncycastle.bcpg.ArmoredOutputStream(baos).use { aos -> sig.encode(aos) }

        val err = assertThrows(ManifestValidationException::class.java) {
            verifier.verifyDetachedSignature(
                dataBytes = testManifestJson.toByteArray(),
                signatureBytes = baos.toByteArray(),
                publicKey = testKey.publicKey,
            )
        }
        assertTrue(err.message!!.contains("Mandatory issuer fingerprint subpacket missing"))
    }
}
