package com.mvrk.vrka

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Attribute
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.SignedData
import org.bouncycastle.asn1.pkcs.SignerInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.X509Extension
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

@Suppress("DEPRECATION")
object TestXpiSigner {

    data class TestAuthority(
        val caKeyPair: KeyPair,
        val caCert: X509Certificate,
        val caSpkiSha256: String,
    )

    fun createTestAuthority(
        caSubject: String = "CN=VRKA Test Addons Signing CA, O=VRKA Test, C=US",
        nowMs: Long = System.currentTimeMillis(),
    ): TestAuthority {
        SecureComponentUpdater.ensureBouncyCastleProvider()
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val caKeys = kpg.generateKeyPair()

        val principal = X500Principal(caSubject)
        val gen = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.valueOf(1L))
            setIssuerDN(principal)
            setSubjectDN(principal)
            setNotBefore(Date(nowMs - 86_400_000L))
            setNotAfter(Date(nowMs + 365L * 86_400_000L))
            setPublicKey(caKeys.public)
            setSignatureAlgorithm("SHA256withRSA")
            addExtension(X509Extension.basicConstraints, true, BasicConstraints(true))
            addExtension(
                X509Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
        }
        val caCert = gen.generate(caKeys.private)
        val spkiSha256 = XpiSignatureVerifier.sha256Hex(caCert.publicKey.encoded)
        return TestAuthority(caKeys, caCert, spkiSha256)
    }

    fun buildSignedXpi(
        authority: TestAuthority,
        id: String,
        version: String,
        minGecko: String = "115.0",
        maxGecko: String? = null,
        extraEntries: Map<String, ByteArray> = emptyMap(),
        includeSignature: Boolean = true,
        leafCnOverride: String? = null,
        corruptRsaSignature: Boolean = false,
        tamperFileContentAfterSigning: Pair<String, ByteArray>? = null,
        omitCodeSigningEku: Boolean = false,
        leafExpired: Boolean = false,
        fakeUnsignedEntriesOnly: Boolean = false,
        extraMetaEntry: Pair<String, ByteArray>? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        SecureComponentUpdater.ensureBouncyCastleProvider()
        val geckoMaxLine = if (maxGecko != null) {
            ",\n      \"strict_max_version\": \"$maxGecko\""
        } else {
            ""
        }
        val manifestJson = """
        {
          "manifest_version": 2,
          "name": "Extension",
          "version": "$version",
          "browser_specific_settings": {
            "gecko": {
              "id": "$id",
              "strict_min_version": "$minGecko"$geckoMaxLine
            }
          }
        }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val payloadEntries = LinkedHashMap<String, ByteArray>()
        payloadEntries["manifest.json"] = manifestJson
        payloadEntries.putAll(extraEntries)

        val encoder = Base64.getEncoder()
        val mfBuilder = StringBuilder("Manifest-Version: 1.0\n\n")
        for ((name, data) in payloadEntries) {
            val digestB64 = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(data))
            mfBuilder.append("Name: ").append(name).append('\n')
            mfBuilder.append("Digest-Algorithms: SHA256\n")
            mfBuilder.append("SHA256-Digest: ").append(digestB64).append("\n\n")
        }
        val mfBytes = mfBuilder.toString().toByteArray(Charsets.UTF_8)

        val mfDigestB64 = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(mfBytes))
        val sfBytes = ("Signature-Version: 1.0\nSHA256-Digest-Manifest: $mfDigestB64\n\n")
            .toByteArray(Charsets.UTF_8)

        val rsaBytes: ByteArray = if (fakeUnsignedEntriesOnly) {
            "not-a-pkcs7-signature".toByteArray(Charsets.UTF_8)
        } else {
            val leafKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val leafSubject = X500Principal("CN=${leafCnOverride ?: id}, O=Addons Test, C=US")
            val notBefore = if (leafExpired) Date(nowMs - 10L * 86_400_000L) else Date(nowMs - 3_600_000L)
            val notAfter = if (leafExpired) Date(nowMs - 86_400_000L) else Date(nowMs + 30L * 86_400_000L)

            val leafGen = X509V3CertificateGenerator().apply {
                setSerialNumber(BigInteger.valueOf(System.nanoTime() and 0x7FFFFFFFFFFFFFFFL))
                setIssuerDN(authority.caCert.subjectX500Principal)
                setSubjectDN(leafSubject)
                setNotBefore(notBefore)
                setNotAfter(notAfter)
                setPublicKey(leafKeys.public)
                setSignatureAlgorithm("SHA256withRSA")
                addExtension(X509Extension.basicConstraints, true, BasicConstraints(false))
                addExtension(X509Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                if (!omitCodeSigningEku) {
                    addExtension(
                        X509Extension.extendedKeyUsage,
                        false,
                        ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning),
                    )
                }
            }
            val leafCert = leafGen.generate(authority.caKeyPair.private)

            val sfHash = MessageDigest.getInstance("SHA-256").digest(sfBytes)
            val attrVec = ASN1EncodableVector().apply {
                add(Attribute(PKCSObjectIdentifiers.pkcs_9_at_contentType, DERSet(PKCSObjectIdentifiers.data)))
                add(Attribute(PKCSObjectIdentifiers.pkcs_9_at_messageDigest, DERSet(DEROctetString(sfHash))))
            }
            val authAttrSet = DERSet(attrVec)
            val signedBytes = authAttrSet.getEncoded(ASN1Encoding.DER)

            val sigBytes = Signature.getInstance("SHA256withRSA").run {
                initSign(leafKeys.private)
                update(signedBytes)
                sign()
            }
            if (corruptRsaSignature && sigBytes.isNotEmpty()) {
                sigBytes[sigBytes.lastIndex] = (sigBytes.last().toInt() xor 0xFF).toByte()
            }

            val sha256Alg = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE)
            val rsaSha256Alg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val ias = IssuerAndSerialNumber(
                X500Name.getInstance(authority.caCert.subjectX500Principal.encoded),
                leafCert.serialNumber,
            )
            val signerInfo = SignerInfo(
                ASN1Integer(1L),
                ias,
                sha256Alg,
                authAttrSet,
                rsaSha256Alg,
                DEROctetString(sigBytes),
                null,
            )
            val certPrimitives = ASN1EncodableVector().apply {
                add(ASN1Primitive.fromByteArray(leafCert.encoded))
                add(ASN1Primitive.fromByteArray(authority.caCert.encoded))
            }
            val signedData = SignedData(
                ASN1Integer(1L),
                DERSet(sha256Alg),
                ContentInfo(PKCSObjectIdentifiers.data, null),
                DERSet(certPrimitives),
                null,
                DERSet(signerInfo),
            )
            ContentInfo(PKCSObjectIdentifiers.signedData, signedData).getEncoded(ASN1Encoding.DER)
        }

        if (tamperFileContentAfterSigning != null) {
            payloadEntries[tamperFileContentAfterSigning.first] = tamperFileContentAfterSigning.second
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in payloadEntries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
            if (includeSignature) {
                zos.putNextEntry(ZipEntry("META-INF/manifest.mf"))
                zos.write(if (fakeUnsignedEntriesOnly) "Manifest-Version: 1.0\n\n".toByteArray() else mfBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/mozilla.sf"))
                zos.write(if (fakeUnsignedEntriesOnly) "Signature-Version: 1.0\n\n".toByteArray() else sfBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/mozilla.rsa"))
                zos.write(rsaBytes)
                zos.closeEntry()
            }
            if (extraMetaEntry != null) {
                zos.putNextEntry(ZipEntry(extraMetaEntry.first))
                zos.write(extraMetaEntry.second)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
