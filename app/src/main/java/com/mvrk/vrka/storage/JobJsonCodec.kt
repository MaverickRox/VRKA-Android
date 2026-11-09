package com.mvrk.vrka.storage

import com.mvrk.vrka.ActiveStream
import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.HeaderValidation
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.SizeEstimateKind
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.subtitle.SubtitleStatus
import org.json.JSONArray
import org.json.JSONObject

internal object JobJsonCodec {
    fun attemptIdFor(jobId: String, attemptNumber: Int): String = "$jobId:attempt:$attemptNumber"

    fun encodeRequest(request: DownloadRequest): String = JSONObject()
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
        .put("destinationTreeUri", request.destinationTreeUri)
        .toString()

    fun decodeRequest(text: String): DownloadRequest {
        val json = JSONObject(text)
        val customHeaders = json.optJSONObject("customHeaders")?.let { obj ->
            buildMap {
                for (key in obj.keys()) {
                    if (!HeaderValidation.isSensitiveHeader(key)) {
                        put(key, obj.optString(key))
                    }
                }
            }
        }.orEmpty()
        val storedAudioFormat = json.optString("audioFormat")
        val audioFormat = if (storedAudioFormat == "FLAC") AudioFormat.OPUS else enumValue(storedAudioFormat, AudioFormat.MP3)
        return DownloadRequest(
            url = json.getString("url"),
            mode = enumValue(json.optString("mode"), MediaMode.VIDEO),
            quality = enumValue(json.optString("quality"), VideoQuality.BEST),
            prefer60Fps = json.optBoolean("prefer60Fps"),
            audioFormat = audioFormat,
            mp3Bitrate = json.optInt("bitrate", 320),
            isPlaylist = json.optBoolean("playlist"),
            playlistStart = json.optionalInt("playlistStart"),
            playlistEnd = json.optionalInt("playlistEnd"),
            downloadSubtitles = json.optBoolean("subtitles"),
            automaticCaptions = json.optBoolean("automaticCaptions", true),
            embedSubtitles = json.optBoolean("embedSubtitles", true),
            subtitleLanguages = json.optString("subtitleLanguages", "en.*"),
            embedMetadata = json.optBoolean("metadata", true),
            embedThumbnail = json.optBoolean("thumbnail", true),
            sponsorBlock = json.optBoolean("sponsorBlock"),
            sponsorCategories = json.optString("sponsorCategories", "sponsor,selfpromo,interaction"),
            trimStart = json.optString("trimStart"),
            trimEnd = json.optString("trimEnd"),
            customArguments = json.optJSONArray("customArguments")?.strings().orEmpty(),
            commandTemplateId = json.optString("commandTemplateId").takeIf { it.isNotBlank() && it != "null" },
            commandTemplateName = json.optString("commandTemplateName"),
            commandTemplateArguments = json.optJSONArray("commandTemplateArguments")?.strings().orEmpty(),
            h264CompatibilityMode = json.optBoolean("h264CompatibilityMode", false),
            referer = json.optString("referer"),
            origin = json.optString("origin"),
            customHeaders = customHeaders,
            destinationTreeUri = if (json.isNull("destinationTreeUri")) null else json.optString("destinationTreeUri").takeIf { it.isNotBlank() },
        )
    }

    fun encodeJob(
        job: DownloadJob,
        currentAttemptId: String,
        legacyVerification: String,
        recoveryRequired: Boolean,
        terminalAt: Long?,
        commitSequence: Long,
        version: Long,
    ): JobEntity = JobEntity(
        id = job.id,
        requestJson = encodeRequest(job.request),
        state = job.state.name,
        title = job.title,
        detail = job.detail,
        progress = job.progress.takeIf { it.isFinite() } ?: 0f,
        speed = job.speed,
        etaSeconds = job.etaSeconds,
        downloadedBytes = job.downloadedBytes,
        estimatedTotalBytes = job.estimatedTotalBytes,
        estimatedSizeAccuracy = job.estimatedSizeAccuracy.name,
        reportedTotalBytes = job.reportedTotalBytes,
        reportedTotalIsExact = job.reportedTotalIsExact,
        activeStream = job.activeStream?.name,
        playlistItemIndex = job.playlistItemIndex,
        playlistItemCount = job.playlistItemCount,
        actualFinalFileBytes = job.actualFinalFileBytes,
        createdAt = job.createdAt,
        updatedAt = job.updatedAt,
        outputUrisJson = JSONArray(job.outputUris).toString(),
        error = job.error,
        attempt = job.attempt,
        actualResolution = job.actualResolution,
        runtimeName = job.runtimeName,
        runtimeVersion = job.runtimeVersion,
        subtitleStatus = job.subtitleStatus.name,
        publishStarted = job.publishStarted,
        currentAttemptId = currentAttemptId,
        legacyVerification = legacyVerification,
        recoveryRequired = recoveryRequired,
        terminalAt = terminalAt,
        commitSequence = commitSequence,
        version = version,
    )

    fun decodeJob(entity: JobEntity): DownloadJob = DownloadJob(
        id = entity.id,
        request = decodeRequest(entity.requestJson),
        state = enumValue(entity.state, JobState.FAILED),
        title = entity.title,
        detail = entity.detail,
        progress = entity.progress.takeIf { it.isFinite() } ?: 0f,
        speed = entity.speed,
        etaSeconds = entity.etaSeconds,
        estimatedTotalBytes = entity.estimatedTotalBytes,
        estimatedSizeAccuracy = enumValue(entity.estimatedSizeAccuracy, SizeEstimateKind.UNAVAILABLE),
        downloadedBytes = entity.downloadedBytes,
        reportedTotalBytes = entity.reportedTotalBytes,
        reportedTotalIsExact = entity.reportedTotalIsExact,
        activeStream = entity.activeStream?.let { enumValue(it, ActiveStream.MEDIA) },
        playlistItemIndex = entity.playlistItemIndex,
        playlistItemCount = entity.playlistItemCount,
        actualFinalFileBytes = entity.actualFinalFileBytes,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        outputUris = runCatching {
            val array = JSONArray(entity.outputUrisJson)
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
        }.getOrDefault(emptyList()),
        error = entity.error,
        attempt = entity.attempt,
        actualResolution = entity.actualResolution,
        runtimeName = entity.runtimeName,
        runtimeVersion = entity.runtimeVersion,
        subtitleStatus = enumValue(entity.subtitleStatus, SubtitleStatus.NONE),
        publishStarted = entity.publishStarted,
        recoveryRequired = entity.recoveryRequired,
        legacyVerification = entity.legacyVerification,
    )

    fun entityVersion(entity: JobEntity): Long = entity.version

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun JSONObject.optionalInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private fun JSONArray.strings(): List<String> =
        buildList { for (index in 0 until length()) add(optString(index)) }
}
