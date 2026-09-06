package com.mvrk.vrka

internal fun requestSummary(request: DownloadRequest): String = when (request.mode) {
    MediaMode.VIDEO -> "Video • ${request.quality.label}"
    MediaMode.AUDIO -> when (request.audioFormat) {
        AudioFormat.MP3 -> "MP3 • ${request.mp3Bitrate} kbps"
        AudioFormat.WAV -> "WAV • source-dependent"
        AudioFormat.FLAC -> "FLAC • source-dependent"
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

internal fun jobProgressSummary(job: DownloadJob): String = buildList {
    if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
        add("${job.progress.toInt()}%")
    }
    job.speed.takeIf(String::isNotBlank)?.let(::add)
    job.etaSeconds?.let { add(formatEtaCompact(it)) }
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
        "sign in" in text || "login" in text || "private" in text || "forbidden" in text ||
            "http error 403" in text -> "Sign-in may be required"
        "browser fallback closed" in text -> "Browser session closed"
        "requested format" in text || "no video formats" in text ||
            "no downloadable" in text -> "Couldn’t find a downloadable format"
        else -> "Download failed"
    }
}

internal fun friendlyFailureDetail(job: DownloadJob): String {
    val title = friendlyFailureTitle(job)
    return when (title) {
        "Couldn’t reach this site" -> "Check the connection, then try again."
        "Couldn’t find a downloadable format" ->
            "The page did not expose a supported non-DRM media format."
        "Sign-in may be required" -> "The source may require an authenticated browser session."
        "Download failed during publishing" ->
            "The media was processed, but Android could not save the final file."
        "Browser session timed out" -> "Retry the browser session or close it safely."
        "Browser session closed" -> "No downloadable non-DRM media was handed off."
        else -> "Try again. Failure details saved to Diagnostics in Settings."
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
