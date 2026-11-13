package com.mvrk.vrka

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 7 verification gate for Mozilla AMO API v5 uBlock Origin updater:
 * - Mocked AMO v5 tests (200, 304, invalid hash, status != public, disallowed redirect host,
 *   downgrade 1.73.0, SPKI rotation pair vs unknown SPKI, strict_min_version > 159.0,
 *   GeckoView install failure rollback).
 */
class AmoUpdaterFaultReproductionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockAmoHttpConnection(
        url: URL,
        private val statusCode: Int,
        private val responseBody: String = "",
        private val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = statusCode
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        override fun getHeaderField(name: String?): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        override fun getHeaderFields(): Map<String, List<String>> = headers
    }

    @Test
    fun ublock_validSignedUpdateBeyond1740_mustNotFailWithUnapprovedVersionError() = runBlocking {
        val authority = TestXpiSigner.createTestAuthority()
        val xpiBytes = TestXpiSigner.buildSignedXpi(
            authority = authority,
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
            minGecko = "128.0",
        )
        val xpiFile = tempFolder.newFile("uBlock0_1.75.0.firefox.signed.xpi").apply {
            writeBytes(xpiBytes)
        }
        val expectedSha = XpiSignatureVerifier.sha256Hex(xpiFile)
        val targetDir = tempFolder.newFolder("amo_target")

        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            trustedExtensionCaSpkiSha256 = setOf(authority.caSpkiSha256),
        )

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/9999999/ublock_origin-1.75.0.xpi",
            expectedSha256 = expectedSha,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    xpiFile.copyTo(destination, overwrite = true)
                    return expectedSha
                }
            },
            customExtensionValidator = { _ -> "1.75.0" },
        )

        assertTrue(
            "Valid signed AMO release > 1.74.0 must not fail static allowlist check, got: ${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
    }

    @Test
    fun amoApiV5_http200_parsesStrictSchema() = runBlocking {
        val json = """
            {
              "current_version": {
                "version": "1.76.0",
                "file": {
                  "status": "public",
                  "platform": "all",
                  "size": 6341734,
                  "hash": "sha256:6f20ff6749193599548770539103069c20948dfcd3e61c3089a67da7bfc3be39",
                  "url": "https://addons.mozilla.org/firefox/downloads/file/5225273/ublock_origin-1.76.0.xpi"
                }
              }
            }
        """.trimIndent()

        val release = fetchLatestAmoRelease(
            addonSlug = "ublock-origin",
            connectionFactory = { url ->
                MockAmoHttpConnection(
                    url = url,
                    statusCode = 200,
                    responseBody = json,
                    headers = mapOf("ETag" to listOf("\"etag-12345\"")),
                )
            },
        )

        assertNotNull(release)
        assertEquals("1.76.0", release?.version)
        assertEquals("6f20ff6749193599548770539103069c20948dfcd3e61c3089a67da7bfc3be39", release?.expectedSha256)
        assertEquals("https://addons.mozilla.org/firefox/downloads/file/5225273/ublock_origin-1.76.0.xpi", release?.downloadUrl)
        assertEquals(6341734L, release?.size)
        assertEquals("\"etag-12345\"", release?.eTag)
    }

    @Test
    fun amoApiV5_http304_treatsAsCleanCachedResult() = runBlocking {
        val release = fetchLatestAmoRelease(
            addonSlug = "ublock-origin",
            ifNoneMatchETag = "\"etag-12345\"",
            connectionFactory = { url ->
                MockAmoHttpConnection(
                    url = url,
                    statusCode = 304,
                )
            },
        )
        assertNull(release)
    }

    @Test
    fun amoApiV5_invalidHashFormat_rejected() = runBlocking {
        val json = """
            {
              "current_version": {
                "version": "1.76.0",
                "file": {
                  "status": "public",
                  "platform": "all",
                  "size": 1000,
                  "hash": "md5:invalidhash12345",
                  "url": "https://addons.mozilla.org/firefox/downloads/file/1/u.xpi"
                }
              }
            }
        """.trimIndent()

        try {
            fetchLatestAmoRelease(
                addonSlug = "ublock-origin",
                connectionFactory = { url ->
                    MockAmoHttpConnection(url = url, statusCode = 200, responseBody = json)
                },
            )
            fail("Must reject invalid hash format")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Invalid AMO hash format"))
        }
    }

    @Test
    fun amoApiV5_statusNotPublic_rejected() = runBlocking {
        val json = """
            {
              "current_version": {
                "version": "1.76.0",
                "file": {
                  "status": "disabled",
                  "platform": "all",
                  "size": 1000,
                  "hash": "sha256:6f20ff6749193599548770539103069c20948dfcd3e61c3089a67da7bfc3be39",
                  "url": "https://addons.mozilla.org/firefox/downloads/file/1/u.xpi"
                }
              }
            }
        """.trimIndent()

        try {
            fetchLatestAmoRelease(
                addonSlug = "ublock-origin",
                connectionFactory = { url ->
                    MockAmoHttpConnection(url = url, statusCode = 200, responseBody = json)
                },
            )
            fail("Must reject non-public file status")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("expected 'public'"))
        }
    }

    @Test
    fun amoApiV5_disallowedRedirectHost_rejected() = runBlocking {
        try {
            fetchLatestAmoRelease(
                addonSlug = "ublock-origin",
                connectionFactory = { url ->
                    MockAmoHttpConnection(
                        url = url,
                        statusCode = 302,
                        headers = mapOf("Location" to listOf("https://unapproved-mirror.com/addon.json")),
                    )
                },
            )
            fail("Must reject redirect to unapproved host")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Unapproved redirect or request host 'unapproved-mirror.com' rejected"))
        }
    }

    @Test
    fun ublock_downgrade1730_belowBundledFloor_rejected() = runBlocking {
        val updater = SecureComponentUpdater(customTargetDir = tempFolder.newFolder("floor_test"))
        val res = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.73.0",
            installedVersion = "1.72.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin-1.73.0.xpi",
            expectedSha256 = "a".repeat(64),
        )
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("below bundled floor v1.74.0"))
    }

    @Test
    fun ublock_spkiRotationPairVsUnknownSpki_verifiesCorrectly() = runBlocking {
        val authority1 = TestXpiSigner.createTestAuthority(caSubject = "CN=AMO Primary CA, O=Mozilla, C=US")
        val authority2 = TestXpiSigner.createTestAuthority(caSubject = "CN=AMO Rollover Secondary CA, O=Mozilla, C=US")
        val rogueAuthority = TestXpiSigner.createTestAuthority(caSubject = "CN=Untrusted Rogue CA, O=Attacker, C=RU")

        val targetDir = tempFolder.newFolder("spki_pair_test")
        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            trustedExtensionCaSpkiSha256 = setOf(authority1.caSpkiSha256, authority2.caSpkiSha256),
        )

        // Auth 1 (primary in pair) -> Success
        val xpi1 = TestXpiSigner.buildSignedXpi(authority1, SecureComponentUpdater.UBLOCK_EXTENSION_ID, "1.75.0", "128.0")
        val xpiFile1 = tempFolder.newFile("ublock_1.75.0.xpi").apply { writeBytes(xpi1) }
        val sha1 = XpiSignatureVerifier.sha256Hex(xpiFile1)
        val res1 = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin-1.75.0.xpi",
            expectedSha256 = sha1,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    xpiFile1.copyTo(destination, overwrite = true)
                    return sha1
                }
            },
            customExtensionValidator = { "1.75.0" },
        )
        assertTrue("XPI signed by primary CA in pair must succeed: ${res1.exceptionOrNull()}", res1.isSuccess)

        // Auth 2 (rollover in pair) -> Success
        val xpi2 = TestXpiSigner.buildSignedXpi(authority2, SecureComponentUpdater.UBLOCK_EXTENSION_ID, "1.76.0", "128.0")
        val xpiFile2 = tempFolder.newFile("ublock_1.76.0.xpi").apply { writeBytes(xpi2) }
        val sha2 = XpiSignatureVerifier.sha256Hex(xpiFile2)
        val res2 = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.76.0",
            installedVersion = "1.75.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/2/ublock_origin-1.76.0.xpi",
            expectedSha256 = sha2,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    xpiFile2.copyTo(destination, overwrite = true)
                    return sha2
                }
            },
            customExtensionValidator = { "1.76.0" },
        )
        assertTrue("XPI signed by rollover CA in pair must succeed: ${res2.exceptionOrNull()}", res2.isSuccess)

        // Rogue Authority (not in pair) -> SecurityException (fail-closed)
        val xpiRogue = TestXpiSigner.buildSignedXpi(rogueAuthority, SecureComponentUpdater.UBLOCK_EXTENSION_ID, "1.77.0", "128.0")
        val xpiFileRogue = tempFolder.newFile("ublock_1.77.0.xpi").apply { writeBytes(xpiRogue) }
        val shaRogue = XpiSignatureVerifier.sha256Hex(xpiFileRogue)
        val resRogue = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.77.0",
            installedVersion = "1.76.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/3/ublock_origin-1.77.0.xpi",
            expectedSha256 = shaRogue,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    xpiFileRogue.copyTo(destination, overwrite = true)
                    return shaRogue
                }
            },
            customExtensionValidator = { "1.77.0" },
        )
        assertTrue(resRogue.isFailure)
        assertTrue(resRogue.exceptionOrNull() is SecurityException)
        assertTrue(resRogue.exceptionOrNull()!!.message!!.contains("Extension signature validation failed"))
    }

    @Test
    fun ublock_strictMinVersionBeyond159_rejected() = runBlocking {
        val authority = TestXpiSigner.createTestAuthority()
        val targetDir = tempFolder.newFolder("min_ver_test")
        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            trustedExtensionCaSpkiSha256 = setOf(authority.caSpkiSha256),
        )

        // Incompatible min version 160.0 on GeckoView 159.0
        val xpiIncompat = TestXpiSigner.buildSignedXpi(
            authority = authority,
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
            minGecko = "160.0",
        )
        val incompatFile = tempFolder.newFile("ublock_incompat.xpi").apply { writeBytes(xpiIncompat) }
        val incompatSha = XpiSignatureVerifier.sha256Hex(incompatFile)

        val resIncompat = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin-1.75.0.xpi",
            expectedSha256 = incompatSha,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    incompatFile.copyTo(destination, overwrite = true)
                    return incompatSha
                }
            },
            customExtensionValidator = { "1.75.0" },
        )
        assertTrue(resIncompat.isFailure)
        assertTrue(resIncompat.exceptionOrNull() is IllegalStateException)
        assertTrue(resIncompat.exceptionOrNull()!!.message!!.contains("requires GeckoView min version 160.0"))

        // Compatible min version 159.0 on GeckoView 159.0 succeeds
        val xpiCompat = TestXpiSigner.buildSignedXpi(
            authority = authority,
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
            minGecko = "159.0",
        )
        val compatFile = tempFolder.newFile("ublock_compat.xpi").apply { writeBytes(xpiCompat) }
        val compatSha = XpiSignatureVerifier.sha256Hex(compatFile)

        val resCompat = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin-1.75.0.xpi",
            expectedSha256 = compatSha,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    compatFile.copyTo(destination, overwrite = true)
                    return compatSha
                }
            },
            customExtensionValidator = { "1.75.0" },
        )
        assertTrue("Min version 159.0 on GeckoView 159.0 must succeed: ${resCompat.exceptionOrNull()}", resCompat.isSuccess)
    }

    @Test
    fun ublock_geckoViewInstallFailure_rollsBackToPreviousVersion() = runBlocking {
        val authority = TestXpiSigner.createTestAuthority()
        val targetDir = tempFolder.newFolder("rollback_test")
        val updater = SecureComponentUpdater(
            customTargetDir = targetDir,
            trustedExtensionCaSpkiSha256 = setOf(authority.caSpkiSha256),
        )

        // Seed original active XPI (1.74.0)
        val origBytes = TestXpiSigner.buildSignedXpi(authority, SecureComponentUpdater.UBLOCK_EXTENSION_ID, "1.74.0", "128.0")
        val targetXpi = File(targetDir, "ublock.xpi").apply { writeBytes(origBytes) }
        val origSha = XpiSignatureVerifier.sha256Hex(targetXpi)

        // Attempt update to 1.75.0 with GeckoView install rejection
        val newBytes = TestXpiSigner.buildSignedXpi(authority, SecureComponentUpdater.UBLOCK_EXTENSION_ID, "1.75.0", "128.0")
        val newFile = tempFolder.newFile("new_ublock.xpi").apply { writeBytes(newBytes) }
        val newSha = XpiSignatureVerifier.sha256Hex(newFile)

        val res = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin-1.75.0.xpi",
            expectedSha256 = newSha,
            transport = object : SecureComponentUpdater.HttpTransport {
                override fun fetchBytes(url: String, maxRedirects: Int): ByteArray = error("unused")
                override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
                    newFile.copyTo(destination, overwrite = true)
                    return newSha
                }
            },
            customExtensionValidator = { null }, // GeckoView install rejection
        )

        assertTrue(res.isFailure)
        assertTrue(targetXpi.exists())
        assertEquals("Active XPI must be rolled back to original content", origSha, XpiSignatureVerifier.sha256Hex(targetXpi))
        assertFalse(File(targetDir, "ublock.backup.tmp").exists())
        assertFalse(File(targetDir, "ublock.download.tmp").exists())
    }
}
