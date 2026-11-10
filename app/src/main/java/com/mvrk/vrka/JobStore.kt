package com.mvrk.vrka

import android.content.Context
import com.mvrk.vrka.storage.JobPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Result of reading the persisted queue/history file. */
internal enum class JobStoreLoadState {
    /** No file exists yet; this is the normal first-run state and is not corruption. */
    MISSING,

    /** The file parsed and every record was usable. */
    OK,

    /** The file exists but is empty; treated as an empty store, without a backup. */
    EMPTY,

    /** Some records were unreadable or malformed; valid records were kept. */
    RECOVERED,

    /** Nothing could be decoded; the original bytes are preserved in a recovery copy. */
    MALFORMED,

    /** The file declared a schema this build does not understand; it was preserved. */
    UNSUPPORTED_SCHEMA,

    /** The file could not be read at all; existence is unknown. */
    READ_FAILED,
}

internal data class JobStoreLoadResult(
    val jobs: List<DownloadJob>,
    val state: JobStoreLoadState,
    val skippedRecords: Int = 0,
    val recoveryCopy: File? = null,
    val detail: String = "",
)

internal data class JobStoreSaveResult(
    val success: Boolean,
    val error: String = "",
    val persistedJobIds: Set<String> = emptySet(),
)

/**
 * File operations behind [JobStore], separated so failure paths (temporary-file creation,
 * interrupted writes, atomic-move rejection, full or read-only storage) are testable.
 */
internal interface JobStoreFileOps {
    fun readText(file: File): String
    fun createTempFile(parent: File, prefix: String): File
    fun writeText(file: File, text: String)
    fun atomicMove(source: File, target: File): Boolean
    fun replaceMove(source: File, target: File)
}

internal object DefaultJobStoreFileOps : JobStoreFileOps {
    override fun readText(file: File): String = file.readText(Charsets.UTF_8)

    override fun createTempFile(parent: File, prefix: String): File =
        File.createTempFile(prefix, ".tmp", parent)

    override fun writeText(file: File, text: String) {
        file.writeText(text, Charsets.UTF_8)
    }

    override fun atomicMove(source: File, target: File): Boolean = runCatching {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        true
    }.getOrDefault(false)

