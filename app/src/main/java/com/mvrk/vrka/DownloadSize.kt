package com.mvrk.vrka

import android.content.Context
import java.util.Locale

/**
 * One progress sample taken from a yt-dlp `[download]` line. The total is approximate when
 * yt-dlp only has an estimate (`of ~ 48.20MiB`), which the UI marks with `~`.
 */
internal data class ProgressSizeUpdate(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val totalIsExact: Boolean,
    /** Current stream only; matches the percentage yt-dlp printed for this line. */
    val currentDownloadedBytes: Long = downloadedBytes,
    val currentTotalBytes: Long = totalBytes,
    val currentIsExact: Boolean = totalIsExact,
    val streamIndex: Int = 1,
    val aggregateDownloadedBytes: Long = downloadedBytes,
    val aggregateTotalBytes: Long = totalBytes,
)

/**
 * Parses the byte counts yt-dlp prints in its progress template:
 * `[download]  12.4% of  48.20MiB at  5.00MiB/s ETA 00:07` and the estimated form
 * `[download]  12.4% of ~ 48.20MiB ...`. Downloaded bytes are derived from the printed
 * percentage because the default template does not include the absolute byte count.
 */
internal object DownloadSizeParser {
    private val progressLine = Regex(
        """(?i)\[download]\s+([0-9]+(?:\.[0-9]+)?)%\s+of\s+(~)?\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGTP]i?B|B)\b""",
    )
    private val sizeToken = Regex("""(?i)^\s*~?\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGTP]i?B|B)\s*$""")

    fun parseProgressLine(line: String): ProgressSizeUpdate? {
        val match = progressLine.find(line) ?: return null
        val percent = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!percent.isFinite()) return null
        val total = sizeToBytes(match.groupValues[3], match.groupValues[4]) ?: return null
        if (total <= 0L) return null
        val downloaded = (total * (percent.coerceIn(0.0, 100.0) / 100.0)).toLong().coerceIn(0L, total)
        return ProgressSizeUpdate(
            downloadedBytes = downloaded,
            totalBytes = total,
            totalIsExact = match.groupValues[2].isEmpty(),
        )
    }

    fun parseSize(text: String): Long? {
        val match = sizeToken.matchEntire(text) ?: return null
        return sizeToBytes(match.groupValues[1], match.groupValues[2])
    }

    private fun sizeToBytes(value: String, unit: String): Long? {
        val number = value.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number < 0.0) return null
        val factor = when (unit.uppercase()) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "KIB" -> 1024.0
            "MB" -> 1_000_000.0
            "MIB" -> 1024.0 * 1024.0
            "GB" -> 1_000_000_000.0
            "GIB" -> 1024.0 * 1024.0 * 1024.0
            "TB" -> 1_000_000_000_000.0
            "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "PB" -> 1_000_000_000_000_000.0
            "PIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        val bytes = number * factor
        if (bytes > Long.MAX_VALUE.toDouble()) return null
        return bytes.toLong()
    }
}

/**
 * Folds per-stream progress samples into a merged-selection total. yt-dlp downloads the video
 * and audio streams separately and prints a fresh `of <size>` for each; the final container is
 * their sum, so the displayed total must never shrink back to the size of the current stream.
 * Repeated samples of one stream replace, never add to, that stream's total, so the same bytes
 * cannot be counted twice.
 */
internal class DownloadSizeTracker {
    private var finishedStreamBytes = 0L
    private var currentStreamBytes = 0L
    private var currentStreamExact = true
    private var streamCount = 0
    private var currentStreamActive = false
    private var currentTotalChanged = false

    fun reset() {
        finishedStreamBytes = 0L
        currentStreamBytes = 0L
        currentStreamExact = true
        streamCount = 0
        currentStreamActive = false
        currentTotalChanged = false
    }

    /**
     * Called on yt-dlp's `Destination:` line. It closes the previous stream exactly once so
     * the video and audio totals cannot be double-counted when the next progress line arrives.
     */
    fun beginStream() {
        if (currentStreamActive) {
            finishedStreamBytes += currentStreamBytes
            streamCount++
        } else if (streamCount == 0) {
            streamCount = 1
        }
        currentStreamBytes = 0L
        currentStreamExact = true
        currentStreamActive = false
        currentTotalChanged = false
    }

