package com.mvrk.vrka.update.security

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.IssuerFingerprint
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date

class ReleaseManifestVerifierTest {

    private lateinit var verifier: ReleaseManifestVerifier
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
        verifier = ReleaseManifestVerifier(customPublicKeySupplier = { pinnedPublicKey })
    }

    @Test
    fun testPinnedPublicKeyLoadedSuccessfully() {
        assertNotNull(pinnedPublicKey)
        assertEquals(ReleaseManifestVerifier.PINNED_RELEASE_KEY_ID, pinnedPublicKey.keyID)
        val fp = org.bouncycastle.util.encoders.Hex.toHexString(pinnedPublicKey.fingerprint).uppercase()
        assertEquals(ReleaseManifestVerifier.PINNED_RELEASE_KEY_FINGERPRINT, fp)
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
                publicKey = pinnedPublicKey,
            )
        }
    }

    @Test
    fun testTruncatedSignatureBytesRejected() {
        // Sign validly first (if secret key available) or test with corrupted header
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val validSig = ReleaseManifestSigner.signDetached(
                dataBytes = testManifestJson.toByteArray(),
                secretKeyStream = FileInputStream(secretKeyFile),
            )
            val truncated = validSig.copyOf(validSig.size / 2)
            assertThrows(ManifestValidationException::class.java) {
                verifier.verifyDetachedSignature(
                    dataBytes = testManifestJson.toByteArray(),
                    signatureBytes = truncated,
                    publicKey = pinnedPublicKey,
                )
            }
        }
    }

    @Test
    fun testTamperedManifestDetectedAndRejected() {
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val originalBytes = testManifestJson.toByteArray()
            val sig = ReleaseManifestSigner.signDetached(
                dataBytes = originalBytes,
                secretKeyStream = FileInputStream(secretKeyFile),
            )

            // Verify original passes
            verifier.verifyDetachedSignature(originalBytes, sig, pinnedPublicKey)

            // Tamper 1 byte
            val tamperedBytes = testManifestJson.replace("50100", "50101").toByteArray()
            val err = assertThrows(ManifestValidationException::class.java) {
                verifier.verifyDetachedSignature(tamperedBytes, sig, pinnedPublicKey)
            }
            assertTrue(err.message!!.contains("invalid signature"))
        }
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
                publicKey = pinnedPublicKey,
            )
        }
        assertTrue(err.message!!.contains("does not match trusted key ID"))
    }

    @Test
    fun testInsecureHashAlgorithmRejected() {
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val decoder = PGPUtil.getDecoderStream(FileInputStream(secretKeyFile))
            val collection = org.bouncycastle.openpgp.PGPSecretKeyRingCollection(
                decoder,
                JcaKeyFingerprintCalculator().setProvider("BC"),
            )
            val secKey = collection.asSequence().flatMap { it.secretKeys.asSequence() }.first { it.isSigningKey }
            val privKey = secKey.extractPrivateKey(
                JcePBESecretKeyDecryptorBuilder().setProvider("BC").build("".toCharArray())
            )

            // Deliberately sign with SHA-1 (HashAlgorithmTags.SHA1 = 2)
            val weakSignerBuilder = JcaPGPContentSignerBuilder(secKey.publicKey.algorithm, HashAlgorithmTags.SHA1)
                .setProvider("BC")
            val sigGen = PGPSignatureGenerator(weakSignerBuilder)
            sigGen.init(PGPSignature.BINARY_DOCUMENT, privKey)
            sigGen.update(testManifestJson.toByteArray())
            val sig = sigGen.generate()

            val baos = ByteArrayOutputStream()
            ArmoredOutputStream(baos).use { it -> sig.encode(it) }
            val weakSigBytes = baos.toByteArray()

            val err = assertThrows(ManifestValidationException::class.java) {
                verifier.verifyDetachedSignature(
                    dataBytes = testManifestJson.toByteArray(),
                    signatureBytes = weakSigBytes,
                    publicKey = pinnedPublicKey,
                )
            }
            assertTrue(err.message!!.contains("Insecure hash algorithm rejected"))
        }
    }

    @Test
    fun testEndToEndVerifyAndParseManifestWithRealSignature() {
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val manifestBytes = testManifestJson.toByteArray()
            val sigBytes = ReleaseManifestSigner.signDetached(
                dataBytes = manifestBytes,
                secretKeyStream = FileInputStream(secretKeyFile),
            )

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
    }

    @Test
    fun generateAndVerifyReleaseManifestAsc() {
        val secretKeyFile = File(System.getProperty("user.home"), ".vrka/vrka_release_signing_key.sec")
        if (secretKeyFile.exists()) {
            val manifestFile = listOf(
                File("VRKA-Android-v5.1.0.manifest.json"),
                File("../VRKA-Android-v5.1.0.manifest.json"),
                File("../../VRKA-Android-v5.1.0.manifest.json"),
            ).firstOrNull { it.exists() }
            if (manifestFile != null) {
                val manifestBytes = manifestFile.readBytes()
                val sigBytes = ReleaseManifestSigner.signDetached(
                    dataBytes = manifestBytes,
                    secretKeyStream = FileInputStream(secretKeyFile),
                )
                val sigFile = File(manifestFile.parentFile, "VRKA-Android-v5.1.0.manifest.json.asc")
                sigFile.writeBytes(sigBytes)

                // Verify the generated signature passes full manifest verification
                val parsed = verifier.verifyAndParseManifest(
                    manifestBytes = manifestBytes,
                    signatureBytes = sigBytes,
                    currentInstalledPackage = "com.mvrk.vrka",
                    currentInstalledVersionCode = 50002L,
                    currentStoredRevision = 1L,
                    expectedReleaseTag = "v5.1.0",
                    currentTimeMillis = 1793494800000L,
                )
                assertEquals("5.1.0", parsed.versionName)
                assertEquals(157202797L, parsed.apk.sizeBytes)
                assertEquals("5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa", parsed.apk.sha256)
            }
        }
    }
}
