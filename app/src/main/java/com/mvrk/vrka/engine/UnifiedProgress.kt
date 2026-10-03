package com.mvrk.vrka.engine

import java.util.Locale

/**
 * Unified progress snapshot across all media download engines.
 */
data class UnifiedProgressSnapshot(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val percentage: Float? = null,
    val segmentProgress: Pair<Int, Int>? = null, // completed to total segments
    val durationProcessedSeconds: Double? = null,
    val totalDurationSeconds: Double? = null,
    val realtimeMultiplier: Double? = null, // e.g. 3.8x
    val etaSeconds: Long? = null,
    val detail: String = "",
) {
    /**
     * Compute safe display percentage:
     * NEVER returns 0% when downloadedBytes > 0!
     */
    val safePercentage: Float?
        get() {
            if (percentage != null) {
                return if (downloadedBytes > 0L && percentage < 1f) 1f else percentage.coerceIn(0f, 100f)
            }
            if (totalBytes != null && totalBytes > 0L) {
                val calc = (downloadedBytes.toDouble() / totalBytes.toDouble() * 100.0).toFloat()
                return if (downloadedBytes > 0L && calc < 1f) 1f else calc.coerceIn(0f, 100f)
            }
            if (segmentProgress != null && segmentProgress.second > 0) {
                val calc = (segmentProgress.first.toDouble() / segmentProgress.second.toDouble() * 100.0).toFloat()
                return if (downloadedBytes > 0L && calc < 1f) 1f else calc.coerceIn(0f, 100f)
            }
            if (durationProcessedSeconds != null && totalDurationSeconds != null && totalDurationSeconds > 0.0) {
                val calc = (durationProcessedSeconds / totalDurationSeconds * 100.0).toFloat()
                return if (downloadedBytes > 0L && calc < 1f) 1f else calc.coerceIn(0f, 100f)
            }
            return null
        }

    /**
     * Formats the status line for download card UI.
     * Examples:
     * - "324 MB / 1.2 GB • 3.8 MB/s (27%)"
     * - "324 MB • 3.8 MB/s (3.8x)"
     * - "Segment 45/120 • 324 MB • 3.8 MB/s (37%)"
     */
    fun formatCardStatusText(): String {
        val parts = mutableListOf<String>()

        // 1. Segments or bytes transferred
        if (segmentProgress != null && segmentProgress.second > 0) {
            parts.add("Segment ${segmentProgress.first}/${segmentProgress.second}")
        }

        if (totalBytes != null && totalBytes > 0L) {
            parts.add("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
        } else if (downloadedBytes > 0L) {
            parts.add(formatBytes(downloadedBytes))
        }

        // 2. Speed and/or realtime multiplier
        val speedStr = bytesPerSecond?.let { formatSpeed(it) }
        val multStr = realtimeMultiplier?.let { String.format(Locale.US, "%.1fx", it) }

        if (speedStr != null && multStr != null) {
            parts.add("$speedStr ($multStr)")
        } else if (speedStr != null) {
            parts.add(speedStr)
        } else if (multStr != null) {
            parts.add(multStr)
        }

        // 3. Percentage
        safePercentage?.let { pct ->
            val pctStr = String.format(Locale.US, "%.0f%%", pct)
            // Replace trailing or add percentage
            if (parts.isNotEmpty()) {
                val last = parts.removeAt(parts.size - 1)
                parts.add("$last ($pctStr)")
            } else {
                parts.add(pctStr)
            }
        }

        if (parts.isEmpty()) {
            return detail.ifBlank { "Downloading" }
        }

        val baseStatus = parts.joinToString(" • ")
        return if (detail.isNotBlank() && !baseStatus.contains(detail, ignoreCase = true) && !detail.equals("Downloading", ignoreCase = true)) {
            "$baseStatus • $detail"
        } else {
            baseStatus
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var b = bytes.toDouble()
            var unitIndex = 0
            while (b >= 1024.0 && unitIndex < units.size - 1) {
                b /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", b, units[unitIndex])
        }

        fun formatSpeed(bytesPerSec: Long): String {
            return "${formatBytes(bytesPerSec)}/s"
        }
    }
}

/**
 * Helper to compute smoothed speed, ETA, and progress snapshots during streaming transfers.
 */
class ProgressTracker(
    private val totalExpectedBytes: Long? = null,
    private val totalExpectedSegments: Int? = null,
    private val totalDurationSeconds: Double? = null,
) {
    private var startTimeMs: Long = System.currentTimeMillis()
    private var lastUpdateMs: Long = startTimeMs
    private var downloadedBytes: Long = 0L
    private var completedSegments: Int = 0

    // Rolling window for speed calculation (up to 5 samples)
    private val windowSamples = mutableListOf<Pair<Long, Long>>() // timestampMs to bytes

    @Synchronized
    fun updateBytes(bytes: Long, detail: String = ""): UnifiedProgressSnapshot {
        val now = System.currentTimeMillis()
        downloadedBytes = bytes
        recordSample(now, bytes)

        val speed = calculateSpeed(now)
        val eta = calculateEta(bytes, totalExpectedBytes, speed)

        return UnifiedProgressSnapshot(
            downloadedBytes = bytes,
            totalBytes = totalExpectedBytes,
            bytesPerSecond = speed,
            segmentProgress = totalExpectedSegments?.let { completedSegments to it },
            totalDurationSeconds = totalDurationSeconds,
            etaSeconds = eta,
            detail = detail
        )
    }

    @Synchronized
    fun updateSegments(completed: Int, currentBytes: Long = downloadedBytes, detail: String = ""): UnifiedProgressSnapshot {
        val now = System.currentTimeMillis()
        completedSegments = completed
        downloadedBytes = currentBytes
        recordSample(now, currentBytes)

        val speed = calculateSpeed(now)
        val eta = if (totalExpectedSegments != null && totalExpectedSegments > 0 && completed > 0) {
            val elapsedSec = (now - startTimeMs) / 1000.0
            val secPerSegment = elapsedSec / completed
            val remainingSegments = totalExpectedSegments - completed
            (remainingSegments * secPerSegment).toLong().coerceAtLeast(0L)
        } else calculateEta(currentBytes, totalExpectedBytes, speed)

        return UnifiedProgressSnapshot(
            downloadedBytes = currentBytes,
            totalBytes = totalExpectedBytes,
            bytesPerSecond = speed,
            segmentProgress = totalExpectedSegments?.let { completed to it },
            totalDurationSeconds = totalDurationSeconds,
            etaSeconds = eta,
            detail = detail
        )
    }

    private fun recordSample(now: Long, bytes: Long) {
        windowSamples.add(now to bytes)
        while (windowSamples.size > 8 || (windowSamples.size > 2 && now - windowSamples.first().first > 3000)) {
            windowSamples.removeAt(0)
        }
    }

    private fun calculateSpeed(now: Long): Long? {
        if (windowSamples.size < 2) return null
        val first = windowSamples.first()
        val last = windowSamples.last()
        val timeDiff = (last.first - first.first) / 1000.0
        val bytesDiff = last.second - first.second
        if (timeDiff <= 0.1 || bytesDiff < 0) return null
        return (bytesDiff / timeDiff).toLong().coerceAtLeast(0L)
    }

    private fun calculateEta(bytes: Long, total: Long?, speed: Long?): Long? {
        if (total == null || total <= 0L || speed == null || speed <= 0L) return null
        val remaining = total - bytes
        return if (remaining > 0) remaining / speed else 0L
    }
}
