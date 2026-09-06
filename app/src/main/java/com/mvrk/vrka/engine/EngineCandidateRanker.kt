/**
 * Deterministic candidate ranking engine.
 *
 * Uses 17 weighted scoring criteria with confidence margins, stabilization
 * windows, and ambiguity detection. Pure domain logic with no Android
 * framework dependency.
 */
package com.mvrk.vrka.engine

/**
 * Deterministic candidate ranking engine with 17 weighted criteria.
 *
 * The scoring formula per candidate C at timestamp t:
 *   Score(C) = Σ Wᵢ · 𝟙ᵢ
 */
class EngineCandidateRanker(
    private val config: RankingConfig = RankingConfig(),
) {
    /**
     * Score one candidate at the given timestamp.
     */
    fun score(candidate: MediaCandidate, now: Double): RankedCandidate {
        var totalScore = 0.0
        val reasons = mutableListOf<String>()

        fun add(value: Double, explanation: String) {
            if (value != 0.0) {
                totalScore += value
                val prefix = if (value > 0) "+" else ""
                reasons.add("$prefix${value} $explanation")
            }
        }

        // --- Positive signals ---
        add(
            if (candidate.userSelected) config.userSelected else 0.0,
            "explicit user selection"
        )
        add(
            if (candidate.userStarted) config.userStarted else 0.0,
            "playback followed user interaction"
        )
        add(
            if (candidate.primaryPlayer) config.primaryPlayer else 0.0,
            "linked to primary player"
        )
        add(
            if (candidate.playing) config.playing else 0.0,
            "currently playing"
        )

        // Sustained playback (2.0 per second, capped at 55.0)
        var sustained = candidate.sustainedPlaybackSeconds
        if (candidate.playing && candidate.playbackStartedAt != null) {
            sustained += maxOf(0.0, now - (candidate.playbackStartedAt ?: now))
        }
        add(
            minOf(sustained * config.sustainedPerSecond, config.sustainedCap),
            "sustained playback"
        )

        // Stream type bonuses
        if (candidate.kind in setOf(CandidateKind.HLS, CandidateKind.DASH)) {
            add(config.manifestKind, "${candidate.kind.value.uppercase()} manifest")
            if (isMasterManifest(candidate.currentUrl)) {
                add(config.masterManifest, "master manifest (best-quality selection)")
            }
            if (candidate.segmentCount >= 2) {
                add(config.coherentManifest, "coherent segment activity")
            }
        } else if (candidate.kind == CandidateKind.DIRECT) {
            add(config.directKind, "direct media response")
        }

        // Stability survival
        val survival = maxOf(0.0, now - candidate.firstSeen)
        if (survival >= config.quickStabilitySeconds && candidate.requestCount >= 2) {
            add(config.stableSurvival, "survived stabilization window")
        }

        // Source replacement lineage
        if (candidate.sourceLineage.isNotEmpty()) {
            add(config.sourceReplacement, "replaced an earlier source in the same player")
        }

        // Video dimensions
        val w = candidate.width
        val h = candidate.height
        if (w != null && h != null && w >= 320 && h >= 180) {
            add(config.usefulDimensions, "usable video dimensions")
        }

        // Required context headers observed
        if (candidate.requiredHeaders.isNotEmpty()) {
            add(config.completeContext, "required request context observed")
        }

        // --- Negative signals ---
        add(
            if (candidate.nestedFrame) config.nestedFrame else 0.0,
            "nested frame context"
        )
        add(
            if (candidate.popupContext) config.popupContext else 0.0,
            "popup context"
        )
        add(
            if (candidate.lifecycle == CandidateLifecycle.REPLACED) config.replaced else 0.0,
            "rapidly replaced source"
        )
        add(
            config.repeatedLoop * candidate.repeatedLoops,
            "repeated looping"
        )
        add(
            config.nuisance * candidate.nuisanceScore,
            "nuisance-adjacent request"
        )
        add(
            if (candidate.lifecycle == CandidateLifecycle.EXPIRED) config.expired else 0.0,
            "expired URL"
        )
        add(
            if (candidate.lifecycle == CandidateLifecycle.FAILED_HANDOFF) config.failedHandoff else 0.0,
            "previous validation failure"
        )

        return RankedCandidate(candidate.candidateId, totalScore, reasons.take(10))
    }

    /**
     * Evaluate all candidates and produce a ranking decision.
     *
     * Implements confidence margins, stabilization windows, and ambiguity detection.
     */
    fun decide(
        candidates: Iterable<MediaCandidate>,
        now: Double? = null,
    ): RankingDecision {
        val current = now ?: (System.nanoTime() / 1_000_000_000.0)
        val items = candidates.toList()
        val ranked = items
            .map { score(it, current) }
            .sortedWith(compareBy<RankedCandidate> { -it.score }.thenBy { it.candidateId })

        // Update candidate metadata with ranking results
        val byId = items.associateBy { it.candidateId }
        for (entry in ranked) {
            val candidate = byId[entry.candidateId] ?: continue
            candidate.rankScore = entry.score
            candidate.confidenceExplanation = entry.reasons
        }

        // --- Decision logic ---

        // No candidates or best score too low
        if (ranked.isEmpty() || ranked[0].score < config.minCandidateScore) {
            return RankingDecision(
                selectedCandidateId = null,
                ambiguousCandidateIds = emptyList(),
                ranked = ranked,
                waitSeconds = config.quickStabilitySeconds,
                explanation = "Waiting for stronger playback evidence.",
            )
        }

        val top = ranked[0]
        val topCandidate = byId[top.candidateId]!!

        // Explicit user selection → immediate
        if (topCandidate.userSelected) {
            return RankingDecision(
                selectedCandidateId = top.candidateId,
                ambiguousCandidateIds = emptyList(),
                ranked = ranked,
                waitSeconds = 0.0,
                explanation = "Selected explicitly by the user.",
            )
        }

        val margin = if (ranked.size > 1) top.score - ranked[1].score else top.score
        val age = maxOf(0.0, current - topCandidate.firstSeen)

        // Coherent activity check
        val coherent = when (topCandidate.kind) {
            CandidateKind.DIRECT -> topCandidate.requestCount >= 2
            CandidateKind.HLS, CandidateKind.DASH -> topCandidate.segmentCount >= 2
            else -> false
        }

        // Unambiguous leader with coherent activity
        if (margin >= config.unambiguousMargin && coherent) {
            val remaining = maxOf(0.0, config.quickStabilitySeconds - age)
            return RankingDecision(
                selectedCandidateId = if (remaining == 0.0) top.candidateId else null,
                ambiguousCandidateIds = emptyList(),
                ranked = ranked,
                waitSeconds = remaining,
                explanation = "The leading media has coherent activity and a clear confidence margin.",
            )
        }

        // Ambiguous — multiple plausible candidates
        val plausible = ranked
            .filter {
                it.score >= config.minCandidateScore &&
                        top.score - it.score < config.unambiguousMargin
            }
            .map { it.candidateId }

        val waitTarget = minOf(config.ambiguousStabilitySeconds, config.maximumStabilitySeconds)
        val remaining = maxOf(0.0, waitTarget - age)

        if (remaining > 0) {
            return RankingDecision(
                selectedCandidateId = null,
                ambiguousCandidateIds = plausible,
                ranked = ranked,
                waitSeconds = remaining,
                explanation = "Several media candidates are still stabilizing.",
            )
        }

        if (plausible.size > 1) {
            return RankingDecision(
                selectedCandidateId = null,
                ambiguousCandidateIds = plausible,
                ranked = ranked,
                waitSeconds = 0.0,
                explanation = "Multiple plausible videos remain; user selection is required.",
            )
        }

        return RankingDecision(
            selectedCandidateId = top.candidateId,
            ambiguousCandidateIds = emptyList(),
            ranked = ranked,
            waitSeconds = 0.0,
            explanation = "The best available candidate remained stable through the bounded window.",
        )
    }
}
