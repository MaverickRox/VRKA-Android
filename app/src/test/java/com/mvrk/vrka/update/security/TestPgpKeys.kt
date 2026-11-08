package com.mvrk.vrka.update.security

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ephemeral OpenPGP keys for unit tests.
 *
 * Tests must never discover or read a developer's release signing key. Every cryptographic
 * test generates its own short-lived key pair, keeps it in memory/temporary storage, and
 * verifies through a verifier configured with that temporary public key. The production
 * trust anchor is exercised separately against the published public key and detached
 * signature, which requires no secret material.
 */
internal object TestPgpKeys {
    private val sequence = AtomicInteger()

    data class KeyMaterial(
        val publicKey: PGPPublicKey,
        val secretKeyRing: ByteArray,
    )

    fun generate(): KeyMaterial {
        ReleaseManifestVerifier.ensureBouncyCastleProvider()
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val pgpKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPair, Date())
        val signer = JcaPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA256)
            .setProvider("BC")
        val generator = org.bouncycastle.openpgp.PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            "vrka-hermetic-test-${sequence.incrementAndGet()}",
            JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build().get(HashAlgorithmTags.SHA1),
            null,
            null,
            signer,
            null,
        )
        val secretRing = generator.generateSecretKeyRing()
        val bytes = ByteArrayOutputStream().use { output ->
            secretRing.encode(output)
            output.toByteArray()
        }
        return KeyMaterial(secretRing.publicKey, bytes)
    }

    fun verifierFor(key: KeyMaterial): ReleaseManifestVerifier =
        ReleaseManifestVerifier(customPublicKeySupplier = { key.publicKey })

    fun sign(data: ByteArray, key: KeyMaterial): ByteArray =
        sign(data, key, HashAlgorithmTags.SHA256)

    fun sign(data: ByteArray, key: KeyMaterial, hashAlgorithm: Int): ByteArray {
        ReleaseManifestVerifier.ensureBouncyCastleProvider()
        val decoder = PGPUtil.getDecoderStream(ByteArrayInputStream(key.secretKeyRing))
        val collection = PGPSecretKeyRingCollection(
            decoder,
            JcaKeyFingerprintCalculator().setProvider("BC"),
        )
        val secretKey = collection.asSequence()
            .flatMap { it.secretKeys.asSequence() }
            .first { it.isSigningKey }
        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder().setProvider("BC").build("".toCharArray()),
        )
        val generator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, hashAlgorithm).setProvider("BC"),
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        val subpackets = PGPSignatureSubpacketGenerator()
        subpackets.setIssuerFingerprint(false, secretKey.publicKey)
        generator.setHashedSubpackets(subpackets.generate())
        generator.update(data)
        val signature = generator.generate()
        return ByteArrayOutputStream().use { output ->
            ArmoredOutputStream(output).use { armored -> signature.encode(armored) }
            output.toByteArray()
        }
    }
}