    override fun replaceMove(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

/**
 * Persists queue and history in an app-private JSON file.
 *
 * The store never rewrites or empties the file during [loadWithStatus]. A malformed or
 * partially unreadable file is first copied to a bounded recovery file, valid records are
 * salvaged, and the caller receives a [JobStoreLoadResult] describing what happened. Saving
 * uses a temporary file followed by an atomic move and returns [JobStoreSaveResult] instead
 * of silently swallowing failures.
 */
internal class JobStore(
    private val target: File,
    private val ops: JobStoreFileOps = DefaultJobStoreFileOps,
) : JobPersistence {
    constructor(context: Context) : this(File(context.filesDir, "download_jobs.json"))

    @Volatile
    override var lastLoadResult: JobStoreLoadResult? = null
        private set

    /**
     * [recoverActive] keeps in-flight states so a user-initiated transfer worker can resume
     * them. Without it, in-flight records are downgraded to FAILED as before.
     */
    override fun load(recoverActive: Boolean): List<DownloadJob> =
        loadWithStatus(recoverActive).jobs

    fun load(): List<DownloadJob> = load(false)

    fun loadWithStatus(recoverActive: Boolean = false): JobStoreLoadResult {
        val result = read(recoverActive)
        lastLoadResult = result
        return result
    }

    private fun read(recoverActive: Boolean): JobStoreLoadResult {
        if (!target.isFile) {
            return JobStoreLoadResult(emptyList(), JobStoreLoadState.MISSING)
        }
        val text = try {
            ops.readText(target)
        } catch (error: Throwable) {
            return JobStoreLoadResult(
                emptyList(),
                JobStoreLoadState.READ_FAILED,
                detail = error.message.orEmpty().take(MAX_DETAIL_CHARS),
            )
        }
        if (text.isBlank()) {
            return JobStoreLoadResult(emptyList(), JobStoreLoadState.EMPTY)
        }

        val trimmed = text.trim().removePrefix("\uFEFF")
        val objectRoot = runCatching { JSONObject(trimmed) }.getOrNull()
        if (objectRoot != null) {
            val versionRaw = objectRoot.opt("schemaVersion")
            val version = when (versionRaw) {
                null, JSONObject.NULL -> LEGACY_SCHEMA_VERSION
                is Number -> versionRaw.toInt()
                else -> return preserveAndReport(
                    text,
                    JobStoreLoadResult(
                        emptyList(),
                        JobStoreLoadState.UNSUPPORTED_SCHEMA,
                        detail = "schemaVersion is not a number",
                    ),
                )
            }
            if (version < LEGACY_SCHEMA_VERSION || version > CURRENT_SCHEMA_VERSION) {
                return preserveAndReport(
                    text,
                    JobStoreLoadResult(
                        emptyList(),
                        JobStoreLoadState.UNSUPPORTED_SCHEMA,
                        detail = "unsupported schema version $version",
                    ),
                )
            }
            val jobsArray = objectRoot.optJSONArray("jobs")
                ?: return preserveAndReport(
                    text,
                    JobStoreLoadResult(
                        emptyList(),
                        JobStoreLoadState.MALFORMED,
                        detail = "schema object has no jobs array",
                    ),
                )
            return finishDecoded(text, decodeArray(jobsArray, recoverActive), "schema $version")
        }

        val array = runCatching { JSONArray(trimmed) }.getOrNull()
        if (array != null) {
            return finishDecoded(text, decodeArray(array, recoverActive), "legacy array")
        }

        // Truncated or otherwise invalid JSON: salvage complete record objects without
        // touching the original file. This keeps valid neighbours when one record is torn.
        val decoded = decodeObjects(salvageObjects(trimmed), recoverActive)
        return preserveAndReport(
            text,
            JobStoreLoadResult(
                jobs = decoded.jobs,
                state = if (decoded.jobs.isEmpty()) {
                    JobStoreLoadState.MALFORMED
                } else {
                    JobStoreLoadState.RECOVERED
                },
                skippedRecords = decoded.skipped,
                detail = if (decoded.jobs.isEmpty()) "unparseable JSON" else "salvaged ${decoded.jobs.size} record(s)",
            ),
        )
    }

    private data class Decoded(val jobs: List<DownloadJob>, val skipped: Int)

    private fun decodeArray(array: JSONArray, recoverActive: Boolean): Decoded {
        val objects = ArrayList<JSONObject>(array.length())
        var skipped = 0
        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index)
            if (value == null) skipped++ else objects += value
        }
        val decoded = decodeObjects(objects, recoverActive)
        return Decoded(decoded.jobs, decoded.skipped + skipped)
    }

    private fun decodeObjects(objects: List<JSONObject>, recoverActive: Boolean): Decoded {
        val byId = LinkedHashMap<String, DownloadJob>()
        var skipped = 0
        for (value in objects) {
            val job = decode(value, recoverActive)
            if (job == null) {
                skipped++
                continue
            }
            if (byId.putIfAbsent(job.id, job) != null) {
                // Duplicate identity: keep the first occurrence and report the anomaly.
                skipped++
            }
        }
        return Decoded(byId.values.toList(), skipped)
    }

    private fun finishDecoded(
        originalText: String,
        decoded: Decoded,
        label: String,
    ): JobStoreLoadResult {
        if (decoded.skipped == 0) {
            return JobStoreLoadResult(decoded.jobs, JobStoreLoadState.OK)
        }
        return preserveAndReport(
            originalText,
            JobStoreLoadResult(
                jobs = decoded.jobs,
                state = if (decoded.jobs.isEmpty()) {
                    JobStoreLoadState.MALFORMED
                } else {
                    JobStoreLoadState.RECOVERED
                },
                skippedRecords = decoded.skipped,
                detail = "$label: skipped ${decoded.skipped} invalid record(s)",
            ),
        )
    }

