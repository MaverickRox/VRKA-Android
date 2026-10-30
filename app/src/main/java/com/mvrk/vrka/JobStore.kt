package com.mvrk.vrka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JobStore(private val target: File) {
    constructor(context: Context) : this(File(context.filesDir, "download_jobs.json"))

    /**
     * [recoverActive] keeps in-flight states so a user-initiated transfer worker can resume
     * them. Without it, in-flight records are downgraded to FAILED as before.
     */
    fun load(recoverActive: Boolean = false): List<DownloadJob> {
        if (!target.isFile) return emptyList()
        val loaded = runCatching {
            val array = JSONArray(target.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.getJSONObject(index), recoverActive)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        // Rewrite legacy records after sensitive custom headers have been stripped.
        save(loaded)
        return loaded
    }

    @Synchronized
    fun save(jobs: List<DownloadJob>) {
        runCatching {
            val array = JSONArray()
            jobs.take(250).forEach { array.put(encode(it)) }
            val parent = target.parentFile ?: return@runCatching
            parent.mkdirs()
            val temporary = File.createTempFile("jobs_", ".tmp", parent)
            try {
                temporary.writeText(array.toString(), Charsets.UTF_8)
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.getOrElse {
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
            } finally {
                if (temporary.exists()) {
                    temporary.delete()
                }
            }
        }
    }

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
            .put("totalBytes", job.totalBytes)
            .put("totalIsExact", job.totalIsExact)
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
        val savedState = enumValue(value.optString("state"), JobState.FAILED)
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
            enumValue(storedAudioFormat, AudioFormat.MP3)
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
                mode = enumValue(encodedRequest.optString("mode"), MediaMode.VIDEO),
                quality = enumValue(
                    encodedRequest.optString("quality"),
                    VideoQuality.BEST,
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
            progress = value.optDouble("progress").toFloat(),
            speed = value.optString("speed"),
            etaSeconds = value.optionalLong("eta"),
            downloadedBytes = value.optionalLong("downloadedBytes"),
            totalBytes = value.optionalLong("totalBytes"),
            totalIsExact = value.optBoolean("totalIsExact", false),
            actualFinalFileBytes = value.optionalLong("actualFinalBytes"),
            createdAt = value.optLong("created", System.currentTimeMillis()),
            updatedAt = value.optLong("updated", System.currentTimeMillis()),
            outputUris = value.optJSONArray("outputs")?.strings().orEmpty(),
            error = restoredError,
            attempt = value.optInt("attempt", 1),
            actualResolution = value.optString("actualResolution").takeIf { it.isNotBlank() && it != "null" },
            runtimeName = value.optString("runtimeName"),
            runtimeVersion = value.optString("runtimeVersion"),
            subtitleStatus = enumValue(value.optString("subtitleStatus"), com.mvrk.vrka.subtitle.SubtitleStatus.NONE),
            publishStarted = value.optBoolean("publishStarted", false),
        )
    }.getOrNull()

    private fun JSONObject.optionalInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private fun JSONObject.optionalLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)

    private fun JSONArray.strings(): List<String> =
        buildList { for (index in 0 until length()) add(optString(index)) }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
