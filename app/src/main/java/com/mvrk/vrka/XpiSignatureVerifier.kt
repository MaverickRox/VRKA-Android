package com.mvrk.vrka

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Attribute
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.SignedData
import org.bouncycastle.asn1.pkcs.SignerInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.security.auth.x500.X500Principal

/**
 * Cryptographic verifier for Mozilla WebExtension XPI archives (PKCS#7 / CMS signature,
 * X.509 certificate pinning to Mozilla AMO signing CA, JAR SF/MF digest chains,
 * per-entry SHA-256 verification, and manifest identity/version/GeckoView checks).
 */
object XpiSignatureVerifier {

    private const val ENTRY_MANIFEST_JSON = "manifest.json"
    private const val ENTRY_META_RSA = "META-INF/mozilla.rsa"
    private const val ENTRY_META_SF = "META-INF/mozilla.sf"
    private const val ENTRY_META_MF = "META-INF/manifest.mf"
    private val UNHASHED_META_ENTRIES = setOf(ENTRY_META_RSA, ENTRY_META_SF, ENTRY_META_MF)

    private const val OID_EXT_KEY_USAGE_CODE_SIGNING = "1.3.6.1.5.5.7.3.3"
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private const val MAX_CHAIN_DEPTH = 6

    private data class ArchiveReadResult(
        val metadataBytes: Map<String, ByteArray>,
        val entrySha256Base64: Map<String, String>,
        val metaInfEntries: Set<String>,
    )

    private data class DigestSpec(
        val jcaDigestName: String,
        val rsaPrefix: String,
    )

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(16 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return bytesToHex(digest.digest())
    }