    /**
     * Copies the original file before any future save can replace it. Recovery copies are
     * bounded so repeated failed starts cannot fill the app's private storage.
     */
    private fun preserveAndReport(
        originalText: String,
        result: JobStoreLoadResult,
    ): JobStoreLoadResult {
        val copy = runCatching { writeRecoveryCopy(originalText) }.getOrNull()
        return result.copy(recoveryCopy = copy)
    }

    private fun writeRecoveryCopy(originalText: String): File? {
        val parent = target.parentFile ?: return null
        if (!parent.isDirectory) return null
        pruneRecoveryCopies(parent)
        val base = target.name.substringBeforeLast('.', target.name)
        var stamp = System.currentTimeMillis()
        var copy = File(parent, "$base.corrupt-$stamp.json")
        while (copy.exists()) {
            stamp++
            copy = File(parent, "$base.corrupt-$stamp.json")
        }
        return runCatching {
            ops.writeText(copy, originalText)
            copy
        }.getOrNull()
    }

    private fun pruneRecoveryCopies(parent: File) {
        val copies = recoveryCopies(parent)
        while (copies.size >= MAX_RECOVERY_COPIES) {
            copies.removeAt(0).delete()
        }
    }

    private fun recoveryCopies(parent: File): MutableList<File> {
        val base = target.name.substringBeforeLast('.', target.name)
        return (parent.listFiles { file -> file.isFile && file.name.startsWith("$base.corrupt-") } ?: emptyArray())
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .toMutableList()
    }

