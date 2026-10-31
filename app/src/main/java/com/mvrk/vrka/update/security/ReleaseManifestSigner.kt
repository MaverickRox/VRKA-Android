package com.mvrk.vrka.update.security

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.sig.IssuerFingerprint
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ReleaseManifestSigner {

    fun signDetached(
        dataBytes: ByteArray,
        secretKeyStream: InputStream,
        passphrase: CharArray = "".toCharArray(),
    ): ByteArray {
        ReleaseManifestVerifier.ensureBouncyCastleProvider()
        val decoder = PGPUtil.getDecoderStream(secretKeyStream)
        val collection = PGPSecretKeyRingCollection(
            decoder,
            JcaKeyFingerprintCalculator().setProvider("BC"),
        )

        val secKey = collection.asSequence()
            .flatMap { it.secretKeys.asSequence() }
            .firstOrNull { it.isSigningKey }
            ?: throw IllegalArgumentException("No signing key found in secret key stream")

        val decryptor = JcePBESecretKeyDecryptorBuilder()
            .setProvider("BC")
            .build(passphrase)
        val privKey = secKey.extractPrivateKey(decryptor)

        val signerBuilder = JcaPGPContentSignerBuilder(secKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
            .setProvider("BC")
        val sigGen = PGPSignatureGenerator(signerBuilder)
        sigGen.init(PGPSignature.BINARY_DOCUMENT, privKey)

        // Add Issuer Fingerprint subpacket (RFC 4880bis / RFC 9580)
        val subGen = PGPSignatureSubpacketGenerator()
        subGen.setIssuerFingerprint(false, secKey.publicKey)
        sigGen.setHashedSubpackets(subGen.generate())

        sigGen.update(dataBytes)
        val signature = sigGen.generate()

        val baos = ByteArrayOutputStream()
        ArmoredOutputStream(baos).use { aos ->
            signature.encode(aos)
        }
        return baos.toByteArray()
    }
}
