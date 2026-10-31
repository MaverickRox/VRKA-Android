package com.mvrk.vrka

import java.security.MessageDigest

/**
 * Bounded, in-memory cache for metadata size preflights. The cache is keyed by everything that
 * can change yt-dlp's selected streams (source, format selection, trim, runtime, effective
 * request headers), so a stale response is never reused after the user changes an option.
 *
 * Header values are reduced to a digest inside the key and are never stored. Nothing here is
 * persisted to disk, so cookies and authentication headers stay out of the cache file system.
 */
class MetadataEstimateCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val unavailableTtlMs: Long = DEFAULT_UNAVAILABLE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val estimate: SizeEstimate, val storedAt: Long)

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(key: String): SizeEstimate? {
        val entry = entries[key] ?: return null
        val ttl = if (entry.estimate.isAvailable) ttlMs else unavailableTtlMs
        if (clock() - entry.storedAt > ttl) {
            entries.remove(key)
            return null
        }
        return entry.estimate
    }

    @Synchronized
    fun put(key: String, estimate: SizeEstimate) {
        if (estimate.detail == "custom-command") return
        entries[key] = Entry(estimate, clock())
        while (entries.size > maxEntries) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 16
        const val DEFAULT_TTL_MS = 10 * 60_000L
        const val DEFAULT_UNAVAILABLE_TTL_MS = 20_000L

        /**
         * The part of the key that changes only when yt-dlp would select different streams.
         * A cached metadata document keeps every available format, so this is all that is
         * needed to recalculate a size locally after the user changes quality or format.
         */
        fun formatKeyFor(request: DownloadRequest): String = buildString {
            append(request.mode)
            append('|').append(request.quality.height ?: "best")
            append('|').append(request.prefer60Fps)
            append('|').append(request.audioFormat)
            append('|').append(request.mp3Bitrate)
            append('|').append(request.h264CompatibilityMode)
            append('|').append(request.commandTemplateId.orEmpty())
        }

        fun keyFor(request: DownloadRequest, runtimeVersion: String): String = buildString {
            append(request.resolvedMediaUrl ?: request.url)
            append('|').append(formatKeyFor(request))
            append('|').append(request.isPlaylist)
            append('|').append(request.playlistStart ?: "")
            append('|').append(request.playlistEnd ?: "")
            append('|').append(request.trimStart.trim())
            append('|').append(request.trimEnd.trim())
            append('|').append(request.embedThumbnail)
            append('|').append(runtimeVersion)
            append('|').append(headersDigest(request))
        }

        internal fun headersDigest(request: DownloadRequest): String {
            val headers = runCatching { HeaderValidation.resolveEffectiveHeaders(request) }
                .getOrDefault(emptyMap())
            if (headers.isEmpty() && request.resolvedHeaders.isEmpty()) return ""
            val material = buildString {
                (headers.keys + request.resolvedHeaders.keys)
                    .map { it.lowercase(java.util.Locale.ROOT) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val value = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                            ?: request.resolvedHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value
                            ?: ""
                        append(name).append(':').append(value).append('\n')
                    }
            }
            return sha256(material)
        }

        private fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Bounded, in-memory cache of raw yt-dlp metadata documents.
 *
 * A document describes every available stream for a source, so it can answer size questions
 * for any quality or format selection without another network extraction. The key therefore
 * omits the format selector and trim range and stays stable across those UI changes; the
 * format key that produced the document is stored alongside it so callers know whether
 * `requested_formats` is trustworthy or the selection must be recalculated locally.
 *
 * Documents are never persisted, so signed media URLs and session data stay in memory only.
 * Header values are reduced to a digest inside the key and are never stored.
 */
class MetadataDocumentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Document(
        val json: String,
        /** [MetadataEstimateCache.formatKeyFor] that produced this document. */
        val sourceFormatKey: String,
        val storedAt: Long,
    )

    private val entries = LinkedHashMap<String, Document>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(key: String): Document? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAt > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, document: Document) {
        entries[key] = document
        while (entries.size > maxEntries) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8
        const val DEFAULT_TTL_MS = 10 * 60_000L

        fun keyFor(request: DownloadRequest, runtimeVersion: String): String = buildString {
            append(request.resolvedMediaUrl ?: request.url)
            append('|').append(request.isPlaylist)
            append('|').append(request.playlistStart ?: "")
            append('|').append(request.playlistEnd ?: "")
            append('|').append(runtimeVersion)
            append('|').append(MetadataEstimateCache.headersDigest(request))
        }
    }
}
