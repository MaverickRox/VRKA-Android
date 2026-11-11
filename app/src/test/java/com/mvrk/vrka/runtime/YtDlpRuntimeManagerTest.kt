package com.mvrk.vrka.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.SecureComponentUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YtDlpRuntimeManagerTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    private class FakeTransport(
        var releasesProvider: () -> String,
        var binaryProvider: (url: String) -> ByteArray,
    ) : SecureComponentUpdater.HttpTransport {
        override fun fetchBytes(url: String, maxRedirects: Int): ByteArray =
            releasesProvider().toByteArray(Charsets.UTF_8)

        override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
            val bytes = binaryProvider(url)
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            return sha256(bytes)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob())
        cleanupRuntimeFiles()
    }

    @After
    fun tearDown() {
        scope.cancel()
        cleanupRuntimeFiles()
    }

    private fun cleanupRuntimeFiles() {
        File(context.filesDir, "ytdlp_runtimes.json").delete()
        File(context.filesDir, "yt-dlp-runtimes").deleteRecursively()
        File(context.noBackupFilesDir, "youtubedl-android").deleteRecursively()
    }

    private fun releasesJson(
        tag: String,
        assetSize: Int,
        repo: String = "fork/yt-dlp",
    ): String {
        val asset = JSONObject()
            .put("name", "yt-dlp")
            .put("browser_download_url", "https://github.com/$repo/releases/download/$tag/yt-dlp")
            .put("size", assetSize)
        val release = JSONObject()
            .put("tag_name", tag)
            .put("draft", false)
            .put("prerelease", false)
            .put("published_at", "2026-11-11T00:00:00Z")
            .put("assets", JSONArray().put(asset))
        return JSONArray().put(release).toString()
    }

    @Test
    fun installVerifiedCustomRuntimePersistsChecksumAndActivatesIt() = runBlocking {
        val payload = "#!/usr/bin/env python3\n# yt-dlp test zipapp\n".toByteArray()
        val appliedBinaries = mutableListOf<File>()
        val transport = FakeTransport(
            releasesProvider = { releasesJson("2026.11.11", payload.size) },
            binaryProvider = { payload },
        )
        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = transport,
            versionProbe = { "2026.11.11" },
            binaryApplier = { file ->
                appliedBinaries += file
                Result.success(Unit)
            },
        )

        val result = manager.install("fork/yt-dlp")
        assertTrue(result.isSuccess)

        val runtime = manager.state.value.snapshot.runtimes.single()
        assertEquals("fork/yt-dlp", runtime.repo)
        assertEquals("2026.11.11", runtime.tag)
        assertEquals(sha256(payload), runtime.sha256)
        assertTrue(runtime.verifiedAt > 0L)
        assertTrue(manager.isCustomActive())
        assertEquals(manager.customRuntimePathFor(runtime.id), manager.activeBinary())
        assertEquals("Custom fork/yt-dlp 2026.11.11", manager.activeDescription())
        assertEquals(listOf(manager.customRuntimePathFor(runtime.id)), appliedBinaries)
    }

    @Test
    fun installRejectsFailedProbeAndSizeMismatchWithoutActivatingCustom() = runBlocking {
        val payload = "zipapp-payload-v1".toByteArray()

        val probeFailingTransport = FakeTransport(
            releasesProvider = { releasesJson("2026.11.11", payload.size) },
            binaryProvider = { payload },
        )
        val probeFailManager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = probeFailingTransport,
            versionProbe = { null },
            binaryApplier = { Result.success(Unit) },
        )
        val probeFailure = probeFailManager.install("fork/yt-dlp")
        assertTrue(probeFailure.isFailure)
        assertTrue(probeFailure.exceptionOrNull()?.message.orEmpty().contains("did not execute successfully"))
        assertFalse(probeFailManager.isCustomActive())
        assertTrue(probeFailManager.state.value.snapshot.runtimes.isEmpty())

        val sizeMismatchTransport = FakeTransport(
            releasesProvider = { releasesJson("2026.11.11", payload.size + 100) },
            binaryProvider = { payload },
        )
        val sizeFailManager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = sizeMismatchTransport,
            versionProbe = { "2026.11.11" },
            binaryApplier = { Result.success(Unit) },
        )
        val sizeFailure = sizeFailManager.install("fork/yt-dlp")
        assertTrue(sizeFailure.isFailure)
        assertTrue(sizeFailure.exceptionOrNull()?.message.orEmpty().contains("size does not match"))
        assertFalse(sizeFailManager.isCustomActive())
        assertTrue(sizeFailManager.state.value.snapshot.runtimes.isEmpty())
    }

    @Test
    fun tamperedOrMissingActiveCustomBinaryFailsClosedToStock() = runBlocking {
        val payload = "verified-custom-runtime".toByteArray()
        val appliedBinaries = mutableListOf<File>()
        val transport = FakeTransport(
            releasesProvider = { releasesJson("2026.11.11", payload.size) },
            binaryProvider = { payload },
        )
        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = transport,
            versionProbe = { "2026.11.11" },
            binaryApplier = { file ->
                appliedBinaries += file
                Result.success(Unit)
            },
        )
        assertTrue(manager.install("fork/yt-dlp").isSuccess)
        val runtime = manager.state.value.snapshot.runtimes.single()
        val binary = manager.customRuntimePathFor(runtime.id)
        assertEquals(binary, manager.activeBinary())

        // Corrupt on disk after install: activeBinary() refuses tampered bytes and applyActiveRuntime() reverts to stock.
        binary.writeBytes("tampered-payload".toByteArray())
        assertEquals(manager.stockBinary(), manager.activeBinary())

        val reloadedManager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = transport,
            versionProbe = { "2026.11.11" },
            binaryApplier = { file ->
                appliedBinaries += file
                Result.success(Unit)
            },
        )
        assertEquals(reloadedManager.stockBinary(), reloadedManager.activeBinary())
        assertTrue(reloadedManager.applyActiveRuntime().isSuccess)
        assertFalse(reloadedManager.isCustomActive())
        assertEquals(reloadedManager.stockBinary(), appliedBinaries.last())
    }

    @Test
    fun activateCustomRefusesUnverifiedOrTamperedRuntime() = runBlocking {
        val unverifiedPayload = "unverified-bytes".toByteArray()
        val storeFile = File(context.filesDir, "ytdlp_runtimes.json")
        val runtimeDir = File(context.filesDir, "yt-dlp-runtimes/fork-unverified")
        runtimeDir.mkdirs()
        File(runtimeDir, "yt-dlp").writeBytes(unverifiedPayload)
        YtDlpRuntimeStore(storeFile).save(
            RuntimeSnapshot(
                activeKind = RuntimeKind.STOCK,
                activeId = null,
                runtimes = listOf(
                    CustomRuntime(
                        id = "fork-unverified",
                        repo = "fork/unverified",
                        label = "fork/unverified 2026.11.11",
                        tag = "2026.11.11",
                        assetName = "yt-dlp",
                        sha256 = sha256(unverifiedPayload),
                        version = "2026.11.11",
                        installedAt = 100L,
                        verifiedAt = 0L,
                    ),
                ),
            ),
        )

        val unverifiedManager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = FakeTransport({ "[]" }, { ByteArray(0) }),
            versionProbe = { "2026.11.11" },
            binaryApplier = { Result.success(Unit) },
        )
        val unverifiedActivation = unverifiedManager.activateCustom("fork-unverified")
        assertTrue(unverifiedActivation.isFailure)
        assertFalse(unverifiedManager.isCustomActive())

        val validPayload = "valid-bytes".toByteArray()
        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = FakeTransport(
                releasesProvider = { releasesJson("2026.11.11", validPayload.size, repo = "fork/valid") },
                binaryProvider = { validPayload },
            ),
            versionProbe = { "2026.11.11" },
            binaryApplier = { Result.success(Unit) },
        )
        assertTrue(manager.install("fork/valid").isSuccess)
        val id = manager.state.value.snapshot.runtimes.first { it.repo == "fork/valid" }.id
        assertTrue(manager.activateStock().isSuccess)
        manager.customRuntimePathFor(id).writeBytes("modified-after-stock".toByteArray())

        val tamperedActivation = manager.activateCustom(id)
        assertTrue(tamperedActivation.isFailure)
        assertTrue(tamperedActivation.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
        assertFalse(manager.isCustomActive())
    }

    @Test
    fun removeRejectsActiveRuntimeUntilStockIsRestored() = runBlocking {
        val payload = "active-custom-runtime".toByteArray()
        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = FakeTransport(
                releasesProvider = { releasesJson("2026.11.11", payload.size) },
                binaryProvider = { payload },
            ),
            versionProbe = { "2026.11.11" },
            binaryApplier = { Result.success(Unit) },
        )
        assertTrue(manager.install("fork/yt-dlp").isSuccess)
        val id = manager.state.value.snapshot.runtimes.single().id

        val blockedRemove = manager.remove(id)
        assertTrue(blockedRemove.isFailure)
        assertTrue(manager.isCustomActive())

        assertTrue(manager.activateStock().isSuccess)
        assertFalse(manager.isCustomActive())

        assertTrue(manager.remove(id).isSuccess)
        assertFalse(manager.customRuntimePathFor(id).exists())
        assertTrue(manager.state.value.snapshot.runtimes.isEmpty())
    }

    @Test
    fun updateRejectsSameTagBinaryChangeAndPreservesExistingBinaryOnProbeFailure() = runBlocking {
        val v1Bytes = "runtime-v1".toByteArray()
        val v1Altered = "runtime-v1-retagged".toByteArray()
        val v2Bytes = "runtime-v2-valid".toByteArray()
        var currentTag = "2026.11.11"
        var currentBytes = v1Bytes
        var probeResult: String? = "2026.11.11"

        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = FakeTransport(
                releasesProvider = { releasesJson(currentTag, currentBytes.size) },
                binaryProvider = { currentBytes },
            ),
            versionProbe = { probeResult },
            binaryApplier = { Result.success(Unit) },
        )
        assertTrue(manager.install("fork/yt-dlp").isSuccess)
        val id = manager.state.value.snapshot.runtimes.single().id
        val binaryFile = manager.customRuntimePathFor(id)

        // Removing local binary forces update check for same tag, which rejects altered bytes for the same tag.
        assertTrue(binaryFile.delete())
        currentBytes = v1Altered
        val changedSameTag = manager.update(id)
        assertTrue(changedSameTag.isFailure)
        assertTrue(changedSameTag.exceptionOrNull()?.message.orEmpty().contains("changed since it was installed"))

        // Reinstall v1 cleanly, then fail probe on v2 and verify existing v1 file + record stay intact.
        binaryFile.writeBytes(v1Bytes)
        currentTag = "2026.11.12"
        currentBytes = v2Bytes
        probeResult = null
        val failedUpdate = manager.update(id)
        assertTrue(failedUpdate.isFailure)
        assertEquals("2026.11.11", manager.state.value.snapshot.runtimes.single().tag)
        assertEquals(sha256(v1Bytes), manager.state.value.snapshot.runtimes.single().sha256)
        assertArrayEquals(v1Bytes, binaryFile.readBytes())

        // Once v2 passes probe, update succeeds and replaces hash + tag.
        probeResult = "2026.11.12"
        val succeededUpdate = manager.update(id)
        assertTrue(succeededUpdate.isSuccess)
        assertEquals("2026.11.12", manager.state.value.snapshot.runtimes.single().tag)
        assertEquals(sha256(v2Bytes), manager.state.value.snapshot.runtimes.single().sha256)
        assertArrayEquals(v2Bytes, binaryFile.readBytes())
        assertFalse(manager.state.value.working)
    }

    @Test
    fun corruptedLocalRuntimeCanBeReinstalledWhenUpstreamHashMatchesAndWorkingResetsOnActivationFailure() = runBlocking {
        val originalBytes = "verified-custom-binary".toByteArray()
        var failApplier = false
        val transport = FakeTransport(
            releasesProvider = { releasesJson("2026.11.11", originalBytes.size) },
            binaryProvider = { originalBytes },
        )
        val manager = YtDlpRuntimeManager(
            context = context,
            scope = scope,
            transport = transport,
            versionProbe = { "2026.11.11" },
            binaryApplier = {
                if (failApplier) Result.failure(IllegalStateException("boom")) else Result.success(Unit)
            },
        )
        assertTrue(manager.install("fork/yt-dlp").isSuccess)
        val runtime = manager.state.value.snapshot.runtimes.single()
        val binary = manager.customRuntimePathFor(runtime.id)

        // Corrupt on disk, then reinstall the same verified upstream tag to repair it.
        binary.writeBytes("corrupted".toByteArray())
        val repair = manager.install("fork/yt-dlp")
        assertTrue(repair.isSuccess)
        assertArrayEquals(originalBytes, binary.readBytes())
        assertFalse(manager.state.value.working)

        // Failing activation resets working state back to false.
        assertTrue(binary.delete())
        failApplier = true
        val failedInstall = manager.install("fork/yt-dlp")
        assertTrue(failedInstall.isFailure)
        assertFalse(manager.state.value.working)
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
