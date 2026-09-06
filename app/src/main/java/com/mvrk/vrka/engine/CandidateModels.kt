/**
 * Core candidate domain models for the VRKA download engine.
 * Pure domain logic with no Android framework, UI, or filesystem dependency.
 */
package com.mvrk.vrka.engine

/**
 * Classification of the media delivery mechanism.
 */
enum class CandidateKind(val value: String) {
    DIRECT("direct"),
    HLS("hls"),
    DASH("dash"),
    OTHER("other");

    companion object {
        fun fromValue(value: String): CandidateKind =
            entries.firstOrNull { it.value == value } ?: OTHER
    }
}

/**
 * Per-candidate lifecycle states.
 */
enum class CandidateLifecycle(val value: String) {
    OBSERVED("observed"),
    PLAYABLE("playable"),
    PLAYING("playing"),
    STABILIZING("stabilizing"),
    STABLE("stable"),
    REPLACED("replaced"),
    EXPIRED("expired"),
    REJECTED("rejected"),
    SELECTED("selected"),
    HANDED_OFF("handed_off"),
    FAILED_HANDOFF("failed_handoff");
}

/**
 * Evidence trail entry for candidate observation.
 */
data class CandidateEvidence(
    val timestamp: Double,
    val event: String,
    val safeDetail: String = "",
)

/**
 * A single media candidate observed during browser fallback.
 * Tracks observation, playback, and lifecycle state of a candidate stream.
 */
data class MediaCandidate(
    val candidateId: String,
    val canonicalIdentity: String,
    var currentUrl: String,
    val kind: CandidateKind,
    var contentType: String = "",
    var responseContentType: String = "",
    var playerId: String = "",
    var frameId: String = "",
    var primaryPlayer: Boolean = false,
    var nestedFrame: Boolean = false,
    var popupContext: Boolean = false,
    var firstSeen: Double = 0.0,
    var lastSeen: Double = 0.0,
    var requestCount: Int = 0,
    var segmentCount: Int = 0,
    var bytesObserved: Long = 0,
    var playing: Boolean = false,
    var playbackStartedAt: Double? = null,
    var sustainedPlaybackSeconds: Double = 0.0,
    var durationSeconds: Double? = null,
    var width: Int? = null,
    var height: Int? = null,
    var bitrate: Int? = null,
    var userStarted: Boolean = false,
    var userSelected: Boolean = false,
    var sourceLineage: List<String> = emptyList(),
    var replacedBy: String = "",
    var repeatedLoops: Int = 0,
    var expiresAt: Double? = null,
    var lifecycle: CandidateLifecycle = CandidateLifecycle.OBSERVED,
    var nuisanceScore: Int = 0,
    var requiredHeaders: MutableMap<String, String> = mutableMapOf(),
    var evidence: MutableList<CandidateEvidence> = mutableListOf(),
    var rankScore: Double = 0.0,
    var confidenceExplanation: List<String> = emptyList(),
) {
    /**
     * Return metadata safe for presentation, diagnostics, and History.
     */
    fun safeDict(): Map<String, Any?> {
        val host = try {
            java.net.URI(currentUrl).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        return mapOf(
            "candidate_id" to candidateId,
            "kind" to kind.value,
            "content_type" to (contentType.ifEmpty { responseContentType }),
            "duration_seconds" to durationSeconds,
            "width" to width,
            "height" to height,
            "first_seen" to firstSeen,
            "last_seen" to lastSeen,
            "playing" to playing,
            "stable" to (lifecycle == CandidateLifecycle.STABLE),
            "lifecycle" to lifecycle.value,
            "score" to rankScore,
            "confidence" to confidenceExplanation,
            "host" to host,
        )
    }
}

/**
 * Weighted scoring configuration for the CandidateRanker.
 */
data class RankingConfig(
    val userSelected: Double = 1000.0,
    val userStarted: Double = 70.0,
    val primaryPlayer: Double = 48.0,
    val playing: Double = 36.0,
    val sustainedPerSecond: Double = 2.0,
    val sustainedCap: Double = 55.0,
    val coherentManifest: Double = 34.0,
    val manifestKind: Double = 14.0,
    val masterManifest: Double = 40.0,
    val directKind: Double = 10.0,
    val stableSurvival: Double = 22.0,
    val sourceReplacement: Double = 30.0,
    val usefulDimensions: Double = 12.0,
    val completeContext: Double = 8.0,
    val nestedFrame: Double = -12.0,
    val popupContext: Double = -85.0,
    val replaced: Double = -145.0,
    val repeatedLoop: Double = -22.0,
    val nuisance: Double = -5.0,
    val expired: Double = -1000.0,
    val failedHandoff: Double = -240.0,
    val minCandidateScore: Double = 20.0,
    val unambiguousMargin: Double = 28.0,
    val quickStabilitySeconds: Double = 2.5,
    val ambiguousStabilitySeconds: Double = 5.0,
    val maximumStabilitySeconds: Double = 12.0,
)

/**
 * One scored candidate in a ranking pass.
 */
data class RankedCandidate(
    val candidateId: String,
    val score: Double,
    val reasons: List<String>,
)

/**
 * The output of a CandidateRanker.decide() evaluation.
 */
data class RankingDecision(
    val selectedCandidateId: String?,
    val ambiguousCandidateIds: List<String>,
    val ranked: List<RankedCandidate>,
    val waitSeconds: Double,
    val explanation: String,
)

/**
 * Immutable downloader context transferred from the browser session.
 */
data class HandoffBundle(
    val taskId: String,
    val candidateId: String,
    val mediaUrl: String,
    val mediaKind: CandidateKind,
    val userAgent: String = "",
    val referer: String = "",
    val origin: String = "",
    val cookies: List<Map<String, String>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val expectedContentTypes: List<String> = emptyList(),
    val observedStatus: Int = 0,
    val observedContentType: String = "",
    val expectedDurationSeconds: Double = 0.0,
) {
    /**
     * Return metadata safe for logging. Never includes secret fields.
     */
    fun safeSummary(): Map<String, Any> {
        val host = try {
            java.net.URI(mediaUrl).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        return mapOf(
            "task_id" to taskId,
            "candidate_id" to candidateId,
            "media_kind" to mediaKind.value,
            "media_host" to host,
            "has_user_agent" to userAgent.isNotEmpty(),
            "has_referer" to referer.isNotEmpty(),
            "has_origin" to origin.isNotEmpty(),
            "cookie_count" to cookies.size,
            "header_names" to headers.keys.map { it.lowercase() }.sorted(),
        )
    }
}