    fun sha256Hex(bytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun verifyXpi(
        xpiFile: File,
        expectedId: String,
        expectedVersion: String,
        installedGeckoVersion: String,
        trustedCaSpkiSha256: Set<String> = SecureComponentUpdater.TRUSTED_EXTENSION_CA_SPKI_SHA256,
        clock: () -> Long = { System.currentTimeMillis() },
    ): SecureComponentUpdater.XpiValidationResult {
        if (!xpiFile.exists() || !xpiFile.isFile || xpiFile.length() == 0L) {
            throw FileNotFoundException("XPI file is missing or empty: ${xpiFile.path}")
        }
        if (trustedCaSpkiSha256.isEmpty()) {
            throw SecurityException("Extension signature validation failed: no trusted CA SPKI anchors configured")
        }

        val normalizedAnchors = trustedCaSpkiSha256.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val archive = readAndHashArchive(xpiFile)

        val manifestJsonBytes = archive.metadataBytes[ENTRY_MANIFEST_JSON]
            ?: throw SecurityException("Archive does not contain $ENTRY_MANIFEST_JSON")

        val rsaBytes = archive.metadataBytes[ENTRY_META_RSA]
            ?: throw SecurityException("Extension signature validation failed: XPI archive is missing Mozilla signature ($ENTRY_META_RSA)")
        val sfBytes = archive.metadataBytes[ENTRY_META_SF]
            ?: throw SecurityException("Extension signature validation failed: XPI archive is missing Mozilla signature ($ENTRY_META_SF)")
        val mfBytes = archive.metadataBytes[ENTRY_META_MF]
            ?: throw SecurityException("Extension signature validation failed: XPI archive is missing Mozilla signature ($ENTRY_META_MF)")

        if (rsaBytes.isEmpty() || sfBytes.isEmpty() || mfBytes.isEmpty()) {
            throw SecurityException("Extension signature validation failed: XPI archive has empty signature metadata")
        }

        // Reject ambiguous/extra unverified signature blocks under META-INF/
        for (metaEntry in archive.metaInfEntries) {
            if (metaEntry in UNHASHED_META_ENTRIES) continue
            val lower = metaEntry.lowercase(Locale.ROOT)
            if (lower.endsWith(".rsa") || lower.endsWith(".sf") || lower.endsWith(".dsa") || lower.endsWith(".ec")) {
                throw SecurityException(
                    "Extension signature validation failed: unexpected extra signature entry $metaEntry"
                )
            }
        }

        // 1. Verify PKCS#7 SignedData over META-INF/mozilla.sf & X.509 certificate trust chain
        verifyPkcs7OverSignatureFile(
            rsaBytes = rsaBytes,
            sfBytes = sfBytes,
            expectedId = expectedId,
            trustedCaSpkiSha256 = normalizedAnchors,
            verificationTime = Date(clock()),
        )

        // 2. Verify META-INF/mozilla.sf commits cryptographically to META-INF/manifest.mf
        verifySignatureFileMatchesManifest(sfBytes = sfBytes, mfBytes = mfBytes)

        // 3. Verify every ZIP entry (and every META-INF/manifest.mf record) matches SHA256-Digest
        verifyManifestCoversAllArchiveEntries(mfBytes = mfBytes, entrySha256Base64 = archive.entrySha256Base64)

        // 4. Parse and verify manifest.json identity, version, and GeckoView compatibility
        val json = try {
            JSONObject(manifestJsonBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SecurityException("Invalid manifest.json in XPI archive: ${e.message}", e)
        }
        val rawVer = json.optString("version").trim()
        val ver = ComponentUpdateManager.cleanVersionString(rawVer)
        if (ver.isBlank()) {
            throw SecurityException("Manifest does not contain a valid version")
        }
        if (expectedVersion.isNotBlank()) {
            val cleanExpected = ComponentUpdateManager.cleanVersionString(expectedVersion)
            if (ver != cleanExpected) {
                throw SecurityException("Extension version mismatch: expected $cleanExpected, got '$ver'")
            }
        }

        val geckoObj = json.optJSONObject("browser_specific_settings")?.optJSONObject("gecko")
            ?: json.optJSONObject("applications")?.optJSONObject("gecko")
        val id = geckoObj?.optString("id")?.trim() ?: ""
        if (id != expectedId) {
            throw SecurityException("Extension ID mismatch: expected $expectedId, got '$id'")
        }

        val minGeckoVer = geckoObj?.optString("strict_min_version")?.trim()?.takeIf { it.isNotBlank() }
        val maxGeckoVer = geckoObj?.optString("strict_max_version")?.trim()?.takeIf { it.isNotBlank() }
        if (minGeckoVer != null && ComponentUpdateManager.isNewerVersion(minGeckoVer, installedGeckoVersion)) {
            throw IllegalStateException(
                "Extension requires GeckoView min version $minGeckoVer, but installed version is $installedGeckoVersion"
            )
        }
        if (maxGeckoVer != null && ComponentUpdateManager.isNewerVersion(installedGeckoVersion, maxGeckoVer)) {
            throw IllegalStateException(
                "Extension requires GeckoView max version $maxGeckoVer, but installed version is $installedGeckoVersion"
            )
        }

        return SecureComponentUpdater.XpiValidationResult(
            id = id,
            version = ver,
            minGeckoVersion = minGeckoVer,
            maxGeckoVersion = maxGeckoVer,
            hasSignatures = true,
        )
    }

    private fun readAndHashArchive(xpiFile: File): ArchiveReadResult {
        val centralRegularEntries = linkedSetOf<String>()
        try {
            ZipFile(xpiFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    validateArchiveEntryName(entry.name)
                    if (!entry.isDirectory) {
                        if (!centralRegularEntries.add(entry.name)) {
                            throw SecurityException(
                                "Extension signature validation failed: duplicate central directory entry ${entry.name}"
                            )
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: malformed XPI archive (${e.message})", e)
        }

        val metadataBytes = HashMap<String, ByteArray>(4)
        val entrySha256Base64 = LinkedHashMap<String, String>()
        val metaInfEntries = linkedSetOf<String>()
        val seenEntries = HashSet<String>()
        val seenRegularEntries = HashSet<String>()
        val encoder = Base64.getEncoder()

        try {
            ZipInputStream(xpiFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(16 * 1024)
                while (entry != null) {
                    val name = entry.name
                    validateArchiveEntryName(name)
                    if (!seenEntries.add(name)) {
                        throw SecurityException(
                            "Extension signature validation failed: duplicate ZIP entry $name"
                        )
                    }

                    if (entry.isDirectory) {
                        // Ensure directory entry has zero bytes
                        if (zis.read(buf) != -1) {
                            throw SecurityException(
                                "Extension signature validation failed: non-empty directory entry $name"
                            )
                        }
                    } else {
                        seenRegularEntries.add(name)
                        if (name.startsWith("META-INF/")) {
                            metaInfEntries.add(name)
                        }
                        val captureMetadata =
                            name == ENTRY_MANIFEST_JSON || name in UNHASHED_META_ENTRIES
                        val baos = if (captureMetadata) ByteArrayOutputStream() else null
                        val digest = MessageDigest.getInstance("SHA-256")
                        var totalRead = 0
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) {
                            digest.update(buf, 0, n)
                            if (baos != null) {
                                totalRead += n
                                if (totalRead > MAX_METADATA_BYTES) {
                                    throw SecurityException(
                                        "Extension signature validation failed: metadata entry $name exceeds maximum allowed size"
                                    )
                                }
                                baos.write(buf, 0, n)
                            }
                        }
                        if (baos != null) {
                            metadataBytes[name] = baos.toByteArray()
                        }
                        entrySha256Base64[name] = encoder.encodeToString(digest.digest())
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: corrupt XPI stream (${e.message})", e)
        }

        if (seenRegularEntries != centralRegularEntries) {
            throw SecurityException(
                "Extension signature validation failed: ZIP local headers do not match central directory"
            )
        }

        return ArchiveReadResult(
            metadataBytes = metadataBytes,
            entrySha256Base64 = entrySha256Base64,
            metaInfEntries = metaInfEntries,
        )
    }

    private fun validateArchiveEntryName(name: String) {
        if (name.isEmpty()) {
            throw SecurityException("Empty entry name in archive")
        }
        if (name.contains('\u0000') || name.contains("\\") || name.startsWith("/") ||
            (name.length >= 2 && name[1] == ':')
        ) {
            throw SecurityException("Path traversal attempt in archive: $name")
        }
        val trimmed = name.removeSuffix("/")
        if (trimmed.split('/').any { it == ".." || it == "." }) {
            throw SecurityException("Path traversal attempt in archive: $name")
        }
    }

    private fun verifyPkcs7OverSignatureFile(
        rsaBytes: ByteArray,
        sfBytes: ByteArray,
        expectedId: String,
        trustedCaSpkiSha256: Set<String>,
        verificationTime: Date,
    ) {
        val contentInfo = try {
            ASN1InputStream(rsaBytes).use { asn1 ->
                val obj = asn1.readObject()
                    ?: throw SecurityException("Extension signature validation failed: empty PKCS#7 structure")
                if (asn1.readObject() != null) {
                    throw SecurityException("Extension signature validation failed: trailing bytes in PKCS#7 signature")
                }
                ContentInfo.getInstance(obj)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: malformed PKCS#7 DER (${e.message})", e)
        }

        if (contentInfo.contentType != PKCSObjectIdentifiers.signedData) {
            throw SecurityException("Extension signature validation failed: PKCS#7 content is not SignedData")
        }

        val signedData = try {
            SignedData.getInstance(contentInfo.content)
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: invalid SignedData (${e.message})", e)
        }

        val encapContent = signedData.contentInfo?.content
        if (encapContent != null) {
            val embeddedBytes = (encapContent as? ASN1OctetString)?.octets
                ?: throw SecurityException("Extension signature validation failed: non-octet encapsulated content")
            if (!MessageDigest.isEqual(embeddedBytes, sfBytes)) {
                throw SecurityException("Extension signature validation failed: embedded content does not match mozilla.sf")
            }
        }

        val certFactory = CertificateFactory.getInstance("X.509")
        val certSet = signedData.certificates
            ?: throw SecurityException("Extension signature validation failed: no certificates embedded in PKCS#7")
        if (certSet.size() == 0 || certSet.size() > 16) {
            throw SecurityException("Extension signature validation failed: unexpected certificate count ${certSet.size()}")
        }
        val certs = ArrayList<X509Certificate>(certSet.size())
        for (i in 0 until certSet.size()) {
            val encoded = certSet.getObjectAt(i).toASN1Primitive().getEncoded(ASN1Encoding.DER)
            val x509 = certFactory.generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            certs.add(x509)
        }

        val signerInfos = signedData.signerInfos
        if (signerInfos == null || signerInfos.size() == 0) {
            throw SecurityException("Extension signature validation failed: PKCS#7 SignedData contains no SignerInfo")
        }

        for (i in 0 until signerInfos.size()) {
            val signerInfo = try {
                SignerInfo.getInstance(signerInfos.getObjectAt(i))
            } catch (e: Exception) {
                throw SecurityException("Extension signature validation failed: invalid SignerInfo (${e.message})", e)
            }

            verifySingleSignerInfo(
                signerInfo = signerInfo,
                encapContentType = signedData.contentInfo.contentType,
                sfBytes = sfBytes,
                certs = certs,
                expectedId = expectedId,
                trustedCaSpkiSha256 = trustedCaSpkiSha256,
                verificationTime = verificationTime,
            )
        }
    }

    private fun verifySingleSignerInfo(
        signerInfo: SignerInfo,
        encapContentType: ASN1ObjectIdentifier,
        sfBytes: ByteArray,
        certs: List<X509Certificate>,
        expectedId: String,
        trustedCaSpkiSha256: Set<String>,
        verificationTime: Date,
    ) {
        val ias = try {
            IssuerAndSerialNumber.getInstance(signerInfo.issuerAndSerialNumber)
        } catch (e: Exception) {
            throw SecurityException(
                "Extension signature validation failed: unsupported SignerIdentifier in PKCS#7",
                e,
            )
        }
        val expectedIssuer = X500Principal(ias.name.getEncoded(ASN1Encoding.DER))
        val expectedSerial = ias.certificateSerialNumber.value
        val leafCert = certs.firstOrNull {
            it.issuerX500Principal == expectedIssuer && it.serialNumber == expectedSerial
        } ?: throw SecurityException(
            "Extension signature validation failed: signer certificate not found in PKCS#7 chain"
        )

        val digestSpec = resolveDigestSpec(signerInfo.digestAlgorithm.algorithm)
        val sigAlgName = resolveSignatureAlgorithm(digestSpec, signerInfo.digestEncryptionAlgorithm.algorithm)

        val authAttrs: ASN1Set? = signerInfo.authenticatedAttributes
        val dataToVerify: ByteArray = if (authAttrs != null) {
            verifyAuthenticatedAttributes(
                authAttrs = authAttrs,
                encapContentType = encapContentType,
                sfBytes = sfBytes,
                jcaDigestName = digestSpec.jcaDigestName,
            )
            // RFC 5652 Section 5.4: re-tag IMPLICIT [0] SET OF as DER SET OF (0x31)
            DERSet(authAttrs.toArray()).getEncoded(ASN1Encoding.DER)
        } else {
            sfBytes
        }

        val signatureBytes = signerInfo.encryptedDigest.octets
        val verified = try {
            Signature.getInstance(sigAlgName).run {
                initVerify(leafCert.publicKey)
                update(dataToVerify)
                verify(signatureBytes)
            }
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: signature verification error (${e.message})", e)
        }

        if (!verified) {
            throw SecurityException("Extension signature validation failed: PKCS#7 cryptographic signature invalid")
        }

        verifyLeafCertificateAndChain(
            leafCert = leafCert,
            certs = certs,
            expectedId = expectedId,
            trustedCaSpkiSha256 = trustedCaSpkiSha256,
            verificationTime = verificationTime,
        )
    }

    private fun verifyAuthenticatedAttributes(
        authAttrs: ASN1Set,
        encapContentType: ASN1ObjectIdentifier,
        sfBytes: ByteArray,
        jcaDigestName: String,
    ) {
        var foundContentType = false
        var foundMessageDigest = false
        val actualSfDigest = MessageDigest.getInstance(jcaDigestName).digest(sfBytes)

        for (i in 0 until authAttrs.size()) {
            val attr = Attribute.getInstance(authAttrs.getObjectAt(i))
            when (attr.attrType) {
                PKCSObjectIdentifiers.pkcs_9_at_contentType -> {
                    if (foundContentType || attr.attrValues.size() != 1) {
                        throw SecurityException(
                            "Extension signature validation failed: invalid contentType authenticated attribute"
                        )
                    }
                    val ct = ASN1ObjectIdentifier.getInstance(attr.attrValues.getObjectAt(0))
                    if (ct != encapContentType) {
                        throw SecurityException(
                            "Extension signature validation failed: authenticated contentType mismatch"
                        )
                    }
                    foundContentType = true
                }
                PKCSObjectIdentifiers.pkcs_9_at_messageDigest -> {
                    if (foundMessageDigest || attr.attrValues.size() != 1) {
                        throw SecurityException(
                            "Extension signature validation failed: invalid messageDigest authenticated attribute"
                        )
                    }
                    val md = ASN1OctetString.getInstance(attr.attrValues.getObjectAt(0)).octets
                    if (!MessageDigest.isEqual(md, actualSfDigest)) {
                        throw SecurityException(
                            "Extension signature validation failed: mozilla.sf digest mismatch in PKCS#7 attributes"
                        )
                    }
                    foundMessageDigest = true
                }
            }
        }

        if (!foundContentType || !foundMessageDigest) {
            throw SecurityException(
                "Extension signature validation failed: missing required CMS contentType/messageDigest attributes"
            )
        }
    }

    private fun verifyLeafCertificateAndChain(
        leafCert: X509Certificate,
        certs: List<X509Certificate>,
        expectedId: String,
        trustedCaSpkiSha256: Set<String>,
        verificationTime: Date,
    ) {
        try {
            leafCert.checkValidity(verificationTime)
        } catch (e: Exception) {
            throw SecurityException(
                "Extension signature validation failed: leaf signing certificate is expired or not yet valid",
                e,
            )
        }

        if (leafCert.basicConstraints != -1) {
            throw SecurityException(
                "Extension signature validation failed: leaf signing certificate must not be a CA"
            )
        }

        val leafKeyUsage = leafCert.keyUsage
        if (leafKeyUsage != null && !leafKeyUsage[0]) {
            throw SecurityException(
                "Extension signature validation failed: leaf certificate lacks digitalSignature KeyUsage"
            )
        }

        val eku = leafCert.extendedKeyUsage ?: emptyList()
        if (OID_EXT_KEY_USAGE_CODE_SIGNING !in eku) {
            throw SecurityException(
                "Extension signature validation failed: leaf certificate lacks id-kp-codeSigning EKU"
            )
        }

        val subjectX500 = X500Name.getInstance(leafCert.subjectX500Principal.encoded)
        val cnValues = subjectX500.getRDNs(BCStyle.CN).flatMap { rdn ->
            rdn.typesAndValues.filter { it.type == BCStyle.CN }.map { IETFUtils.valueToString(it.value) }
        }
        if (cnValues.size != 1 || cnValues[0] != expectedId) {
            throw SecurityException(
                "Extension signature validation failed: signing certificate CN mismatch (expected $expectedId, got $cnValues)"
            )
        }

        // Walk certificate chain upward to a pinned CA SPKI anchor
        val visited = HashSet<X509Certificate>()
        var current: X509Certificate = leafCert
        var intermediatesBelowCa = 0

        for (depth in 0 until MAX_CHAIN_DEPTH) {
            visited.add(current)
            val issuerPrincipal = current.issuerX500Principal
            val candidates = certs.filter { candidate ->
                candidate != current &&
                    candidate !in visited &&
                    candidate.subjectX500Principal == issuerPrincipal
            }
            if (candidates.isEmpty()) break

            var advanced = false
            for (issuerCert in candidates) {
                val signedByIssuer = runCatching {
                    current.verify(issuerCert.publicKey)
                    true
                }.getOrDefault(false)
                if (!signedByIssuer) continue

                try {
                    issuerCert.checkValidity(verificationTime)
                } catch (e: Exception) {
                    throw SecurityException(
                        "Extension signature validation failed: CA certificate in chain is expired or not yet valid",
                        e,
                    )
                }

                if (issuerCert.basicConstraints < intermediatesBelowCa) {
                    throw SecurityException(
                        "Extension signature validation failed: issuer certificate lacks valid CA BasicConstraints"
                    )
                }
                val caKeyUsage = issuerCert.keyUsage
                if (caKeyUsage == null || caKeyUsage.size <= 5 || !caKeyUsage[5]) {
                    throw SecurityException(
                        "Extension signature validation failed: issuer CA certificate lacks keyCertSign KeyUsage"
                    )
                }

                val issuerSpkiHash = sha256Hex(issuerCert.publicKey.encoded)
                if (issuerSpkiHash in trustedCaSpkiSha256) {
                    return
                }

                current = issuerCert
                intermediatesBelowCa++
                advanced = true
                break
            }
            if (!advanced) break
        }

        throw SecurityException(
            "Extension signature validation failed: signing certificate chain is not anchored to a trusted CA"
        )
    }

    private fun resolveDigestSpec(oid: ASN1ObjectIdentifier): DigestSpec = when (oid) {
        NISTObjectIdentifiers.id_sha256 -> DigestSpec("SHA-256", "SHA256")
        NISTObjectIdentifiers.id_sha384 -> DigestSpec("SHA-384", "SHA384")
        NISTObjectIdentifiers.id_sha512 -> DigestSpec("SHA-512", "SHA512")
        else -> throw SecurityException(
            "Extension signature validation failed: unsupported or weak CMS digest algorithm ${oid.id}"
        )
    }

    private fun resolveSignatureAlgorithm(
        digestSpec: DigestSpec,
        sigAlgOid: ASN1ObjectIdentifier,
    ): String = when (sigAlgOid) {
        PKCSObjectIdentifiers.rsaEncryption -> "${digestSpec.rsaPrefix}withRSA"
        PKCSObjectIdentifiers.sha256WithRSAEncryption -> {
            if (digestSpec.jcaDigestName != "SHA-256") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-256 RSA OID")
            }
            "SHA256withRSA"
        }
        PKCSObjectIdentifiers.sha384WithRSAEncryption -> {
            if (digestSpec.jcaDigestName != "SHA-384") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-384 RSA OID")
            }
            "SHA384withRSA"
        }
        PKCSObjectIdentifiers.sha512WithRSAEncryption -> {
            if (digestSpec.jcaDigestName != "SHA-512") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-512 RSA OID")
            }
            "SHA512withRSA"
        }
        X9ObjectIdentifiers.ecdsa_with_SHA256 -> {
            if (digestSpec.jcaDigestName != "SHA-256") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-256 ECDSA OID")
            }
            "SHA256withECDSA"
        }
        X9ObjectIdentifiers.ecdsa_with_SHA384 -> {
            if (digestSpec.jcaDigestName != "SHA-384") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-384 ECDSA OID")
            }
            "SHA384withECDSA"
        }
        X9ObjectIdentifiers.ecdsa_with_SHA512 -> {
            if (digestSpec.jcaDigestName != "SHA-512") {
                throw SecurityException("Extension signature validation failed: mismatched SHA-512 ECDSA OID")
            }
            "SHA512withECDSA"
        }
        else -> throw SecurityException(
            "Extension signature validation failed: unsupported or weak signature algorithm ${sigAlgOid.id}"
        )
    }

    private fun verifySignatureFileMatchesManifest(sfBytes: ByteArray, mfBytes: ByteArray) {
        val sfText = sfBytes.toString(Charsets.UTF_8)
        val sections = parseManifestSections(sfText)
        val mainHeaders = sections.firstOrNull()
            ?: throw SecurityException("Extension signature validation failed: empty META-INF/mozilla.sf")
        val sfManifestDigestB64 = mainHeaders["sha256-digest-manifest"]?.trim()
            ?: throw SecurityException(
                "Extension signature validation failed: META-INF/mozilla.sf missing SHA256-Digest-Manifest"
            )
        val expectedMfDigest = decodeBase64Strict(sfManifestDigestB64, "SHA256-Digest-Manifest")
        val actualMfDigest = MessageDigest.getInstance("SHA-256").digest(mfBytes)
        if (!MessageDigest.isEqual(expectedMfDigest, actualMfDigest)) {
            throw SecurityException(
                "Extension signature validation failed: META-INF/manifest.mf digest does not match META-INF/mozilla.sf"
            )
        }
    }

    private fun verifyManifestCoversAllArchiveEntries(
        mfBytes: ByteArray,
        entrySha256Base64: Map<String, String>,
    ) {
        val mfText = mfBytes.toString(Charsets.UTF_8)
        val sections = parseManifestSections(mfText)
        if (sections.isEmpty()) {
            throw SecurityException("Extension signature validation failed: empty META-INF/manifest.mf")
        }

        val manifestDigests = LinkedHashMap<String, String>()
        for (idx in 1 until sections.size) {
            val section = sections[idx]
            val name = section["name"]
                ?: throw SecurityException("Extension signature validation failed: manifest section missing Name attribute")
            if (name in manifestDigests) {
                throw SecurityException(
                    "Extension signature validation failed: duplicate manifest section for $name"
                )
            }
            val sha256B64 = section["sha256-digest"]?.trim()
                ?: throw SecurityException(
                    "Extension signature validation failed: manifest entry '$name' missing SHA256-Digest"
                )
            manifestDigests[name] = sha256B64
        }

        val requiredSignedEntries = entrySha256Base64.keys.filterNot { it in UNHASHED_META_ENTRIES }
        for (entryName in requiredSignedEntries) {
            val expectedB64 = manifestDigests[entryName]
                ?: throw SecurityException(
                    "Extension signature validation failed: unsigned archive entry not in manifest.mf: $entryName"
                )
            val expectedBytes = decodeBase64Strict(expectedB64, "manifest SHA256-Digest for $entryName")
            val actualBytes = Base64.getDecoder().decode(entrySha256Base64.getValue(entryName))
            if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
                throw SecurityException(
                    "Extension signature validation failed: SHA-256 digest mismatch for archive entry $entryName"
                )
            }
        }

        for (manifestEntry in manifestDigests.keys) {
            if (manifestEntry !in entrySha256Base64) {
                throw SecurityException(
                    "Extension signature validation failed: manifest entry missing from archive: $manifestEntry"
                )
            }
        }
    }

    private fun parseManifestSections(rawText: String): List<Map<String, String>> {
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        // Unfold JAR manifest continuation lines (lines beginning with a single space)
        val rawLines = normalized.split('\n')
        val blocks = mutableListOf<Map<String, String>>()
        var currentLines = mutableListOf<String>()

        fun flushBlock() {
            if (currentLines.isEmpty()) return
            val map = LinkedHashMap<String, String>()
            var lastKey: String? = null
            for (line in currentLines) {
                if (line.startsWith(" ")) {
                    val prev = lastKey ?: throw SecurityException(
                        "Extension signature validation failed: invalid continuation line in manifest"
                    )
                    map[prev] = (map[prev] ?: "") + line.substring(1)
                } else {
                    val colon = line.indexOf(':')
                    if (colon <= 0) {
                        throw SecurityException(
                            "Extension signature validation failed: malformed manifest header line"
                        )
                    }
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trimStart()
                    if (key in map) {
                        throw SecurityException(
                            "Extension signature validation failed: duplicate manifest header '$key'"
                        )
                    }
                    map[key] = value
                    lastKey = key
                }
            }
            if (map.isNotEmpty()) blocks.add(map)
            currentLines = mutableListOf()
        }

        for (line in rawLines) {
            if (line.isEmpty()) {
                flushBlock()
            } else {
                currentLines.add(line)
            }
        }
        flushBlock()
        return blocks
    }

    private fun decodeBase64Strict(b64: String, label: String): ByteArray {
        return try {
            Base64.getDecoder().decode(b64)
        } catch (e: Exception) {
            throw SecurityException("Extension signature validation failed: invalid Base64 in $label", e)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
}
