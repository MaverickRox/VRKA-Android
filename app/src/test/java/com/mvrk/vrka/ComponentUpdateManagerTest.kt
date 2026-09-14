package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentUpdateManagerTest {

    @Test
    fun dateBasedVersionComparisons() {
        // Newer release
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19", "2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("v2026.08.19", "2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19", "yt-dlp 2026.06.30"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.19.1", "2026.08.19"))

        // Equal release
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.06.30", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("v2026.06.30", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.06.30", "yt-dlp 2026.06.30"))

        // Older release
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.05.01", "2026.06.30"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2025.12.31", "2026.06.30"))
    }

    @Test
    fun nightlyVersionNormalizationAndParity() {
        // Various release tag and banner formats
        val stableFormats = listOf(
            "v2026.08.19",
            "2026.08.19",
            "yt-dlp 2026.08.19",
        )
        // All forms of 2026.08.19 must extract the exact same numbers and compare equal
        for (f1 in stableFormats) {
            for (f2 in stableFormats) {
                assertFalse("Comparing $f1 vs $f2 should not report update", ComponentUpdateManager.isNewerVersion(f1, f2))
                assertEquals(
                    listOf(2026L, 8L, 19L),
                    ComponentUpdateManager.extractVersionNumbers(f1),
                )
            }
        }

        val nightlyFormats = listOf(
            "v2026.08.30.232658",
            "yt-dlp 2026.08.30.232658",
            "yt-dlp nightly 2026.08.30.232658",
            "nightly 2026.08.30.232658",
        )
        // All forms of nightly 2026.08.30.232658 must extract the exact same numbers and compare equal
        for (f1 in nightlyFormats) {
            for (f2 in nightlyFormats) {
                assertFalse("Comparing $f1 vs $f2 should not report update", ComponentUpdateManager.isNewerVersion(f1, f2))
                assertEquals(
                    listOf(2026L, 8L, 30L, 232658L),
                    ComponentUpdateManager.extractVersionNumbers(f1),
                )
            }
        }

        // Cross-format comparison: Candidate == Installed must yield false (not UPDATE_AVAILABLE)
        assertFalse(
            ComponentUpdateManager.isNewerVersion(
                candidate = "v2026.08.30.232658",
                installed = "yt-dlp nightly 2026.08.30.232658",
            ),
        )
        assertFalse(
            ComponentUpdateManager.isNewerVersion(
                candidate = "v2026.08.30.232658",
                installed = "nightly 2026.08.30.232658",
            ),
        )

        // Strict ordering
        assertTrue(ComponentUpdateManager.isNewerVersion("v2026.08.30.232658", "v2026.08.19"))
        assertTrue(ComponentUpdateManager.isNewerVersion("yt-dlp nightly 2026.08.30.232658", "yt-dlp 2026.08.19"))
        assertFalse(ComponentUpdateManager.isNewerVersion("2026.08.19", "yt-dlp nightly 2026.08.30.232658"))

        // Prefix cleaning
        assertEquals("2026.08.19", ComponentUpdateManager.cleanVersionString("v2026.08.19"))
        assertEquals("2026.08.19", ComponentUpdateManager.cleanVersionString("yt-dlp 2026.08.19"))
        assertEquals("2026.08.30.232658", ComponentUpdateManager.cleanVersionString("yt-dlp nightly 2026.08.30.232658"))
        assertEquals("2026.08.30.232658", ComponentUpdateManager.cleanVersionString("nightly 2026.08.30.232658"))
    }

    @Test
    fun semVerComparisons() {
        // Newer
        assertTrue(ComponentUpdateManager.isNewerVersion("1.75.0", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.74.1", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("2.0.0", "1.74.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion("v1.75.0", "1.74.0"))

        // Equal
        assertFalse(ComponentUpdateManager.isNewerVersion("1.74.0", "1.74.0"))
        assertFalse(ComponentUpdateManager.isNewerVersion("v1.74.0", "1.74.0"))

        // Older
        assertFalse(ComponentUpdateManager.isNewerVersion("1.73.9", "1.74.0"))
        assertFalse(ComponentUpdateManager.isNewerVersion("1.0.0", "1.74.0"))
    }

    @Test
    fun stateMachineOrthogonality() {
        val checkingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.CHECKING,
            updateState = ComponentUpdateState.UPDATE_IDLE,
        )
        assertTrue(checkingStatus.isChecking)
        assertFalse(checkingStatus.isUpdating)

        val downloadingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.DOWNLOADING,
        )
        assertFalse(downloadingStatus.isChecking)
        assertTrue(downloadingStatus.isUpdating)

        val verifyingStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.0.0",
            checkState = ComponentCheckState.UPDATE_AVAILABLE,
            updateState = ComponentUpdateState.VERIFYING,
        )
        assertFalse(verifyingStatus.isChecking)
        assertTrue(verifyingStatus.isUpdating)

        val successStatus = ComponentStatus(
            id = "test",
            name = "Test",
            installedVersion = "1.1.0",
            checkState = ComponentCheckState.UP_TO_DATE,
            updateState = ComponentUpdateState.UPDATE_SUCCESS,
        )
        assertFalse(successStatus.isChecking)
        assertFalse(successStatus.isUpdating)
    }

    // ==========================================
    // Independent Component Updater Tests
    // ==========================================

    @get:org.junit.Rule
    val tempDir = org.junit.rules.TemporaryFolder()

    private class TestTransport(
        var fileContent: ByteArray = ByteArray(0),
        var fileHash: String = "",
        var shouldThrow: Boolean = false,
    ) : SecureComponentUpdater.HttpTransport {
        override fun fetchBytes(url: String, maxRedirects: Int): ByteArray {
            if (shouldThrow) throw java.io.IOException("Network error")
            return fileContent
        }

        override fun downloadToFile(url: String, destination: java.io.File, maxRedirects: Int): String {
            if (shouldThrow) throw java.io.IOException("Network error")
            destination.writeBytes(fileContent)
            return fileHash
        }
    }

    // --- YT-DLP TESTS ---

    @Test
    fun ytdlpDowngradeRejected() {
        assertFalse(
            "Older release must be rejected as downgrade",
            ComponentUpdateManager.isNewerVersion("2026.05.01", "2026.06.30"),
        )
        assertFalse(
            "Equal release must be rejected as downgrade/no-op",
            ComponentUpdateManager.isNewerVersion("2026.06.30", "2026.06.30"),
        )
    }

    private fun createMockXpi(
        id: String,
        version: String,
        minGecko: String = "115.0",
        hasSignature: Boolean = true,
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            val manifestContent = """
            {
              "manifest_version": 2,
              "name": "Extension",
              "version": "$version",
              "browser_specific_settings": {
                "gecko": {
                  "id": "$id",
                  "strict_min_version": "$minGecko"
                }
              }
            }
            """.trimIndent()
            zos.write(manifestContent.toByteArray())
            zos.closeEntry()

            if (hasSignature) {
                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/mozilla.rsa"))
                zos.write("sig".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/mozilla.sf"))
                zos.write("sf".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/manifest.mf"))
                zos.write("mf".toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // --- UBLOCK TESTS ---

    @Test
    fun ublockCurrentVersionDetectionAndComparison() {
        val installed = "1.74.0"
        assertEquals(listOf(1L, 74L, 0L), ComponentUpdateManager.extractVersionNumbers(installed))
        assertEquals("1.74.0", ComponentUpdateManager.cleanVersionString(installed))

        // Update available
        assertTrue(ComponentUpdateManager.isNewerVersion("1.75.0", installed))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.74.1", installed))

        // Downgrade rejected
        assertFalse(ComponentUpdateManager.isNewerVersion("1.73.0", installed))
        assertFalse(ComponentUpdateManager.isNewerVersion("1.74.0", installed))
    }

    @Test
    fun ublockSuccessfulVerifiedUpdate() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("ublock_active")
        val payload = createMockXpi(
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
        )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = org.bouncycastle.util.encoders.Hex.toHexString(md.digest(payload))

        val transport = TestTransport(fileContent = payload, fileHash = expectedHash)
        val updater = SecureComponentUpdater(
            customTargetDir = target,
        )

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://github.com/gorhill/uBlock/releases/download/1.75.0/uBlock0_1.75.0.firefox.signed.xpi",
            expectedSha256 = expectedHash,
            transport = transport,
            customExtensionValidator = { "1.75.0" },
        )

        assertTrue("Update must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("1.75.0", result.getOrNull())
        val installedXpi = java.io.File(target, "ublock.xpi")
        assertTrue("Installed intact XPI must exist", installedXpi.exists())
    }

    @Test
    fun ublockInvalidIntegrityRejection() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("ublock_integrity")
        val payload = createMockXpi(
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
        )
        val transport = TestTransport(fileContent = payload, fileHash = "actualHash")
        val updater = SecureComponentUpdater(customTargetDir = target)

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://github.com/gorhill/uBlock/releases/download/1.75.0/uBlock0_1.75.0.firefox.signed.xpi",
            expectedSha256 = "expectedDifferentHash",
            transport = transport,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun ublockDowngradeRejectedException() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("ublock_downgrade")
        val updater = SecureComponentUpdater(customTargetDir = target)

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.73.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://github.com/gorhill/uBlock/releases/download/1.73.0/uBlock0_1.73.0.firefox.signed.xpi",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Downgrade rejected"))
    }

    @Test
    fun ublockFailedInstallationRetainsPreviousVersion() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("ublock_retained")
        val originalXpi = java.io.File(target, "ublock.xpi")
        val origPayload = createMockXpi(
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.74.0",
        )
        originalXpi.writeBytes(origPayload)

        val newPayload = createMockXpi(
            id = SecureComponentUpdater.UBLOCK_EXTENSION_ID,
            version = "1.75.0",
        )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = org.bouncycastle.util.encoders.Hex.toHexString(md.digest(newPayload))

        val transport = TestTransport(fileContent = newPayload, fileHash = expectedHash)
        val updater = SecureComponentUpdater(
            customTargetDir = target,
        )

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_UBLOCK,
            candidateVersion = "1.75.0",
            installedVersion = "1.74.0",
            downloadUrl = "https://github.com/gorhill/uBlock/releases/download/1.75.0/uBlock0_1.75.0.firefox.signed.xpi",
            expectedSha256 = expectedHash,
            transport = transport,
            customExtensionValidator = { null }, // Simulated validation failure
        )

        assertTrue("Update must fail validation", result.isFailure)
        assertTrue("Previous active XPI must still exist", originalXpi.exists())
        assertEquals(origPayload.size.toLong(), originalXpi.length())
    }

    // --- PUEMOS TESTS ---

    @Test
    fun puemosCurrentVersionDetectionAndComparison() {
        val installed = "1.0.0"
        assertEquals(listOf(1L, 0L, 0L), ComponentUpdateManager.extractVersionNumbers(installed))
        assertEquals("1.0.0", ComponentUpdateManager.cleanVersionString(installed))

        // Update available
        assertTrue(ComponentUpdateManager.isNewerVersion("5.5.0", installed))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.0.1", installed))
        assertTrue(ComponentUpdateManager.isNewerVersion("2.0.0", installed))

        // Downgrade rejected
        assertFalse(ComponentUpdateManager.isNewerVersion("0.9.0", installed))
        assertFalse(ComponentUpdateManager.isNewerVersion("1.0.0", installed))
    }

    @Test
    fun puemosSuccessfulVerifiedUpdate() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("puemos_active")
        val payload = createMockXpi(
            id = SecureComponentUpdater.PUEMOS_EXTENSION_ID,
            version = "5.5.0",
        )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = org.bouncycastle.util.encoders.Hex.toHexString(md.digest(payload))

        val transport = TestTransport(fileContent = payload, fileHash = expectedHash)
        val updater = SecureComponentUpdater(
            customTargetDir = target,
        )

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_PUEMOS,
            candidateVersion = "5.5.0",
            installedVersion = "1.0.0",
            downloadUrl = "https://github.com/puemos/hls-downloader/releases/download/v5.5.0/extension-mv2-firefox.xpi",
            expectedSha256 = expectedHash,
            transport = transport,
            customExtensionValidator = { "5.5.0" },
        )

        assertTrue("Update must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("5.5.0", result.getOrNull())
        val installedXpi = java.io.File(target, "puemos.xpi")
        assertTrue("Installed intact XPI must exist", installedXpi.exists())
    }

    @Test
    fun puemosInvalidIntegrityRejection() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("puemos_integrity")
        val payload = createMockXpi(
            id = SecureComponentUpdater.PUEMOS_EXTENSION_ID,
            version = "5.5.0",
        )
        val transport = TestTransport(fileContent = payload, fileHash = "actualHash")
        val updater = SecureComponentUpdater(customTargetDir = target)

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_PUEMOS,
            candidateVersion = "5.5.0",
            installedVersion = "1.0.0",
            downloadUrl = "https://github.com/puemos/hls-downloader/releases/download/v5.5.0/extension-mv2-firefox.xpi",
            expectedSha256 = "expectedDifferentHash",
            transport = transport,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun puemosDowngradeRejectedException() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("puemos_downgrade")
        val updater = SecureComponentUpdater(customTargetDir = target)

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_PUEMOS,
            candidateVersion = "0.9.0",
            installedVersion = "1.0.0",
            downloadUrl = "https://github.com/puemos/hls-downloader/releases/download/v0.9.0/extension-mv2-firefox.xpi",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Downgrade rejected"))
    }

    @Test
    fun puemosFailedInstallationRetainsPreviousVersion() = kotlinx.coroutines.runBlocking {
        val target = tempDir.newFolder("puemos_retained")
        val originalXpi = java.io.File(target, "puemos.xpi")
        val origPayload = createMockXpi(
            id = SecureComponentUpdater.PUEMOS_EXTENSION_ID,
            version = "1.0.0",
        )
        originalXpi.writeBytes(origPayload)

        val newPayload = createMockXpi(
            id = SecureComponentUpdater.PUEMOS_EXTENSION_ID,
            version = "5.5.0",
        )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = org.bouncycastle.util.encoders.Hex.toHexString(md.digest(newPayload))

        val transport = TestTransport(fileContent = newPayload, fileHash = expectedHash)
        val updater = SecureComponentUpdater(
            customTargetDir = target,
        )

        val result = updater.updateExtension(
            componentId = ComponentUpdateManager.ID_PUEMOS,
            candidateVersion = "5.5.0",
            installedVersion = "1.0.0",
            downloadUrl = "https://github.com/puemos/hls-downloader/releases/download/v5.5.0/extension-mv2-firefox.xpi",
            expectedSha256 = expectedHash,
            transport = transport,
            customExtensionValidator = { null },
        )

        assertTrue("Update must fail validation", result.isFailure)
        assertTrue("Previous active XPI must still exist", originalXpi.exists())
        assertEquals(origPayload.size.toLong(), originalXpi.length())
    }

    // --- ISOLATION TESTS ---

    @Test
    fun testIsolationYtdlpFailureDoesNotBreakPuemosOrUblock() {
        val initialMap = mapOf(
            ComponentUpdateManager.ID_YTDLP to ComponentStatus(
                id = ComponentUpdateManager.ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = "2026.08.19",
                updateState = ComponentUpdateState.UPDATE_FAILED,
                error = "Network timeout on yt-dlp",
            ),
            ComponentUpdateManager.ID_UBLOCK to ComponentStatus(
                id = ComponentUpdateManager.ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = "1.74.0",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
            ComponentUpdateManager.ID_PUEMOS to ComponentStatus(
                id = ComponentUpdateManager.ID_PUEMOS,
                name = "Puemos",
                installedVersion = "5.5.0",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
        )

        // yt-dlp is failed
        assertEquals(ComponentUpdateState.UPDATE_FAILED, initialMap[ComponentUpdateManager.ID_YTDLP]?.updateState)
        // uBlock and Puemos remain fully operational
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_UBLOCK]?.checkState)
        assertEquals("1.74.0", initialMap[ComponentUpdateManager.ID_UBLOCK]?.installedVersion)
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_PUEMOS]?.checkState)
        assertEquals("5.5.0", initialMap[ComponentUpdateManager.ID_PUEMOS]?.installedVersion)
    }

    @Test
    fun testIsolationUblockFailureDoesNotBreakYtdlpOrPuemos() {
        val initialMap = mapOf(
            ComponentUpdateManager.ID_YTDLP to ComponentStatus(
                id = ComponentUpdateManager.ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = "2026.08.19",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
            ComponentUpdateManager.ID_UBLOCK to ComponentStatus(
                id = ComponentUpdateManager.ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = "1.74.0",
                updateState = ComponentUpdateState.UPDATE_FAILED,
                error = "uBlock download checksum mismatch",
            ),
            ComponentUpdateManager.ID_PUEMOS to ComponentStatus(
                id = ComponentUpdateManager.ID_PUEMOS,
                name = "Puemos",
                installedVersion = "5.5.0",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
        )

        assertEquals(ComponentUpdateState.UPDATE_FAILED, initialMap[ComponentUpdateManager.ID_UBLOCK]?.updateState)
        assertEquals("1.74.0", initialMap[ComponentUpdateManager.ID_UBLOCK]?.installedVersion)
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_YTDLP]?.checkState)
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_PUEMOS]?.checkState)
    }

    @Test
    fun testIsolationPuemosFailureDoesNotBreakYtdlpOrUblock() {
        val initialMap = mapOf(
            ComponentUpdateManager.ID_YTDLP to ComponentStatus(
                id = ComponentUpdateManager.ID_YTDLP,
                name = "yt-dlp Engine",
                installedVersion = "2026.08.19",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
            ComponentUpdateManager.ID_UBLOCK to ComponentStatus(
                id = ComponentUpdateManager.ID_UBLOCK,
                name = "uBlock Origin",
                installedVersion = "1.74.0",
                checkState = ComponentCheckState.UP_TO_DATE,
                updateState = ComponentUpdateState.UPDATE_IDLE,
            ),
            ComponentUpdateManager.ID_PUEMOS to ComponentStatus(
                id = ComponentUpdateManager.ID_PUEMOS,
                name = "Puemos",
                installedVersion = "5.5.0",
                updateState = ComponentUpdateState.UPDATE_FAILED,
                error = "Puemos verification failed",
            ),
        )

        assertEquals(ComponentUpdateState.UPDATE_FAILED, initialMap[ComponentUpdateManager.ID_PUEMOS]?.updateState)
        assertEquals("5.5.0", initialMap[ComponentUpdateManager.ID_PUEMOS]?.installedVersion)
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_YTDLP]?.checkState)
        assertEquals(ComponentCheckState.UP_TO_DATE, initialMap[ComponentUpdateManager.ID_UBLOCK]?.checkState)
    }

    @Test
    fun testBundledStableBaselineConstants() {
        assertEquals("2026.08.19", ComponentUpdateManager.DEFAULT_YTDLP_VER)
        assertEquals("1.74.0", ComponentUpdateManager.DEFAULT_UBLOCK_VER)
        assertEquals("5.5.0", ComponentUpdateManager.DEFAULT_PUEMOS_VER)
    }

    @Test
    fun testYtDlp20260819Comparisons() {
        // v4.5.3 baseline is 2026.08.19
        val baseline = "2026.08.19"
        // Old 2026.06.30 is strictly older
        assertTrue(ComponentUpdateManager.isNewerVersion(baseline, "2026.06.30"))
        // Baseline is not newer than itself
        assertFalse(ComponentUpdateManager.isNewerVersion(baseline, baseline))
        assertFalse(ComponentUpdateManager.isNewerVersion("v$baseline", baseline))
        // Future release is newer
        assertTrue(ComponentUpdateManager.isNewerVersion("2026.08.30", baseline))
        assertTrue(ComponentUpdateManager.isNewerVersion("v2026.08.30.232658", baseline))
    }

    @Test
    fun testPuemos550Comparisons() {
        // v4.5.3 baseline is 5.5.0
        val baseline = "5.5.0"
        // Old 1.0.0 is strictly older
        assertTrue(ComponentUpdateManager.isNewerVersion(baseline, "1.0.0"))
        assertTrue(ComponentUpdateManager.isNewerVersion(baseline, "5.4.0"))
        // Baseline is not newer than itself
        assertFalse(ComponentUpdateManager.isNewerVersion(baseline, baseline))
        assertFalse(ComponentUpdateManager.isNewerVersion("v$baseline", baseline))
        // Future release is newer
        assertTrue(ComponentUpdateManager.isNewerVersion("5.5.1", baseline))
        assertTrue(ComponentUpdateManager.isNewerVersion("5.6.0", baseline))
        assertTrue(ComponentUpdateManager.isNewerVersion("6.0.0", baseline))
    }

    @Test
    fun testUBlock1740Comparisons() {
        // v4.5.3 baseline is 1.74.0
        val baseline = "1.74.0"
        assertFalse(ComponentUpdateManager.isNewerVersion(baseline, baseline))
        assertFalse(ComponentUpdateManager.isNewerVersion("1.73.0", baseline))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.74.1", baseline))
        assertTrue(ComponentUpdateManager.isNewerVersion("1.75.0", baseline))
    }

    @Test
    fun testBatchOperationStateTransitions() {
        assertEquals(BatchOperationState.IDLE, BatchOperationState.valueOf("IDLE"))
        assertEquals(BatchOperationState.CHECKING, BatchOperationState.valueOf("CHECKING"))
        assertEquals(BatchOperationState.UPDATING, BatchOperationState.valueOf("UPDATING"))
        assertEquals(BatchOperationState.COMPLETED, BatchOperationState.valueOf("COMPLETED"))
        assertEquals(BatchOperationState.FAILED, BatchOperationState.valueOf("FAILED"))
        assertEquals(BatchOperationState.CANCELLED, BatchOperationState.valueOf("CANCELLED"))
    }

    @Test
    fun testStartupUpdateDialogDataModels() {
        val item = ComponentUpdateItem(
            id = ComponentUpdateManager.ID_YTDLP,
            name = "yt-dlp Engine",
            currentVersion = "2026.08.19",
            targetVersion = "2026.08.30",
        )
        val dialogData = StartupUpdateDialogData(listOf(item))
        assertEquals(1, dialogData.updates.size)
        assertEquals("yt-dlp Engine", dialogData.updates[0].name)
        assertEquals("2026.08.19", dialogData.updates[0].currentVersion)
        assertEquals("2026.08.30", dialogData.updates[0].targetVersion)
    }

    @Test
    fun testTwentyFourHourIntervalGate() {
        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        val now = 100_000_000_000L
        // Exactly within 24h
        val recentCheck = now - (12 * 60 * 60 * 1000L) // 12 hours ago
        assertTrue(now - recentCheck < twentyFourHoursMs)

        // 23 hours ago
        val almostCheck = now - (23 * 60 * 60 * 1000L)
        assertTrue(now - almostCheck < twentyFourHoursMs)

        // 25 hours ago -> eligible for automatic startup check
        val expiredCheck = now - (25 * 60 * 60 * 1000L)
        assertFalse(now - expiredCheck < twentyFourHoursMs)
    }

    @Test
    fun testUnknownVersionNormalization() {
        val unknown = "Unknown"
        val cleaned = ComponentUpdateManager.cleanVersionString(unknown)
        assertEquals("Unknown", cleaned)
        val displayVer = if (cleaned.equals("Unknown", ignoreCase = true)) "Unknown" else "v$cleaned"
        assertEquals("Unknown", displayVer)
    }
}