    fun update(sample: ProgressSizeUpdate): ProgressSizeUpdate {
        val total = sample.totalBytes
        if (total <= 0L) return sample
        when {
            !currentStreamActive -> {
                currentStreamBytes = total
                currentStreamExact = sample.totalIsExact
                currentStreamActive = true
                currentTotalChanged = false
                if (streamCount == 0) streamCount = 1
            }
            isSameStream(total) -> {
                if (total != currentStreamBytes) currentTotalChanged = true
                currentStreamBytes = maxOf(currentStreamBytes, total)
                currentStreamExact = currentStreamExact && sample.totalIsExact
            }
            else -> {
                // yt-dlp may revise an approximate total for the *same* stream as more
                // fragments are inspected. Stream boundaries come only from a new
                // Destination event, never from a changing denominator.
                if (total != currentStreamBytes) currentTotalChanged = true
                currentStreamBytes = total
                currentStreamExact = sample.totalIsExact
            }
        }
        val currentIsExact = currentStreamExact && !currentTotalChanged
        val combinedTotal = finishedStreamBytes + currentStreamBytes
        return ProgressSizeUpdate(
            downloadedBytes = (finishedStreamBytes + sample.downloadedBytes)
                .coerceIn(0L, combinedTotal),
            totalBytes = combinedTotal,
            // A merged container is never byte-identical to its source streams.
            totalIsExact = currentIsExact && streamCount == 1,
            currentDownloadedBytes = sample.downloadedBytes.coerceIn(0L, currentStreamBytes),
            currentTotalBytes = currentStreamBytes,
            currentIsExact = currentIsExact,
            streamIndex = streamCount,
            aggregateDownloadedBytes = (finishedStreamBytes + sample.downloadedBytes)
                .coerceIn(0L, combinedTotal),
            aggregateTotalBytes = combinedTotal,
        )
    }

    val aggregateTotalBytes: Long
        get() = finishedStreamBytes + currentStreamBytes

    val currentIndex: Int
        get() = streamCount

    private fun isSameStream(total: Long): Boolean {
        if (currentStreamBytes <= 0L || total == currentStreamBytes) return true
        val change = kotlin.math.abs(total - currentStreamBytes).toDouble() / currentStreamBytes
        return change <= SAME_STREAM_TOLERANCE
    }

    private companion object {
        // yt-dlp may adjust a stream's reported total slightly as fragments are probed.
        const val SAME_STREAM_TOLERANCE = 0.05
    }
}

