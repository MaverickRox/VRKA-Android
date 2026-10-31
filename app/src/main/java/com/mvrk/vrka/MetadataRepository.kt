package com.mvrk.vrka

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared metadata store for the download screen, the share card and the format selector.
 *
 * One yt-dlp extraction produces the complete document (every video and audio format plus
 * manual and automatic subtitles). Sizes for each selection are calculated locally from that
 * document, so changing quality never launches another process. Concurrent callers for the
 * same source join a single in-flight extraction, and the document is keyed by source,
 * playlist range, runtime version and a digest of the effective headers.
 *
 * The repository holds everything in memory. Signed media URLs, cookies and authentication
 * headers are never written to disk; header values only participate as a digest in the cache
 * key. [clear] drops both caches when the active runtime changes.
 */
class MetadataRepository(
    private val estimateCache: MetadataEstimateCache = MetadataEstimateCache(),
    private val documentCache: MetadataDocumentCache = MetadataDocumentCache(),
    private val probe: suspend (DownloadRequest) -> SizePreflight,
    private val ensureReady: () -> Unit = {},
    private val onFailure: suspend (DownloadRequest, SizePreflight) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Load(
        val document: MetadataDocumentCache.Document?,
        val failure: SizeEstimate?,
        val source: String,
        val probeDurationMs: Long? = null,
    )

    private data class Failure(val estimate: SizeEstimate, val storedAt: Long)

    private val inFlight = HashMap<String, CompletableDeferred<Load>>()

    // A failed extraction is reused for a short window so the metadata view and the estimate
    // do not each launch their own doomed process. Retry/forceRefresh always bypasses it.
    private val failures = LinkedHashMap<String, Failure>()

    suspend fun estimate(
        request: DownloadRequest,
        runtimeVersion: String,
        forceRefresh: Boolean = false,
        onLoad: (Load) -> Unit = {},
    ): SizeEstimate = withContext(Dispatchers.IO) {
        val estimateKey = MetadataEstimateCache.keyFor(request, runtimeVersion)
        if (!forceRefresh) {
            estimateCache.get(estimateKey)?.let {
                onLoad(Load(null, null, "estimate-cache"))
                return@withContext it
            }
        }
        val load = loadDocument(request, runtimeVersion, forceRefresh)
        val estimate = load.document
            ?.let {
                SizeEstimateCalculator.fromMetadataJson(
                    it.json,
                    request,
                    recalculateSelection = true,
                )
            }
            ?: load.failure
            ?: SizeEstimate.unavailable("process")
        estimateCache.put(estimateKey, estimate)
        onLoad(load)
        estimate
    }

    suspend fun snapshot(
        request: DownloadRequest,
        runtimeVersion: String,
        forceRefresh: Boolean = false,
        onLoad: (Load) -> Unit = {},
    ): MetadataSnapshot? = withContext(Dispatchers.IO) {
        val load = loadDocument(request, runtimeVersion, forceRefresh)
        onLoad(load)
        load.document?.let { SizeEstimateCalculator.snapshotFromMetadataJson(it.json, request) }
    }

    fun clear() {
        estimateCache.clear()
        documentCache.clear()
        synchronized(failures) { failures.clear() }
    }

    private suspend fun loadDocument(
        request: DownloadRequest,
        runtimeVersion: String,
        forceRefresh: Boolean,
    ): Load {
        val documentKey = MetadataDocumentCache.keyFor(request, runtimeVersion)
        if (!forceRefresh) {
            documentCache.get(documentKey)?.let { return Load(it, null, "document-cache") }
            synchronized(failures) {
                failures[documentKey]?.let { failure ->
                    if (clock() - failure.storedAt <= FAILURE_TTL_MS) {
                        return Load(null, failure.estimate, "failure-cache")
                    }
                    failures.remove(documentKey)
                }
            }
        }

        val (deferred, owner) = synchronized(inFlight) {
            val existing = inFlight[documentKey]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<Load>()
                    .also { inFlight[documentKey] = it } to true
            }
        }
        if (!owner) {
            // Join the running extraction instead of starting a second yt-dlp process.
            return deferred.await().copy(source = "shared-in-flight")
        }

        try {
            ensureReady()
            val preflight = probe(request)
            val document = preflight.document?.let {
                MetadataDocumentCache.Document(
                    json = it,
                    sourceFormatKey = MetadataEstimateCache.formatKeyFor(request),
                    storedAt = clock(),
                )
            }
            if (document != null) {
                documentCache.put(documentKey, document)
                synchronized(failures) { failures.remove(documentKey) }
            } else {
                synchronized(failures) {
                    failures[documentKey] = Failure(preflight.estimate, clock())
                    while (failures.size > MAX_FAILURES) {
                        failures.remove(failures.keys.first())
                    }
                }
                onFailure(request, preflight)
            }
            val load = Load(
                document = document,
                failure = if (document == null) preflight.estimate else null,
                source = if (document != null) "extraction" else "extraction-failed",
                probeDurationMs = preflight.durationMs,
            )
            deferred.complete(load)
            return load
        } catch (error: Throwable) {
            deferred.complete(Load(null, SizeEstimate.unavailable("error"), "extraction-failed"))
            throw error
        } finally {
            synchronized(inFlight) { inFlight.remove(documentKey) }
        }
    }

    private companion object {
        const val FAILURE_TTL_MS = 20_000L
        const val MAX_FAILURES = 16
    }
}
