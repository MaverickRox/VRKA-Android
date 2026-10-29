package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the metadata-only size preflight on whichever yt-dlp runtime is currently active.
 * A failed or timed-out preflight is reported as [SizeEstimateKind.UNAVAILABLE]; it never
 * blocks the download and never affects the browser-fallback state machine.
 */
class SizeEstimator {

    suspend fun estimate(
        request: DownloadRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): SizeEstimate {
        if (!request.commandTemplateId.isNullOrBlank()) {
            return SizeEstimate.unavailable("custom-command")
        }
        val uniqueId = "size-" + UUID.randomUUID()
        val failed = AtomicBoolean(false)
        try {
            val json = withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    runCatching {
                        YoutubeDL.getInstance().execute(
                            DownloadRequestFactory.sizeEstimate(request),
                            uniqueId,
                        ).out
                    }.getOrNull().also { output -> if (output.isNullOrBlank()) failed.set(true) }
                }
            } ?: return SizeEstimate.unavailable(if (failed.get()) "failed" else "timeout")
            if (json.isBlank()) return SizeEstimate.unavailable("failed")
            return SizeEstimateCalculator.fromMetadataJson(json, request)
        } finally {
            // The process normally exits on its own; this also reaps it after a timeout
            // or coroutine cancellation (runInterruptible interrupts the blocking wait).
            runCatching { YoutubeDL.getInstance().destroyProcessById(uniqueId) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
    }
}