/** Parses the playlist item line yt-dlp emits before each entry. */
internal object PlaylistProgressParser {
    private val ITEM = Regex(
        """Downloading(?:\s+playlist)?\s+item\s+(\d+)\s+of\s+(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(line: String): Pair<Int, Int>? {
        val match = ITEM.find(line) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        if (index < 1 || total < 1 || index > total) return null
        return index to total
    }
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mib = 1024.0 * 1024.0
    val gib = mib * 1024.0
    val kib = 1024.0
    return when {
        bytes >= gib -> String.format(Locale.US, "%.2f GiB", bytes / gib)
        bytes >= mib -> String.format(Locale.US, "%.1f MiB", bytes / mib)
        bytes >= kib -> String.format(Locale.US, "%.0f KiB", bytes / kib)
        else -> "$bytes B"
    }
}

/**
 * Presentation state for the queue size row. Kept free of Context so the exact/approximate
 * and calculating transitions can be unit-tested deterministically.
 */
internal sealed class DownloadSizeDisplay {
    data object Calculating : DownloadSizeDisplay()
    data object Unavailable : DownloadSizeDisplay()
    data class CalculatingTotal(val downloadedBytes: Long) : DownloadSizeDisplay()
    data class Downloaded(val bytes: Long) : DownloadSizeDisplay()
    data class Total(val bytes: Long, val exact: Boolean) : DownloadSizeDisplay()
    data class DownloadedOfTotal(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val exact: Boolean,
        val stream: ActiveStream? = null,
        val playlistItemIndex: Int? = null,
        val playlistItemCount: Int? = null,
    ) : DownloadSizeDisplay()
    data class Completed(val bytes: Long) : DownloadSizeDisplay()
    data object Hidden : DownloadSizeDisplay()
}

internal fun downloadSizeDisplay(job: DownloadJob): DownloadSizeDisplay {
    val reported = job.reportedTotalBytes?.takeIf { it > 0L }
    val estimated = job.estimatedTotalBytes?.takeIf { it > 0L }
    val estimateExact = job.estimatedSizeAccuracy == SizeEstimateKind.EXACT
    val downloaded = job.downloadedBytes?.takeIf { it > 0L }
    val active = job.state == JobState.QUEUED ||
        job.state == JobState.PREPARING ||
        job.state == JobState.WAITING_FOR_USER ||
        job.state == JobState.BROWSER_FALLBACK ||
        job.state == JobState.DOWNLOADING ||
        job.state == JobState.POSTPROCESSING
    if (job.state == JobState.DONE) {
        // The published document is authoritative; the transfer total describes staging and
        // may still carry the pre-merge estimate.
        val size = job.actualFinalFileBytes?.takeIf { it > 0L }
            ?: reported
            ?: estimated
            ?: downloaded
            ?: return DownloadSizeDisplay.Hidden
        return DownloadSizeDisplay.Completed(size)
    }
    if (!active) {
        // Failed/cancelled jobs keep whatever was transferred so the row is still useful.
        return when {
            downloaded != null -> DownloadSizeDisplay.Downloaded(downloaded)
            reported != null -> DownloadSizeDisplay.Total(reported, job.reportedTotalIsExact)
            estimated != null -> DownloadSizeDisplay.Total(estimated, estimateExact)
            else -> DownloadSizeDisplay.Hidden
        }
    }
    return when {
        downloaded != null && reported != null ->
            DownloadSizeDisplay.DownloadedOfTotal(
                downloaded,
                reported,
                job.reportedTotalIsExact,
                stream = job.activeStream,
                playlistItemIndex = job.playlistItemIndex,
                playlistItemCount = job.playlistItemCount,
            )
        downloaded != null && estimated != null ->
            DownloadSizeDisplay.DownloadedOfTotal(
                downloaded,
                estimated,
                estimateExact,
                stream = job.activeStream,
                playlistItemIndex = job.playlistItemIndex,
                playlistItemCount = job.playlistItemCount,
            )
        downloaded != null -> DownloadSizeDisplay.CalculatingTotal(downloaded)
        reported != null -> DownloadSizeDisplay.Total(reported, job.reportedTotalIsExact)
        estimated != null -> DownloadSizeDisplay.Total(estimated, estimateExact)
        // Only a transfer that is actually running may claim it is calculating a size.
        job.state == JobState.PREPARING ||
            job.state == JobState.DOWNLOADING -> DownloadSizeDisplay.Calculating
        else -> DownloadSizeDisplay.Unavailable
    }
}

internal fun downloadSizeLabel(context: Context, job: DownloadJob): String? =
    when (val display = downloadSizeDisplay(job)) {
        DownloadSizeDisplay.Calculating -> context.getString(R.string.download_size_calculating)
        DownloadSizeDisplay.Unavailable -> context.getString(R.string.estimate_unavailable)
        is DownloadSizeDisplay.CalculatingTotal -> context.getString(
            R.string.download_size_calculating_with_bytes,
            formatDownloadBytes(display.downloadedBytes),
        )
        is DownloadSizeDisplay.Downloaded ->
            context.getString(R.string.download_size_downloaded, formatDownloadBytes(display.bytes))
        is DownloadSizeDisplay.Total ->
            context.getString(
                R.string.download_size_total,
                formatDownloadBytes(display.bytes).withApproximateMarker(display.exact),
            )
        is DownloadSizeDisplay.DownloadedOfTotal -> {
            val scope = buildList {
                if (display.playlistItemIndex != null && display.playlistItemCount != null) {
                    add(
                        context.getString(
                            R.string.download_playlist_item,
                            display.playlistItemIndex,
                            display.playlistItemCount,
                        ),
                    )
                }
                when (display.stream) {
                    ActiveStream.VIDEO -> add(context.getString(R.string.media_mode_video))
                    ActiveStream.AUDIO -> add(context.getString(R.string.media_mode_audio))
                    ActiveStream.MEDIA, null -> Unit
                }
            }.joinToString(" · ")
            val downloaded = formatDownloadBytes(display.downloadedBytes)
            val total = formatDownloadBytes(display.totalBytes)
                .withApproximateMarker(display.exact)
            if (scope.isBlank()) {
                context.getString(R.string.download_size_downloaded_of, downloaded, total)
            } else {
                context.getString(
                    R.string.download_size_scoped_downloaded_of,
                    scope,
                    downloaded,
                    total,
                )
            }
        }
        is DownloadSizeDisplay.Completed ->
            context.getString(R.string.download_size_completed, formatDownloadBytes(display.bytes))
        DownloadSizeDisplay.Hidden -> null
    }

private fun String.withApproximateMarker(exact: Boolean): String = if (exact) this else "~$this"
