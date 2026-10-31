package com.mvrk.vrka.update.security

import android.content.Context
import com.mvrk.vrka.R
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.util.encoders.Hex
import java.io.InputStream
import java.security.Provider
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ReleaseManifestVerifier(
    private val context: Context? = null,
    private val customPublicKeySupplier: (() -> PGPPublicKey)? = null,
) {

    init {
        ensureBouncyCastleProvider()
    }

    fun loadPinnedPublicKey(): PGPPublicKey {
        if (customPublicKeySupplier != null) {
            return customPublicKeySupplier.invoke()
        }
        val ctx = context ?: throw IllegalStateException("Context required to load pinned public key from resources")
        val input = ctx.resources.openRawResource(R.raw.vrka_release_pubkey)
        return loadAndVerifyPublicKey(input)
    }

    fun verifyAndParseManifest(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        currentInstalledPackage: String,
        currentInstalledVersionCode: Long,
        currentStoredRevision: Long,
        expectedReleaseTag: String,
        currentTimeMillis: Long = System.currentTimeMillis(),
        publicKey: PGPPublicKey = loadPinnedPublicKey(),
    ): ReleaseManifest {
        if (manifestBytes.isEmpty()) {
            throw ManifestValidationException("Release manifest payload is empty")
        }
        if (manifestBytes.size > ReleaseManifest.MAX_MANIFEST_SIZE_BYTES) {
            throw ManifestValidationException("Release manifest exceeds maximum size limit of ${ReleaseManifest.MAX_MANIFEST_SIZE_BYTES} bytes")
        }
        if (signatureBytes.isEmpty()) {
            throw ManifestValidationException("Detached OpenPGP signature payload is empty")
        }

        // Cryptographic signature check over raw manifest bytes
        verifyDetachedSignature(manifestBytes, signatureBytes, publicKey)

        // Parse JSON manifest
        val jsonStr = try {
            manifestBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ManifestValidationException("Manifest is not valid UTF-8 text", e)
        }

        val manifest = try {
            ReleaseManifest.fromJson(jsonStr)
        } catch (e: Exception) {
            throw ManifestValidationException("Manifest schema validation failed: ${e.message}", e)
        }

        // Comprehensive domain validation
        validateManifestData(
            manifest = manifest,
            currentInstalledPackage = currentInstalledPackage,
            currentInstalledVersionCode = currentInstalledVersionCode,
            currentStoredRevision = currentStoredRevision,
            expectedReleaseTag = expectedReleaseTag,
            currentTimeMillis = currentTimeMillis,
        )

        return manifest
    }

    fun verifyDetachedSignature(
        dataBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKey: PGPPublicKey,
    ) {
        val provider = ensureBouncyCastleProvider()
        val pgpFact = try {
            val decoder = PGPUtil.getDecoderStream(signatureBytes.inputStream())
            PGPObjectFactory(decoder, JcaKeyFingerprintCalculator().setProvider(provider))
        } catch (e: Exception) {
            throw ManifestValidationException("Unable to decode OpenPGP signature stream", e)
        }

        val obj = try {
            pgpFact.nextObject() ?: throw ManifestValidationException("Empty OpenPGP signature packet")
        } catch (e: Exception) {
            if (e is ManifestValidationException) throw e
            throw ManifestValidationException("Failed to read OpenPGP signature packet: ${e.message}", e)
        }

        val sigList = when (obj) {
            is PGPSignatureList -> obj
            is PGPCompressedData -> {
                val compFact = try {
                    PGPObjectFactory(obj.dataStream, JcaKeyFingerprintCalculator().setProvider(provider))
                } catch (e: Exception) {
                    throw ManifestValidationException("Failed to decode compressed OpenPGP data", e)
                }
                val compObj = try {
                    compFact.nextObject()
                } catch (e: Exception) {
                    throw ManifestValidationException("Failed to read compressed OpenPGP signature", e)
                }
                compObj as? PGPSignatureList
                    ?: throw ManifestValidationException("Compressed packet does not contain signature list")
            }
            else -> throw ManifestValidationException("Unsupported OpenPGP object type: ${obj.javaClass.name}")
        }

        if (sigList.isEmpty) {
            throw ManifestValidationException("Signature list contains no signatures")
        }

        val signature = sigList.get(0)

        // Key ID verification
        if (signature.keyID != publicKey.keyID) {
            val sigKeyId = java.lang.Long.toHexString(signature.keyID).uppercase()
            val trustedKeyId = java.lang.Long.toHexString(publicKey.keyID).uppercase()
            throw ManifestValidationException("Signature key ID 0x$sigKeyId does not match trusted key ID 0x$trustedKeyId")
        }

        // Algorithm policy enforcement
        val hashAlgo = signature.hashAlgorithm
        if (hashAlgo == HashAlgorithmTags.MD5 || hashAlgo == HashAlgorithmTags.SHA1 || hashAlgo == HashAlgorithmTags.RIPEMD160) {
            throw ManifestValidationException("Insecure hash algorithm rejected for release signature (tag: $hashAlgo)")
        }

        val keyAlgo = signature.keyAlgorithm
        if (keyAlgo == PublicKeyAlgorithmTags.DSA) {
            throw ManifestValidationException("Insecure key algorithm rejected: DSA")
        }

        // Issuer fingerprint subpacket enforcement (RFC 4880bis / RFC 9580)
        val issuerFpSubpacket = signature.hashedSubPackets?.issuerFingerprint
            ?: signature.unhashedSubPackets?.issuerFingerprint
        if (issuerFpSubpacket != null) {
            val subpacketFp = Hex.toHexString(issuerFpSubpacket.fingerprint).uppercase()
            val trustedFp = Hex.toHexString(publicKey.fingerprint).uppercase()
            if (subpacketFp != trustedFp) {
                throw ManifestValidationException(
                    "Signature issuer fingerprint ($subpacketFp) does not match trusted key fingerprint ($trustedFp)"
                )
            }
        }

        // Signature expiration check
        val sigCreationTime = signature.creationTime?.time ?: 0L
        val sigExpirationSec = signature.hashedSubPackets?.signatureExpirationTime ?: 0L
        if (sigExpirationSec > 0) {
            val expirationMillis = sigCreationTime + (sigExpirationSec * 1000L)
            if (System.currentTimeMillis() > expirationMillis) {
                throw ManifestValidationException("OpenPGP release signature has expired")
            }
        }

        // Key expiration check
        val validSeconds = publicKey.validSeconds
        if (validSeconds > 0) {
            val keyCreationTime = publicKey.creationTime.time
            val keyExpirationMillis = keyCreationTime + (validSeconds * 1000L)
            if (System.currentTimeMillis() > keyExpirationMillis) {
                throw ManifestValidationException("OpenPGP release signing key has expired")
            }
        }

        // Cryptographic verification
        val verifierProvider = JcaPGPContentVerifierBuilderProvider().setProvider(provider)
        try {
            signature.init(verifierProvider, publicKey)
            signature.update(dataBytes)
            if (!signature.verify()) {
                throw ManifestValidationException("OpenPGP detached signature verification failed (invalid signature)")
            }
        } catch (e: Exception) {
            if (e is ManifestValidationException) throw e
            throw ManifestValidationException("Cryptographic verification failed: ${e.message}", e)
        }
    }

    fun validateManifestData(
        manifest: ReleaseManifest,
        currentInstalledPackage: String,
        currentInstalledVersionCode: Long,
        currentStoredRevision: Long,
        expectedReleaseTag: String,
        currentTimeMillis: Long = System.currentTimeMillis(),
    ) {
        if (manifest.schema != ReleaseManifest.CURRENT_SCHEMA) {
            throw ManifestValidationException("Unsupported manifest schema: ${manifest.schema}")
        }
        if (manifest.schemaVersion != ReleaseManifest.CURRENT_SCHEMA_VERSION) {
            throw ManifestValidationException("Unsupported manifest schema version: ${manifest.schemaVersion}")
        }
        if (manifest.channel != "stable") {
            throw ManifestValidationException("Unsupported release channel '${manifest.channel}' (stable required)")
        }
        if (manifest.repository.owner != EXPECTED_REPO_OWNER || manifest.repository.name != EXPECTED_REPO_NAME) {
            throw ManifestValidationException("Repository mismatch: ${manifest.repository.owner}/${manifest.repository.name} != $EXPECTED_REPO_OWNER/$EXPECTED_REPO_NAME")
        }
        if (manifest.releaseTag != expectedReleaseTag) {
            throw ManifestValidationException("Release tag mismatch: manifest '${manifest.releaseTag}' != expected '$expectedReleaseTag'")
        }
        if (manifest.applicationId != currentInstalledPackage) {
            throw ManifestValidationException("Application ID mismatch: manifest '${manifest.applicationId}' != installed '$currentInstalledPackage'")
        }

        // Anti-rollback: metadata revision must be monotonic
        if (manifest.metadataRevision < currentStoredRevision) {
            throw ManifestValidationException("Metadata revision rollback detected: ${manifest.metadataRevision} < stored $currentStoredRevision")
        }

        // Anti-downgrade: versionCode must be greater than or equal to current (and for updates, strictly greater than installed)
        if (manifest.versionCode < currentInstalledVersionCode) {
            throw ManifestValidationException("Version code downgrade rejected: ${manifest.versionCode} < installed $currentInstalledVersionCode")
        }

        // Expected APK naming convention
        val expectedApkName = "VRKA-Android-${manifest.releaseTag}.apk"
        if (manifest.apk.filename != expectedApkName) {
            throw ManifestValidationException("Manifest APK filename '${manifest.apk.filename}' does not match expected '$expectedApkName'")
        }
        if (manifest.apk.sizeBytes <= 0L) {
            throw ManifestValidationException("Invalid manifest APK size: ${manifest.apk.sizeBytes}")
        }
        if (!SHA256_HEX_PATTERN.matches(manifest.apk.sha256.lowercase())) {
            throw ManifestValidationException("Invalid manifest APK SHA-256 hash: ${manifest.apk.sha256}")
        }

        // Certificate pinning
        val normalizedManifestCert = manifest.apk.certificateSha256.trim().uppercase().replace(":", "")
        val normalizedExpectedCert = EXPECTED_CERT_SHA256.trim().uppercase().replace(":", "")
        if (normalizedManifestCert != normalizedExpectedCert) {
            throw ManifestValidationException("Signing certificate mismatch: manifest cert ($normalizedManifestCert) does not match production cert ($normalizedExpectedCert)")
        }

        // ABI verification
        if (!manifest.apk.supportedAbis.contains("arm64-v8a")) {
            throw ManifestValidationException("Manifest does not declare support for required ABI 'arm64-v8a'")
        }

        // Timestamp verification
        val issuedTime = parseIso8601(manifest.issuedAt)
            ?: throw ManifestValidationException("Invalid 'issued_at' timestamp: ${manifest.issuedAt}")
        val expiresTime = parseIso8601(manifest.expiresAt)
            ?: throw ManifestValidationException("Invalid 'expires_at' timestamp: ${manifest.expiresAt}")

        // Allow up to 1 hour future skew for issued_at
        if (issuedTime > currentTimeMillis + MAX_CLOCK_SKEW_MS) {
            throw ManifestValidationException("Manifest issued_at timestamp is in the future: ${manifest.issuedAt}")
        }

        // Freeze attack prevention: reject expired metadata
        if (expiresTime <= currentTimeMillis) {
            throw ManifestValidationException("Release metadata has expired: ${manifest.expiresAt} <= current time")
        }
    }

    companion object {
        val PINNED_RELEASE_KEY_ID = 0xD519380BEBA1B9F7UL.toLong()
        const val PINNED_RELEASE_KEY_FINGERPRINT = "71165A658B3A8612AC2568C6D519380BEBA1B9F7"
        const val EXPECTED_CERT_SHA256 = "9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA"
        const val EXPECTED_REPO_OWNER = "MaverickRox"
        const val EXPECTED_REPO_NAME = "VRKA-Android"

        private const val MAX_CLOCK_SKEW_MS = 60 * 60 * 1000L // 1 hour
        private val SHA256_HEX_PATTERN = Regex("^[a-f0-9]{64}$")

        private val bundledProvider: Provider by lazy { BouncyCastleProvider() }

        fun ensureBouncyCastleProvider(): Provider {
            val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (existing == null) {
                Security.addProvider(bundledProvider)
            }
            return bundledProvider
        }

        fun loadAndVerifyPublicKey(input: InputStream): PGPPublicKey {
            val provider = ensureBouncyCastleProvider()
            val collection = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(input),
                JcaKeyFingerprintCalculator().setProvider(provider),
            )

            for (ring in collection) {
                for (key in ring) {
                    if (key.keyID == PINNED_RELEASE_KEY_ID) {
                        val derivedFp = Hex.toHexString(key.fingerprint).uppercase()
                        if (derivedFp != PINNED_RELEASE_KEY_FINGERPRINT) {
                            throw ManifestValidationException(
                                "Derived key fingerprint $derivedFp does not match pinned trust anchor $PINNED_RELEASE_KEY_FINGERPRINT"
                            )
                        }
                        return key
                    }
                }
            }
            throw ManifestValidationException("Release signing key 0x${java.lang.Long.toHexString(PINNED_RELEASE_KEY_ID).uppercase()} not found in keyring")
        }

        fun parseIso8601(isoString: String): Long? {
            return try {
                val clean = isoString.trim()
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(clean)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
