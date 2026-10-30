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
    data class CalculatingTotal(val downloadedBytes: Long) : DownloadSizeDisplay()
    data class Downloaded(val bytes: Long) : DownloadSizeDisplay()
    data class Total(val bytes: Long, val exact: Boolean) : DownloadSizeDisplay()
    data class DownloadedOfTotal(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val exact: Boolean,
    ) : DownloadSizeDisplay()
    data class Completed(val bytes: Long) : DownloadSizeDisplay()
    data object Hidden : DownloadSizeDisplay()
}

internal fun downloadSizeDisplay(job: DownloadJob): DownloadSizeDisplay {
    val total = job.totalBytes?.takeIf { it > 0L }
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
            ?: total
            ?: downloaded
            ?: return DownloadSizeDisplay.Hidden
        return DownloadSizeDisplay.Completed(size)
    }
    if (!active) {
        // Failed/cancelled jobs keep whatever was transferred so the row is still useful.
        return when {
            downloaded != null -> DownloadSizeDisplay.Downloaded(downloaded)
            total != null -> DownloadSizeDisplay.Total(total, job.totalIsExact)
            else -> DownloadSizeDisplay.Hidden
        }
    }
    return when {
        downloaded != null && total != null ->
            DownloadSizeDisplay.DownloadedOfTotal(downloaded, total, job.totalIsExact)
        downloaded != null -> DownloadSizeDisplay.CalculatingTotal(downloaded)
        total != null -> DownloadSizeDisplay.Total(total, job.totalIsExact)
        else -> DownloadSizeDisplay.Calculating
    }
}

internal fun downloadSizeLabel(context: Context, job: DownloadJob): String? =
    when (val display = downloadSizeDisplay(job)) {
        DownloadSizeDisplay.Calculating -> context.getString(R.string.download_size_calculating)
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
        is DownloadSizeDisplay.DownloadedOfTotal -> context.getString(
            R.string.download_size_downloaded_of,
            formatDownloadBytes(display.downloadedBytes),
            formatDownloadBytes(display.totalBytes).withApproximateMarker(display.exact),
        )
        is DownloadSizeDisplay.Completed ->
            context.getString(R.string.download_size_completed, formatDownloadBytes(display.bytes))
        DownloadSizeDisplay.Hidden -> null
    }

private fun String.withApproximateMarker(exact: Boolean): String = if (exact) this else "~$this"
