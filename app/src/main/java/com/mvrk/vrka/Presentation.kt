package com.mvrk.vrka

import android.content.Context

internal fun requestSummary(request: DownloadRequest): String = when (request.mode) {
    MediaMode.VIDEO -> "Video • ${request.quality.label}"
    MediaMode.AUDIO -> when (request.audioFormat) {
        AudioFormat.MP3 -> "MP3 • ${request.mp3Bitrate} kbps"
        AudioFormat.OPUS -> "Opus • Best Native Opus"
        AudioFormat.WAV -> "WAV • Uncompressed PCM"
    }
}

internal fun requestSummary(context: Context, request: DownloadRequest): String = when (request.mode) {
    MediaMode.VIDEO -> {
        val qLabel = if (request.quality == VideoQuality.BEST) {
            context.getString(R.string.video_quality_best)
        } else {
            request.quality.label
        }
        "${context.getString(R.string.media_mode_video)} • $qLabel"
    }
    MediaMode.AUDIO -> when (request.audioFormat) {
        AudioFormat.MP3 -> "MP3 • ${request.mp3Bitrate} kbps"
        AudioFormat.OPUS -> "Opus • ${context.getString(R.string.audio_quality_native_opus)}"
        AudioFormat.WAV -> "WAV • ${context.getString(R.string.audio_quality_source_pcm)}"
    }
}

internal fun jobMediaSummary(job: DownloadJob): String = when (job.request.mode) {
    MediaMode.VIDEO -> {
        val actual = job.actualResolution
        if (!actual.isNullOrBlank()) {
            if (job.request.quality == VideoQuality.BEST) "Video • $actual (Best available)"
            else if (job.request.quality.label.startsWith(actual)) "Video • $actual"
            else "Video • $actual (requested: ${job.request.quality.label})"
        } else {
            "Video • ${job.request.quality.label}"
        }
    }
    MediaMode.AUDIO -> when (job.request.audioFormat) {
        AudioFormat.MP3 -> "Audio • MP3 (${job.request.mp3Bitrate} kbps)"
        AudioFormat.OPUS -> "Audio • Opus (Native)"
        AudioFormat.WAV -> "Audio • WAV (PCM)"
    }
}

internal fun jobMediaSummary(context: Context, job: DownloadJob): String = when (job.request.mode) {
    MediaMode.VIDEO -> {
        val actual = job.actualResolution
        val videoMode = context.getString(R.string.media_mode_video)
        if (!actual.isNullOrBlank()) {
            if (job.request.quality == VideoQuality.BEST) {
                context.getString(R.string.media_summary_video_best, actual)
            } else if (job.request.quality.label.startsWith(actual)) {
                context.getString(R.string.media_summary_video_actual, actual)
            } else {
                val qLabel = if (job.request.quality == VideoQuality.BEST) {
                    context.getString(R.string.video_quality_best)
                } else {
                    job.request.quality.label
                }
                context.getString(R.string.media_summary_video_requested, actual, qLabel)
            }
        } else {
            val qLabel = if (job.request.quality == VideoQuality.BEST) {
                context.getString(R.string.video_quality_best)
            } else {
                job.request.quality.label
            }
            "$videoMode • $qLabel"
        }
    }
    MediaMode.AUDIO -> when (job.request.audioFormat) {
        AudioFormat.MP3 -> context.getString(R.string.media_summary_audio_mp3, job.request.mp3Bitrate)
        AudioFormat.OPUS -> context.getString(R.string.media_summary_audio_opus)
        AudioFormat.WAV -> context.getString(R.string.media_summary_audio_wav)
    }
}

internal fun jobStatusLabel(job: DownloadJob): String = when {
    job.state == JobState.PREPARING && job.detail.contains("runtime", true) -> "Analysing"
    job.state == JobState.POSTPROCESSING && job.detail.contains("Publishing", true) -> "Publishing"
    job.state == JobState.POSTPROCESSING &&
        (job.request.trimStart.isNotBlank() || job.request.trimEnd.isNotBlank()) -> "Trimming"
    job.state == JobState.POSTPROCESSING && job.request.mode == MediaMode.AUDIO -> "Converting"
    job.state == JobState.POSTPROCESSING -> "Merging"
    else -> job.state.label
}

internal fun jobStatusLabel(context: Context, job: DownloadJob): String = when {
    job.state == JobState.PREPARING && job.detail.contains("runtime", true) -> context.getString(R.string.status_analysing)
    job.state == JobState.POSTPROCESSING && job.detail.contains("Publishing", true) -> context.getString(R.string.status_publishing)
    job.state == JobState.POSTPROCESSING &&
        (job.request.trimStart.isNotBlank() || job.request.trimEnd.isNotBlank()) -> context.getString(R.string.status_trimming)
    job.state == JobState.POSTPROCESSING && job.request.mode == MediaMode.AUDIO -> context.getString(R.string.status_converting)
    job.state == JobState.POSTPROCESSING -> context.getString(R.string.status_merging)
    else -> localizedJobStateLabel(context, job.state)
}

