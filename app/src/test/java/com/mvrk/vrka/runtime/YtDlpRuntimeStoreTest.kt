package com.mvrk.vrka.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YtDlpRuntimeStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun runtime(id: String = "runtime-1", repo: String = "fork/yt-dlp") = CustomRuntime(
        id = id,
        repo = repo,
        label = "$repo 2026.09.01",
        tag = "2026.09.01",
        assetName = "yt-dlp",
        sha256 = "a".repeat(64),
        version = "2026.09.01",
        installedAt = 1L,
        verifiedAt = 2L,
    )

    @Test
    fun `round trips active custom runtime`() {
        val file = File(temporaryFolder.root, "runtimes.json")
        val store = YtDlpRuntimeStore(file)
        val snapshot = RuntimeSnapshot(
            activeKind = RuntimeKind.CUSTOM,
            activeId = "runtime-1",
            runtimes = listOf(runtime()),
        )
        store.save(snapshot)
        val loaded = store.load()
        assertEquals(RuntimeKind.CUSTOM, loaded.activeKind)
        assertEquals("runtime-1", loaded.activeId)
        assertEquals(snapshot.runtimes, loaded.runtimes)
        assertEquals("runtime-1", loaded.activeRuntime()?.id)
    }

    @Test
    fun `missing file loads stock selection`() {
        val store = YtDlpRuntimeStore(File(temporaryFolder.root, "missing.json"))
        val loaded = store.load()
        assertEquals(RuntimeKind.STOCK, loaded.activeKind)
        assertNull(loaded.activeId)
        assertTrue(loaded.runtimes.isEmpty())
    }

    @Test
    fun `unknown active id falls back to stock`() {
        val file = File(temporaryFolder.root, "runtimes.json")
        val store = YtDlpRuntimeStore(file)
        store.save(RuntimeSnapshot(RuntimeKind.CUSTOM, "does-not-exist", listOf(runtime())))
        val loaded = store.load()
        assertEquals(RuntimeKind.STOCK, loaded.activeKind)
        assertNull(loaded.activeRuntime())
    }

    @Test
    fun `unsafe ids and repos are discarded on load`() {
        val file = File(temporaryFolder.root, "runtimes.json")
        file.writeText(
            """
            {
              "schema": 1,
              "active": {"kind": "STOCK", "id": null},
              "runtimes": [
                {"id": "../escape", "repo": "fork/yt-dlp", "tag": "1"},
                {"id": "safe-id", "repo": "https://evil.example/fork/yt-dlp", "tag": "1"},
                {"id": "valid-id", "repo": "fork/yt-dlp", "tag": "2026.09.01", "asset": "yt-dlp", "sha256": "b", "version": "2026.09.01"}
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = YtDlpRuntimeStore(file).load()
        assertEquals(1, loaded.runtimes.size)
        assertEquals("valid-id", loaded.runtimes.single().id)
    }

    @Test
    fun `corrupt file loads stock selection`() {
        val file = File(temporaryFolder.root, "runtimes.json")
        file.writeText("not json", Charsets.UTF_8)
        val loaded = YtDlpRuntimeStore(file).load()
        assertEquals(RuntimeKind.STOCK, loaded.activeKind)
        assertTrue(loaded.runtimes.isEmpty())
    }
}
