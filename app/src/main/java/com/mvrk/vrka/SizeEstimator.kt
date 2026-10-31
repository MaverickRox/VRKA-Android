package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Result of one metadata-only preflight, including the facts needed to explain a failure in
 * local diagnostics. Only the estimate is user-facing; the remaining fields never contain
 * cookies or authentication headers (the process cannot print request headers here).
 */
data class SizePreflight(
    val estimate: SizeEstimate,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val timedOut: Boolean,
    val stderrTail: String,
    val runtimeLabel: String,
    val formatSelector: String,
    val stdoutChars: Int,
    /** Actual command executed (header values redacted) for local diagnostics. */
    val command: String = "",
    /** Bounded description of the metadata response; the full document is never persisted. */
    val documentSummary: String = "",
    /** Raw metadata document, retained so other selections can be recalculated locally. */
    val document: String? = null,
)

/**
 * Runs the metadata-only size preflight on whichever yt-dlp runtime is currently active.
 * One invocation returns the complete metadata document (all formats and subtitles); every
 * later selection is answered locally from it. A failed or timed-out preflight is reported as
 * [SizeEstimateKind.UNAVAILABLE]; it never blocks the download and never affects the
 * browser-fallback state machine.
 */
class SizeEstimator(
    private val runtimeLabel: () -> String = { "" },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun estimate(request: DownloadRequest): SizeEstimate = probe(request).estimate

    /** Recalculates a size from a cached document without launching yt-dlp again. */
    fun estimateFromDocument(
        document: String,
        request: DownloadRequest,
        recalculateSelection: Boolean,
    ): SizeEstimate = SizeEstimateCalculator.fromMetadataJson(document, request, recalculateSelection)

    suspend fun probe(request: DownloadRequest, timeoutOverrideMs: Long? = null): SizePreflight {
        val started = System.currentTimeMillis()
        val formatSelector = DownloadRequestFactory.formatSelectorFor(request)
        val runtime = runCatching { runtimeLabel() }.getOrDefault("")
        fun report(
            estimate: SizeEstimate,
            exitCode: Int? = null,
            timedOut: Boolean = false,
            stderr: String = "",
            stdoutChars: Int = 0,
            command: String = "",
            documentSummary: String = "",
            document: String? = null,
        ): SizePreflight {
            val finished = System.currentTimeMillis()
            return SizePreflight(
                estimate = estimate,
                startedAtEpochMs = started,
                finishedAtEpochMs = finished,
                durationMs = finished - started,
                exitCode = exitCode,
                timedOut = timedOut,
                stderrTail = stderr.trim().lineSequence().filter(String::isNotBlank).toList()
                    .takeLast(MAX_STDERR_LINES).joinToString("\n").take(MAX_STDERR_CHARS),
                runtimeLabel = runtime,
                formatSelector = formatSelector,
                stdoutChars = stdoutChars,
                command = command,
                documentSummary = documentSummary,
                document = document,
            )
        }

        if (!request.commandTemplateId.isNullOrBlank()) {
            return report(SizeEstimate.unavailable("custom-command"))
        }

        val ytDlpRequest = DownloadRequestFactory.sizeEstimate(request)
        val command = runCatching {
            DownloadRequestFactory.diagnosticCommand(ytDlpRequest)
        }.getOrDefault("")
        val uniqueId = "size-" + UUID.randomUUID()
        var stderr = ""
        var exitCode: Int? = null
        var processFailed = false
        var timedOut = false
        var stdout: String? = null
        try {
            stdout = try {
                withTimeoutOrNull(timeoutOverrideMs ?: timeoutMs) {
                    runInterruptible(Dispatchers.IO) {
                        try {
                            val response = YoutubeDL.getInstance().execute(ytDlpRequest, uniqueId)
                            exitCode = response.exitCode
                            stderr = response.err
                            response.out
                        } catch (error: CancellationException) {
                            throw error
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        } catch (error: Exception) {
                            processFailed = true
                            stderr = error.message.orEmpty()
                            null
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                timedOut = true
                null
            }
            if (stdout == null && !timedOut) timedOut = !processFailed
        } finally {
            // The process normally exits on its own; this also reaps it after a timeout
            // or coroutine cancellation (runInterruptible interrupts the blocking wait).
            runCatching { YoutubeDL.getInstance().destroyProcessById(uniqueId) }
        }

        if (stdout.isNullOrBlank()) {
            val reason = if (timedOut) "timeout" else "process"
            val detail = if (timedOut) {
                "preflight timed out after ${timeoutOverrideMs ?: timeoutMs}ms"
            } else {
                "yt-dlp produced no metadata JSON"
            }
            return report(
                SizeEstimate.unavailable(reason, detail),
                exitCode = exitCode,
                timedOut = timedOut,
                stderr = stderr,
                command = command,
            )
        }

        // The preflight intentionally omits `-f`, so the document's `requested_formats`
        // describe yt-dlp's default selection rather than the user's choice. Recalculate the
        // requested quality locally from the complete format table.
        val estimate = SizeEstimateCalculator.fromMetadataJson(
            stdout,
            request,
            recalculateSelection = true,
        )
        val documentSummary = runCatching {
            SizeEstimateCalculator.summarizeMetadataJson(stdout)
        }.getOrDefault("")
        return report(
            estimate = if (estimate.isAvailable) {
                estimate
            } else {
                estimate.copy(
                    diagnostic = listOf(
                        estimate.diagnostic,
                        "runtime=$runtime",
                        "format=$formatSelector",
                        "exit=$exitCode",
                        "chars=${stdout.length}",
                        stderr.takeIf { it.isNotBlank() }?.let { "stderr=${it.take(400)}" }.orEmpty(),
                    ).filter(String::isNotBlank).joinToString("; "),
                )
            },
            exitCode = exitCode,
            stderr = stderr,
            stdoutChars = stdout.length,
            command = command,
            documentSummary = documentSummary,
            document = stdout,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 25_000L
        private const val MAX_STDERR_LINES = 8
        private const val MAX_STDERR_CHARS = 800
    }
}