internal fun localizedJobStateLabel(context: Context, state: JobState): String = when (state) {
    JobState.QUEUED -> context.getString(R.string.status_queued)
    JobState.PREPARING -> context.getString(R.string.status_preparing)
    JobState.WAITING_FOR_USER -> context.getString(R.string.status_waiting_for_user)
    JobState.BROWSER_FALLBACK -> context.getString(R.string.status_browser_fallback)
    JobState.DOWNLOADING -> context.getString(R.string.status_downloading)
    JobState.POSTPROCESSING -> context.getString(R.string.status_finishing)
    JobState.DONE -> context.getString(R.string.status_complete)
    JobState.FAILED -> context.getString(R.string.status_failed)
    JobState.CANCELLED -> context.getString(R.string.status_cancelled)
}

internal fun jobProgressSummary(job: DownloadJob): String = buildList {
    if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
        add("${job.progress.toInt()}%")
    }
    job.speed.takeIf(String::isNotBlank)?.let(::add)
    job.etaSeconds?.let { add(formatEtaCompact(it)) }
}.joinToString(" • ").ifBlank { job.detail }

internal fun jobProgressSummary(context: Context, job: DownloadJob): String = buildList {
    if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
        add("${job.progress.toInt()}%")
    }
    job.speed.takeIf(String::isNotBlank)?.let(::add)
    job.etaSeconds?.let { add(formatEtaCompact(context, it)) }
}.joinToString(" • ").ifBlank { job.detail }

internal fun friendlyFailureTitle(job: DownloadJob): String {
    val text = "${job.detail} ${job.error}".lowercase()
    return when {
        "publish" in text || "mediastore" in text || "output file" in text ->
            "Download failed during publishing"
        "timed out" in text && ("browser" in text || "webview" in text) ->
            "Browser session timed out"
        "resolve host" in text || "name_not_resolved" in text || "dns" in text ||
            "network is unreachable" in text || "connection timed out" in text ->
            "Couldn’t reach this site"
        "browser fallback could not find" in text || "could not find a media stream" in text ||
            "requested format" in text || "no video formats" in text ||
            "no downloadable" in text -> "Couldn’t find a downloadable format"
        "sign in to confirm" in text || "login required" in text || "account required" in text ||
            "authentication required" in text || "private video" in text || "video is private" in text ->
            "Sign-in may be required"
        "browser fallback closed" in text -> "Browser session closed"
        else -> "Download failed"
    }
}

internal fun friendlyFailureTitle(context: Context, job: DownloadJob): String {
    val text = "${job.detail} ${job.error}".lowercase()
    return when {
        "publish" in text || "mediastore" in text || "output file" in text ->
            context.getString(R.string.failure_publishing_title)
        "timed out" in text && ("browser" in text || "webview" in text) ->
            context.getString(R.string.failure_browser_timeout_title)
        "resolve host" in text || "name_not_resolved" in text || "dns" in text ||
            "network is unreachable" in text || "connection timed out" in text ->
            context.getString(R.string.failure_network_title)
        "browser fallback could not find" in text || "could not find a media stream" in text ||
            "requested format" in text || "no video formats" in text ||
            "no downloadable" in text -> context.getString(R.string.failure_no_format_title)
        "sign in to confirm" in text || "login required" in text || "account required" in text ||
            "authentication required" in text || "private video" in text || "video is private" in text ->
            context.getString(R.string.failure_auth_title)
        "browser fallback closed" in text -> context.getString(R.string.failure_browser_closed_title)
        else -> context.getString(R.string.failure_generic_title)
    }
}

internal fun friendlyFailureDetail(job: DownloadJob): String {
    val title = friendlyFailureTitle(job)
    return when {
        title == "Couldn’t reach this site" -> "Check the connection, then try again."
        title == "Couldn’t find a downloadable format" ->
            "The page did not expose a supported non-DRM media format."
        title == "Sign-in may be required" -> "The source may require an authenticated browser session."
        title == "Download failed during publishing" ->
            "The media was processed, but Android could not save the final file."
        title == "Browser session timed out" -> "Retry the browser session or close it safely."
        title == "Browser session closed" -> "No downloadable non-DRM media was handed off."
        else -> "Try again. Failure details saved to Diagnostics in Settings."
    }
}

internal fun friendlyFailureDetail(context: Context, job: DownloadJob): String {
    val text = "${job.detail} ${job.error}".lowercase()
    return when {
        "publish" in text || "mediastore" in text || "output file" in text ->
            context.getString(R.string.failure_publishing_detail)
        "timed out" in text && ("browser" in text || "webview" in text) ->
            context.getString(R.string.failure_browser_timeout_detail)
        "resolve host" in text || "name_not_resolved" in text || "dns" in text ||
            "network is unreachable" in text || "connection timed out" in text ->
            context.getString(R.string.failure_network_detail)
        "browser fallback could not find" in text || "could not find a media stream" in text ||
            "requested format" in text || "no video formats" in text ||
            "no downloadable" in text -> context.getString(R.string.failure_no_format_detail)
        "sign in to confirm" in text || "login required" in text || "account required" in text ||
            "authentication required" in text || "private video" in text || "video is private" in text ->
            context.getString(R.string.failure_auth_detail)
        "browser fallback closed" in text -> context.getString(R.string.failure_browser_closed_detail)
        else -> context.getString(R.string.failure_generic_detail)
    }
}

internal fun formatEtaCompact(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d left".format(hours, minutes, remainder)
    } else {
        "%d:%02d left".format(minutes, remainder)
    }
}

internal fun formatEtaCompact(context: Context, seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) {
        context.getString(R.string.eta_format_hours, hours, minutes, remainder)
    } else {
        context.getString(R.string.eta_format_minutes, minutes, remainder)
    }
}
