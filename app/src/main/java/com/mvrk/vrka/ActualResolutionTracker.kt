package com.mvrk.vrka

/**
 * Collects the actual video height reported by yt-dlp's `before_dl` marker for every
 * downloaded item. A playlist can contain mixed resolutions, so the last item must never
 * be presented as the resolution of the whole job.
 */
internal class ActualResolutionTracker {
    private val heights = LinkedHashSet<Int>()

    fun record(height: Int?) {
        if (height != null && height > 0) heights += height
    }

    fun record(heightText: String?) {
        record(heightText?.trim()?.toIntOrNull())
    }

    fun recordAll(values: Collection<Int>) {
        values.filter { it > 0 }.forEach(heights::add)
    }

    fun summary(isPlaylist: Boolean): String? {
        if (heights.isEmpty()) return null
        val min = heights.min()
        val max = heights.max()
        return when {
            !isPlaylist || min == max -> "${max}p"
            else -> "${min}p–${max}p"
        }
    }

    fun heights(): Set<Int> = heights.toSet()

    companion object {
        const val MARKER = "__VRKA_RES__"

        fun parseMarker(line: String): Int? {
            val index = line.indexOf(MARKER, ignoreCase = true)
            if (index < 0) return null
            val digits = line.substring(index + MARKER.length)
                .trim()
                .takeWhile(Char::isDigit)
            return digits.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
