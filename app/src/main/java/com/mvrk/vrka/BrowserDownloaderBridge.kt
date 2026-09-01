package com.mvrk.vrka

import android.content.Context
import android.util.Log

object BrowserDownloaderBridge {

    private const val TAG = "VRKA-BrowserBridge"

    fun handoffToDownloader(
        candidate: MediaStreamCandidate,
        downloadManager: VrkaDownloadManager,
    ): DownloadRequest {
        Log.i(TAG, "Bridging browser candidate ${candidate.kind} (${candidate.url}) to VrkaDownloadManager")

        val sanitizedHeaders = candidate.headers.filterKeys { key ->
            key.lowercase() in listOf(
                "user-agent",
                "referer",
                "origin",
                "range",
                "cookie",
                "x-requested-with",
            )
        }

        val request = DownloadRequest(
            url = candidate.pageUrl.ifBlank { candidate.url },
            resolvedMediaUrl = candidate.url,
            resolvedHeaders = sanitizedHeaders,
            mode = if (candidate.kind.equals("Audio", true)) MediaMode.AUDIO else MediaMode.VIDEO,
            quality = VideoQuality.BEST,
        )

        downloadManager.enqueue(request)
        return request
    }
}