    private fun salvageObjects(text: String): List<JSONObject> {
        val objects = mutableListOf<JSONObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            runCatching { JSONObject(text.substring(start, index + 1)) }
                                .getOrNull()
                                ?.let(objects::add)
                            start = -1
                        }
                    }
                }
            }
            if (objects.size >= MAX_RECOVERY_RECORDS) break
        }
        return objects
    }

    /**
     * Persists [jobs] with an explicit retention policy and verifies the durable result.
     *
     * Retention never discards nonterminal work or a job named in [pinnedJobIds] (jobs with
     * unresolved publication journal transactions, including cleanup-pending entries). Only
     * terminal history beyond the protected set is bounded to [MAX_JOBS] records, newest
     * first by [DownloadJob.updatedAt].
     *
     * The payload is written to a temporary file, atomically moved, and then read back from
     * disk. Success is reported only when every retained record is present in the durable
     * file with the exact state and output URI list that was requested. A save that cannot
     * confirm its own durable result fails closed so callers retain their recovery data.
     */
    @Synchronized
    override fun save(jobs: List<DownloadJob>, pinnedJobIds: Set<String>): JobStoreSaveResult {
        var temporary: File? = null
        return try {
            if (jobs.mapTo(HashSet()) { it.id }.size != jobs.size) {
                return JobStoreSaveResult(false, "Duplicate job identities cannot be persisted")
            }
            val retained = applyRetention(jobs, pinnedJobIds)
            val array = JSONArray()
            retained.forEach { array.put(encode(it)) }
            val payload = JSONObject()
                .put("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("jobs", array)
                .toString()
            val parent = target.parentFile
                ?: return JobStoreSaveResult(false, "Store has no parent directory")
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return JobStoreSaveResult(false, "Could not create the store directory")
            }
            temporary = ops.createTempFile(parent, "jobs_")
            ops.writeText(temporary, payload)
            if (!ops.atomicMove(temporary, target)) {
                ops.replaceMove(temporary, target)
                if (!target.isFile) {
                    return JobStoreSaveResult(false, "Atomic and fallback moves did not produce the store file")
                }
            }
            verifyPersisted(retained)
        } catch (error: Throwable) {
            JobStoreSaveResult(false, error.message.orEmpty().take(MAX_DETAIL_CHARS))
        } finally {
            if (temporary != null && temporary.exists()) {
                temporary.delete()
            }
        }
    }

    fun save(jobs: List<DownloadJob>): JobStoreSaveResult = save(jobs, emptySet())

    private fun applyRetention(jobs: List<DownloadJob>, pinnedJobIds: Set<String>): List<DownloadJob> {
        if (jobs.size <= MAX_JOBS) return jobs
        val protected = jobs.filter { !it.state.isTerminal || it.id in pinnedJobIds }
        val protectedIds = protected.mapTo(HashSet()) { it.id }
        // Protected work is never truncated: it is bounded by active downloads plus the
        // journal's own entry cap, while history alone is what grows without limit.
        val historyBudget = (MAX_JOBS - protected.size).coerceAtLeast(0)
        val retainedHistory = jobs.asSequence()
            .filter { it.state.isTerminal && it.id !in protectedIds }
            .sortedWith(
                compareByDescending<DownloadJob> { it.updatedAt }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id },
            )
            .take(historyBudget)
            .mapTo(HashSet()) { it.id }
        return jobs.filter { it.id in protectedIds || it.id in retainedHistory }
    }

    /**
     * Re-reads the file that was just replaced. A concurrent writer or a filesystem that
     * silently discarded the move is detected here instead of being reported as success.
     */
    private fun verifyPersisted(expected: List<DownloadJob>): JobStoreSaveResult {
        val durable = read(recoverActive = true)
        if (durable.state == JobStoreLoadState.READ_FAILED ||
            durable.state == JobStoreLoadState.MALFORMED ||
            durable.state == JobStoreLoadState.UNSUPPORTED_SCHEMA
        ) {
            return JobStoreSaveResult(false, "Persisted file could not be read back: ${durable.detail}")
        }
        val durableById = durable.jobs.associateBy { it.id }
        if (durableById.keys != expected.mapTo(HashSet()) { it.id }) {
            return JobStoreSaveResult(false, "Persisted snapshot does not contain the exact retained job set")
        }
        for (job in expected) {
            val persisted = durableById[job.id]
            if (persisted == null ||
                persisted.state != job.state ||
                persisted.outputUris != job.outputUris
            ) {
                return JobStoreSaveResult(
                    false,
                    "Persisted snapshot lost the durable state for job ${job.id}",
                )
            }
        }
        return JobStoreSaveResult(true, persistedJobIds = durableById.keys)
    }

    override fun deleteJobs(jobIds: Collection<String>): Boolean {
        if (jobIds.isEmpty()) return true
        val current = load(recoverActive = true)
        val remaining = current.filterNot { it.id in jobIds }
        if (remaining.size == current.size) return true
        return save(remaining, emptySet()).success
    }

    override fun clearTerminal(): Boolean {
        val current = load(recoverActive = true)
        val remaining = current.filterNot { it.state.isTerminal }
        return save(remaining, emptySet()).success
    }

    override fun clearAll(): Boolean = save(emptyList(), emptySet()).success

    private fun encode(job: DownloadJob): JSONObject {
        val request = job.request
        return JSONObject()
            .put("id", job.id)
            .put("state", job.state.name)
            .put("title", job.title)
            .put("detail", job.detail)
            .put("progress", job.progress.toDouble())
            .put("speed", job.speed)
            .put("eta", job.etaSeconds)
            .put("downloadedBytes", job.downloadedBytes)
            .put("estimatedTotalBytes", job.estimatedTotalBytes)
            .put("estimatedSizeAccuracy", job.estimatedSizeAccuracy.name)
            .put("reportedTotalBytes", job.reportedTotalBytes)
            .put("reportedTotalIsExact", job.reportedTotalIsExact)
            .put("activeStream", job.activeStream?.name)
            .put("playlistItemIndex", job.playlistItemIndex)
            .put("playlistItemCount", job.playlistItemCount)
            .put("actualFinalBytes", job.actualFinalFileBytes)
            .put("created", job.createdAt)
            .put("updated", job.updatedAt)
            .put("outputs", JSONArray(job.outputUris))
            .put("error", job.error)
            .put("attempt", job.attempt)
            .put("actualResolution", job.actualResolution)
            .put("runtimeName", job.runtimeName)
            .put("runtimeVersion", job.runtimeVersion)
            .put("subtitleStatus", job.subtitleStatus.name)
            .put("publishStarted", job.publishStarted)
            .put(
                "request",
                JSONObject()
                    .put("url", request.url)
                    .put("mode", request.mode.name)
                    .put("quality", request.quality.name)
                    .put("prefer60Fps", request.prefer60Fps)
                    .put("audioFormat", request.audioFormat.name)
                    .put("bitrate", request.mp3Bitrate)
                    .put("playlist", request.isPlaylist)
                    .put("playlistStart", request.playlistStart)
                    .put("playlistEnd", request.playlistEnd)
                    .put("subtitles", request.downloadSubtitles)
                    .put("automaticCaptions", request.automaticCaptions)
                    .put("embedSubtitles", request.embedSubtitles)
                    .put("subtitleLanguages", request.subtitleLanguages)
                    .put("metadata", request.embedMetadata)
                    .put("thumbnail", request.embedThumbnail)
                    .put("sponsorBlock", request.sponsorBlock)
                    .put("sponsorCategories", request.sponsorCategories)
                    .put("trimStart", request.trimStart)
                    .put("trimEnd", request.trimEnd)
                    .put("customArguments", JSONArray(request.customArguments))
                    .put("commandTemplateId", request.commandTemplateId)
                    .put("commandTemplateName", request.commandTemplateName)
                    .put("commandTemplateArguments", JSONArray(request.commandTemplateArguments))
                    .put("h264CompatibilityMode", request.h264CompatibilityMode)
                    .put("referer", request.referer)
                    .put("origin", request.origin)
                    .put(
                        "customHeaders",
                        JSONObject(request.customHeaders.filterKeys { !HeaderValidation.isSensitiveHeader(it) }),
                    )
                    // Browser handoff URLs and headers stay in memory; they may contain credentials.
                    .put("destinationTreeUri", request.destinationTreeUri),
            )
    }

    private fun decode(value: JSONObject, recoverActive: Boolean): DownloadJob? = runCatching {
        val encodedRequest = value.getJSONObject("request")
        val savedState = optionalEnum(value.optString("state"), JobState.FAILED, "state")
        val keepSavedState = savedState.isTerminal || recoverActive
        val restoredState = if (keepSavedState) savedState else JobState.FAILED
        val restoredError = if (keepSavedState) {
            value.optString("error")
        } else {
            INTERRUPTED_DOWNLOAD_ERROR
        }

        val storedAudioFormat = encodedRequest.optString("audioFormat")
        val restoredAudioFormat = if (storedAudioFormat == "FLAC") {
            AudioFormat.OPUS
        } else {
            optionalEnum(storedAudioFormat, AudioFormat.MP3, "audioFormat")
        }

        val customHeadersObj = encodedRequest.optJSONObject("customHeaders")
        val customHeadersMap = if (customHeadersObj != null) {
            buildMap {
                for (key in customHeadersObj.keys()) {
                    if (!HeaderValidation.isSensitiveHeader(key)) {
                        put(key, customHeadersObj.optString(key))
                    }
                }
            }
        } else {
            emptyMap()
        }

        DownloadJob(
            id = value.getString("id"),
            request = DownloadRequest(
                url = encodedRequest.getString("url"),
                mode = optionalEnum(encodedRequest.optString("mode"), MediaMode.VIDEO, "mode"),
                quality = optionalEnum(
                    encodedRequest.optString("quality"),
                    VideoQuality.BEST,
                    "quality",
                ),
                prefer60Fps = encodedRequest.optBoolean("prefer60Fps"),
                audioFormat = restoredAudioFormat,
                mp3Bitrate = encodedRequest.optInt("bitrate", 320),
                isPlaylist = encodedRequest.optBoolean("playlist"),
                playlistStart = encodedRequest.optionalInt("playlistStart"),
                playlistEnd = encodedRequest.optionalInt("playlistEnd"),
                downloadSubtitles = encodedRequest.optBoolean("subtitles"),
                automaticCaptions = encodedRequest.optBoolean("automaticCaptions", true),
                embedSubtitles = encodedRequest.optBoolean("embedSubtitles", true),
                subtitleLanguages = encodedRequest.optString("subtitleLanguages", "en.*"),
                embedMetadata = encodedRequest.optBoolean("metadata", true),
                embedThumbnail = encodedRequest.optBoolean("thumbnail", true),
                sponsorBlock = encodedRequest.optBoolean("sponsorBlock"),
                sponsorCategories = encodedRequest.optString(
                    "sponsorCategories",
                    "sponsor,selfpromo,interaction",
                ),
                trimStart = encodedRequest.optString("trimStart"),
                trimEnd = encodedRequest.optString("trimEnd"),
                customArguments = encodedRequest.optJSONArray("customArguments")
                    ?.strings()
                    .orEmpty(),
                commandTemplateId = encodedRequest.optString("commandTemplateId")
                    .takeIf { it.isNotBlank() && it != "null" },
                commandTemplateName = encodedRequest.optString("commandTemplateName"),
                commandTemplateArguments = encodedRequest.optJSONArray("commandTemplateArguments")
                    ?.strings()
                    .orEmpty(),
                h264CompatibilityMode = encodedRequest.optBoolean("h264CompatibilityMode", false),
                referer = encodedRequest.optString("referer"),
                origin = encodedRequest.optString("origin"),
                customHeaders = customHeadersMap,
                resolvedHeadersOrigin = "",
                destinationTreeUri = if (encodedRequest.isNull("destinationTreeUri")) null else encodedRequest.optString("destinationTreeUri").takeIf { it.isNotBlank() },
            ),
            state = restoredState,
            title = value.optString("title"),
            detail = if (keepSavedState) {
                value.optString("detail")
            } else {
                "Interrupted"
            },
            progress = value.optDouble("progress", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
            speed = value.optString("speed"),
            etaSeconds = value.optionalLong("eta"),
            downloadedBytes = value.optionalLong("downloadedBytes"),
            estimatedTotalBytes = value.optionalLong("estimatedTotalBytes"),
            estimatedSizeAccuracy = optionalEnum(
                value.optString("estimatedSizeAccuracy"),
                SizeEstimateKind.UNAVAILABLE,
                "estimatedSizeAccuracy",
            ),
            // Records written before the estimated/reported split stored the transfer total
            // under "totalBytes"; it belongs to the reported field, never the estimate.
            reportedTotalBytes = value.optionalLong("reportedTotalBytes")
                ?: value.optionalLong("totalBytes"),
            reportedTotalIsExact = if (value.has("reportedTotalIsExact")) {
                value.optBoolean("reportedTotalIsExact", false)
            } else {
                value.optBoolean("totalIsExact", false)
            },
            activeStream = value.optString("activeStream")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { stored ->
                    enumValues<ActiveStream>().firstOrNull { it.name == stored }
                        ?: throw IllegalArgumentException("Unknown activeStream")
                },
            playlistItemIndex = value.optionalInt("playlistItemIndex"),
            playlistItemCount = value.optionalInt("playlistItemCount"),
            actualFinalFileBytes = value.optionalLong("actualFinalBytes"),
            createdAt = value.optLong("created", System.currentTimeMillis()),
            updatedAt = value.optLong("updated", System.currentTimeMillis()),
            outputUris = value.optJSONArray("outputs")?.strings().orEmpty(),
            error = restoredError,
            attempt = value.optInt("attempt", 1),
            actualResolution = value.optString("actualResolution").takeIf { it.isNotBlank() && it != "null" },
            runtimeName = value.optString("runtimeName"),
            runtimeVersion = value.optString("runtimeVersion"),
            subtitleStatus = optionalEnum(
                value.optString("subtitleStatus"),
                com.mvrk.vrka.subtitle.SubtitleStatus.NONE,
                "subtitleStatus",
            ),
            publishStarted = value.optBoolean("publishStarted", false),
        )
    }.getOrNull()

    private fun JSONObject.optionalInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private fun JSONObject.optionalLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)

    private fun JSONArray.strings(): List<String> =
        buildList { for (index in 0 until length()) add(optString(index)) }

    private inline fun <reified T : Enum<T>> optionalEnum(value: String?, fallback: T, field: String): T {
        val raw = value?.takeIf { it.isNotBlank() && it != "null" } ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown $field")
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val LEGACY_SCHEMA_VERSION = 1
        const val MAX_JOBS = 250
        const val MAX_RECOVERY_COPIES = 3
        private const val MAX_RECOVERY_RECORDS = 1_000
        private const val MAX_DETAIL_CHARS = 300
    }
}
