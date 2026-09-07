package com.mvrk.vrka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JobStore(private val target: File) {
    constructor(context: Context) : this(File(context.filesDir, "download_jobs.json"))

    fun load(): List<DownloadJob> {
        if (!target.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(target.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(jobs: List<DownloadJob>) {
        val array = JSONArray()
        jobs.take(250).forEach { array.put(encode(it)) }
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(array.toString(), Charsets.UTF_8)
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
            .put("created", job.createdAt)
            .put("updated", job.updatedAt)
            .put("outputs", JSONArray(job.outputUris))
            .put("error", job.error)
            .put("attempt", job.attempt)
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
                    .put("customArguments", JSONArray(request.customArguments)),
            )
    }

    private fun decode(value: JSONObject): DownloadJob? = runCatching {
        val encodedRequest = value.getJSONObject("request")
        val savedState = enumValue(value.optString("state"), JobState.FAILED)
        val restoredState = if (savedState.isTerminal) savedState else JobState.FAILED
        val restoredError = if (savedState.isTerminal) {
            value.optString("error")
        } else {
            "Download was interrupted when Android stopped VRKA. Tap retry."
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
                audioFormat = enumValue(
                    encodedRequest.optString("audioFormat"),
                    AudioFormat.MP3,
                ),
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
            ),
            state = restoredState,
            title = value.optString("title"),
            detail = if (savedState.isTerminal) {
                value.optString("detail")
            } else {
                "Interrupted"
            },
            progress = value.optDouble("progress").toFloat(),
            speed = value.optString("speed"),
            etaSeconds = value.optionalLong("eta"),
            createdAt = value.optLong("created", System.currentTimeMillis()),
            updatedAt = value.optLong("updated", System.currentTimeMillis()),
            outputUris = value.optJSONArray("outputs")?.strings().orEmpty(),
            error = restoredError,
            attempt = value.optInt("attempt", 1),
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
