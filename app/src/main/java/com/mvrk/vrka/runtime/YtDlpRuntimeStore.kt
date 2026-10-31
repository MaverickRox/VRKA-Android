package com.mvrk.vrka.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RuntimeSnapshot(
    val activeKind: RuntimeKind = RuntimeKind.STOCK,
    val activeId: String? = null,
    val runtimes: List<CustomRuntime> = emptyList(),
) {
    fun activeRuntime(): CustomRuntime? =
        if (activeKind == RuntimeKind.CUSTOM) runtimes.firstOrNull { it.id == activeId } else null
}

/**
 * Durable record of installed custom runtimes and the active selection.
 * The active binary itself lives outside this file; this only stores metadata.
 */
internal class YtDlpRuntimeStore(private val target: File) {

    private val idRegex = Regex("""^[A-Za-z0-9-]{1,64}$""")

    fun load(): RuntimeSnapshot {
        if (!target.isFile) return RuntimeSnapshot()
        return runCatching {
            val root = JSONObject(target.readText(Charsets.UTF_8))
            val active = root.optJSONObject("active")
            val activeKind = runCatching {
                RuntimeKind.valueOf(active?.optString("kind").orEmpty())
            }.getOrDefault(RuntimeKind.STOCK)
            val activeId = active?.optString("id")?.takeIf { it.isNotBlank() }
            val array = root.optJSONArray("runtimes") ?: JSONArray()
            val runtimes = buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index))?.let(::add)
                }
            }
            RuntimeSnapshot(
                activeKind = if (activeKind == RuntimeKind.CUSTOM && runtimes.none { it.id == activeId }) {
                    RuntimeKind.STOCK
                } else {
                    activeKind
                },
                activeId = activeId,
                runtimes = runtimes,
            )
        }.getOrDefault(RuntimeSnapshot())
    }

    @Synchronized
    fun save(snapshot: RuntimeSnapshot) {
        val array = JSONArray()
        snapshot.runtimes.forEach { array.put(encode(it)) }
        val root = JSONObject()
            .put("schema", 1)
            .put(
                "active",
                JSONObject()
                    .put("kind", snapshot.activeKind.name)
                    .put("id", snapshot.activeId ?: JSONObject.NULL),
            )
            .put("runtimes", array)
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val temporary = File.createTempFile("runtimes_", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun encode(runtime: CustomRuntime): JSONObject =
        JSONObject()
            .put("id", runtime.id)
            .put("repo", runtime.repo)
            .put("label", runtime.label)
            .put("tag", runtime.tag)
            .put("asset", runtime.assetName)
            .put("sha256", runtime.sha256)
            .put("version", runtime.version)
            .put("installed", runtime.installedAt)
            .put("verified", runtime.verifiedAt)

    private fun decode(value: JSONObject?): CustomRuntime? {
        value ?: return null
        return runCatching {
            val id = value.optString("id")
            val repo = value.optString("repo")
            if (!idRegex.matches(id)) return@runCatching null
            if (CustomRuntimeSource.normalizeRepo(repo) != repo) return@runCatching null
            CustomRuntime(
                id = id,
                repo = repo,
                label = value.optString("label", repo),
                tag = value.optString("tag"),
                assetName = value.optString("asset", CustomRuntimeSource.ASSET_NAME),
                sha256 = value.optString("sha256"),
                version = value.optString("version"),
                installedAt = value.optLong("installed", 0L),
                verifiedAt = value.optLong("verified", 0L),
            )
        }.getOrNull()
    }
}
