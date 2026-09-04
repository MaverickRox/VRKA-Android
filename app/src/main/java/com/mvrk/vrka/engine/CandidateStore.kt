/**
 * Bounded semantic candidate store with player source lineage.
 *
 * Ported faithfully from Desktop VRKA Build 017 vrka_core/candidates.py CandidateStore (lines 375-534).
 * This is the core of the candidate management pipeline: it ingests raw network
 * observations, deduplicates by canonical URL identity, collapses HLS/DASH segments
 * into their parent manifests, tracks player source lineage, and manages per-candidate
 * lifecycle states.
 *
 * Pure domain logic with no Android framework or UI dependency.
 */
package com.mvrk.vrka.engine

import java.security.MessageDigest

/**
 * Bounded semantic candidate store with player source lineage.
 *
 * Ported from Desktop CandidateStore (candidates.py:375-534).
 *
 * @param maxCandidates Maximum number of candidates before pruning (4..256).
 * @param maxEvidencePerCandidate Maximum evidence entries per candidate.
 * @param maxAgeSeconds Maximum age before a candidate is pruned.
 */
class CandidateStore(
    val maxCandidates: Int = 48,
    val maxEvidence: Int = 16,
    val maxAgeSeconds: Double = 1800.0,
) {
    init {
        require(maxCandidates in 4..256) { "Candidate bound must be between 4 and 256" }
    }

    private val items = LinkedHashMap<String, MediaCandidate>()
    private val playerCurrent = HashMap<String, String>()

    fun values(): List<MediaCandidate> = items.values.toList()

    fun get(candidateId: String): MediaCandidate? = items[candidateId]

    /**
     * True when the store contains any candidate that followed a user interaction.
     * Ported from Desktop _store_has_user_started_candidate (browser_fallback.py:500-506).
     */
    fun hasUserStartedCandidate(): Boolean = items.values.any { it.userStarted }

    /**
     * True when every stored candidate carries the generic live-widget URL signature.
     * Ported from Desktop _store_only_widget_shaped (browser_fallback.py:508-520).
     */
    fun storeOnlyWidgetShaped(): Boolean {
        val candidates = items.values
        if (candidates.isEmpty()) return false
        return candidates.all { looksLikeLiveWidgetUrl(it.currentUrl) }
    }

    /**
     * Ingest one network observation. Returns the created or updated candidate,
     * or null if the URL is not a media URL or is filtered.
     *
     * Ported from Desktop CandidateStore.observe() (candidates.py:394-471).
     */
    fun observe(
        url: String,
        contentType: String = "",
        timestamp: Double? = null,
        playerId: String = "",
        frameId: String = "",
        primaryPlayer: Boolean = false,
        nestedFrame: Boolean = false,
        popupContext: Boolean = false,
        segmentParentUrl: String = "",
        contentLength: Long? = null,
        playing: Boolean? = null,
        durationSeconds: Double? = null,
        width: Int? = null,
        height: Int? = null,
        userStarted: Boolean = false,
        nuisanceScore: Int = 0,
        requiredHeaders: Map<String, String> = emptyMap(),
    ): MediaCandidate? {
        val now = timestamp ?: (System.nanoTime() / 1_000_000_000.0)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        // Segments collapse into their parent manifest
        if (isSegment(url, contentType)) {
            val collapsed = observeSegment(segmentParentUrl, playerId, now, contentLength, segmentUrl = url)
            if (collapsed != null) return collapsed
        }

        val kind = mediaKind(url, contentType)
        if (kind == CandidateKind.OTHER) return null

        val identity = canonicalMediaIdentity(url, kind)
        var candidate = items.values.firstOrNull { it.canonicalIdentity == identity }
        val created = candidate == null

        if (candidate == null) {
            val candidateId = "mc_" + blake2sShort(
                "$identity|$now|${items.size}", 8
            )
            candidate = MediaCandidate(
                candidateId = candidateId,
                canonicalIdentity = identity,
                currentUrl = url,
                kind = kind,
                firstSeen = now,
                lastSeen = now,
            )
            items[candidateId] = candidate
        }

        // Refresh an expired/signed transfer URL in place
        candidate.currentUrl = url
        candidate.contentType = contentType.ifEmpty { candidate.contentType }
        candidate.lastSeen = now
        candidate.requestCount += 1
        if (contentLength != null) candidate.bytesObserved += contentLength
        if (durationSeconds != null) candidate.durationSeconds = durationSeconds
        if (width != null) candidate.width = width
        if (height != null) candidate.height = height

        if (requiredHeaders.isNotEmpty()) {
            candidate.requiredHeaders.putAll(requiredHeaders)
        }
        candidate.playerId = playerId.ifEmpty { candidate.playerId }
        candidate.frameId = frameId.ifEmpty { candidate.frameId }
        candidate.primaryPlayer = primaryPlayer || candidate.primaryPlayer
        candidate.nestedFrame = nestedFrame
        candidate.popupContext = popupContext
        candidate.userStarted = userStarted || candidate.userStarted
        candidate.nuisanceScore = maxOf(candidate.nuisanceScore, nuisanceScore)
        if (contentLength != null && contentLength > 0) {
            candidate.bytesObserved = maxOf(candidate.bytesObserved, contentLength)
        }

        if (playing != null) {
            if (playing && !candidate.playing) {
                candidate.playbackStartedAt = now
            }
            if (!playing && candidate.playing && candidate.playbackStartedAt != null) {
                candidate.sustainedPlaybackSeconds += maxOf(
                    0.0, now - (candidate.playbackStartedAt ?: now)
                )
                candidate.playbackStartedAt = null
            }
            candidate.playing = playing
            candidate.lifecycle = if (playing) CandidateLifecycle.PLAYING else CandidateLifecycle.PLAYABLE
        } else if (created) {
            candidate.lifecycle = CandidateLifecycle.PLAYABLE
        }

        addEvidence(candidate, "observed", kind.value)

        // Player source lineage tracking
        if (playerId.isNotEmpty()) {
            val previousId = playerCurrent[playerId]
            if (previousId != null && previousId != candidate.candidateId) {
                val previous = items[previousId]
                if (previous != null && !previous.userSelected) {
                    previous.replacedBy = candidate.candidateId
                    previous.lifecycle = CandidateLifecycle.REPLACED
                    candidate.sourceLineage =
                        (previous.sourceLineage + previous.candidateId).takeLast(8)
                    addEvidence(previous, "replaced", candidate.candidateId)
                    addEvidence(candidate, "source_replacement", previous.candidateId)
                }
            }
            playerCurrent[playerId] = candidate.candidateId
        }

        prune(now)
        return candidate
    }

    /**
     * Collapse a segment observation into its parent manifest.
     * Ported from Desktop CandidateStore._observe_segment() (candidates.py:473-490).
     */
    private fun observeSegment(
        manifestUrl: String,
        playerId: String,
        now: Double,
        contentLength: Long?,
        segmentUrl: String = "",
    ): MediaCandidate? {
        var candidate: MediaCandidate? = null
        if (manifestUrl.isNotEmpty()) {
            val identity = canonicalMediaIdentity(manifestUrl, mediaKind(manifestUrl))
            candidate = items.values.firstOrNull { it.canonicalIdentity == identity }
        }
        if (candidate == null && playerId.isNotEmpty()) {
            candidate = items[playerCurrent[playerId] ?: ""]
        }
        // Port Desktop _segment_parent_url: match segment stem / directory against observed manifests
        if (candidate == null && segmentUrl.isNotEmpty()) {
            candidate = findParentManifestForSegment(segmentUrl)
        }
        if (candidate != null && candidate.kind in setOf(CandidateKind.HLS, CandidateKind.DASH)) {
            candidate.segmentCount += 1
            candidate.requestCount += 1
            candidate.lastSeen = now
            if (contentLength != null && contentLength > 0) {
                candidate.bytesObserved += contentLength
            }
            addEvidence(candidate, "segment", "")
        }
        return candidate
    }

    /**
     * Correlate a sequence-numbered or codec-shaped segment to an observed parent manifest.
     * Ported from Desktop _manifest_stems and _segment_parent_url (vrka_downloader.py:1853-1905).
     */
    fun findParentManifestForSegment(segmentUrl: String): MediaCandidate? {
        val segmentPath = try { java.net.URI(segmentUrl).path ?: "" } catch (_: Exception) { return null }
        val segmentStem = segmentPath.substringAfterLast("/").substringBeforeLast(".")
        if (segmentStem.isEmpty()) return null
        val segmentDir = if (segmentPath.contains("/")) segmentPath.substringBeforeLast("/") else ""

        val manifests = items.values.filter { it.kind in setOf(CandidateKind.HLS, CandidateKind.DASH) }
        if (manifests.isEmpty()) return null

        // 1. Strict match: directory matches and segment stem starts with manifest stem + "_"
        for (manifest in manifests) {
            val mPath = try { java.net.URI(manifest.currentUrl).path ?: "" } catch (_: Exception) { continue }
            val mStem = mPath.substringAfterLast("/").substringBeforeLast(".")
            val mDir = if (mPath.contains("/")) mPath.substringBeforeLast("/") else ""
            if (segmentDir == mDir && (segmentStem == mStem || segmentStem.startsWith("${mStem}_"))) {
                return manifest
            }
        }

        // 2. Loose match: shared prefix before first "_"
        val segmentPrefix = if (segmentStem.contains("_")) segmentStem.substringBefore("_") else ""
        if (segmentPrefix.isNotEmpty()) {
            for (manifest in manifests) {
                val mPath = try { java.net.URI(manifest.currentUrl).path ?: "" } catch (_: Exception) { continue }
                val mStem = mPath.substringAfterLast("/").substringBeforeLast(".")
                val mDir = if (mPath.contains("/")) mPath.substringBeforeLast("/") else ""
                val mPrefix = if (mStem.contains("_")) mStem.substringBefore("_") else ""
                if (segmentDir == mDir && segmentPrefix == mPrefix) {
                    return manifest
                }
            }
        }
        return null
    }

    private fun addEvidence(candidate: MediaCandidate, event: String, detail: String) {
        candidate.evidence.add(
            CandidateEvidence(candidate.lastSeen, event, detail.take(80))
        )
        if (candidate.evidence.size > maxEvidence) {
            candidate.evidence.subList(0, candidate.evidence.size - maxEvidence).clear()
        }
    }

    /**
     * Mark a candidate as explicitly selected by the user.
     * Ported from Desktop CandidateStore.select() (candidates.py:497-502).
     */
    fun select(candidateId: String): MediaCandidate {
        val candidate = items[candidateId]
            ?: throw NoSuchElementException("Candidate $candidateId not found")
        candidate.userSelected = true
        candidate.lifecycle = CandidateLifecycle.SELECTED
        addEvidence(candidate, "user_selected", "")
        return candidate
    }

    /**
     * Record the outcome of a handoff attempt.
     * Ported from Desktop CandidateStore.mark_handoff() (candidates.py:504-509).
     */
    fun markHandoff(candidateId: String, success: Boolean) {
        val candidate = items[candidateId]
            ?: throw NoSuchElementException("Candidate $candidateId not found")
        candidate.lifecycle = if (success) {
            CandidateLifecycle.HANDED_OFF
        } else {
            CandidateLifecycle.FAILED_HANDOFF
        }
        addEvidence(candidate, if (success) "handoff_committed" else "handoff_failed", "")
    }

    /**
     * Remove stale and excess candidates.
     * Ported from Desktop CandidateStore.prune() (candidates.py:511-533).
     */
    fun prune(now: Double? = null) {
        val current = now ?: (System.nanoTime() / 1_000_000_000.0)

        // Remove stale candidates
        val stale = items.values.filter { candidate ->
            current - candidate.lastSeen > maxAgeSeconds &&
                    candidate.lifecycle !in setOf(
                CandidateLifecycle.SELECTED,
                CandidateLifecycle.HANDED_OFF,
            )
        }
        stale.forEach { items.remove(it.candidateId) }

        // Capacity pruning
        if (items.size <= maxCandidates) return
        val retention = items.values.sortedWith(
            compareByDescending<MediaCandidate> { it.userSelected }
                .thenByDescending {
                    it.lifecycle !in setOf(
                        CandidateLifecycle.REPLACED,
                        CandidateLifecycle.EXPIRED,
                        CandidateLifecycle.REJECTED,
                    )
                }
                .thenByDescending { it.lastSeen }
        ).take(maxCandidates)

        val kept = retention.map { it.candidateId }.toSet()
        items.keys.retainAll(kept)
    }

    /**
     * Clear all candidates and player tracking.
     */
    fun clear() {
        items.clear()
        playerCurrent.clear()
    }

    /**
     * Produce a BLAKE2s-equivalent short hash (using SHA-256 truncated for compatibility).
     */
    private fun blake2sShort(input: String, bytes: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.take(bytes).joinToString("") { "%02x".format(it) }
    }
}
