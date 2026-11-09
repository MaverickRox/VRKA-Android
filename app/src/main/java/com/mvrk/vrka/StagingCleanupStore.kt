package com.mvrk.vrka

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PendingStagingCleanup(
    val jobId: String,
    val attempts: Int,
    val firstSeenAt: Long,
    val updatedAt: Long,
)

/**
 * Durable retry list for per-job staging directories that could not be deleted after their
 * publication transaction became durable. The list is bounded and every startup retries it;
 * entries are removed only after the directory is confirmed gone.
 */
internal class StagingCleanupStore(
    private val target: File,
    private val ops: JobStoreFileOps = DefaultJobStoreFileOps,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun pending(): List<PendingStagingCleanup> = synchronized(lock) { read() }

    fun markPending(jobId: String) {
        if (jobId.isBlank()) return
        synchronized(lock) {
            val now = clock()
            val entries = read().toMutableList()
            val index = entries.indexOfFirst { it.jobId == jobId }
            if (index >= 0) {
                entries[index] = entries[index].copy(
                    attempts = entries[index].attempts + 1,
                    updatedAt = now,
                )
            } else {
                entries += PendingStagingCleanup(jobId, attempts = 1, firstSeenAt = now, updatedAt = now)
            }
            write(entries)
        }
    }

    fun clear(jobId: String) {
        synchronized(lock) {
            val entries = read()
            val remaining = entries.filterNot { it.jobId == jobId }
            if (remaining.size != entries.size) write(remaining)
        }
    }

    private fun read(): List<PendingStagingCleanup> {
        if (!target.isFile) return emptyList()
        val text = runCatching { ops.readText(target) }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text.trim().removePrefix("\uFEFF")) }.getOrNull()
            ?: return emptyList()
        if ((root.opt("schemaVersion") as? Number)?.toInt() != SCHEMA_VERSION) return emptyList()
        val array = root.optJSONArray("entries") ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val jobId = (obj.opt("jobId") as? String)?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(jobId)) continue
                val attempts = (obj.opt("attempts") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                val firstSeenAt = (obj.opt("firstSeenAt") as? Number)?.toLong()?.takeIf { it > 0L }
                    ?: clock()
                val updatedAt = (obj.opt("updatedAt") as? Number)?.toLong()?.takeIf { it > 0L }
                    ?: firstSeenAt
                add(PendingStagingCleanup(jobId, attempts, firstSeenAt, updatedAt))
            }
        }
    }

    private fun write(entries: List<PendingStagingCleanup>) {
        val bounded = if (entries.size > MAX_ENTRIES) {
            entries.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
        } else {
            entries
        }
        val array = JSONArray()
        bounded.forEach { entry ->
            array.put(
                JSONObject()
                    .put("jobId", entry.jobId)
                    .put("attempts", entry.attempts)
                    .put("firstSeenAt", entry.firstSeenAt)
                    .put("updatedAt", entry.updatedAt),
            )
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("entries", array)
            .toString()
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val parentOrRoot = parent ?: File(".")
        val temporary = ops.createTempFile(parentOrRoot, "staging_cleanup")
        try {
            ops.writeText(temporary, payload)
            if (!ops.atomicMove(temporary, target)) {
                ops.replaceMove(temporary, target)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 128
    }
}
