package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

class EngineCandidateRankerTest {

    private val ranker = EngineCandidateRanker()

    @Test
    fun `user selected candidate gets massive positive boost`() {
        val candidate = MediaCandidate(
            candidateId = "mc_1",
            canonicalIdentity = "ident_1",
            currentUrl = "https://example.com/stream.m3u8",
            kind = CandidateKind.HLS,
            firstSeen = 100.0,
            lastSeen = 100.0,
            userSelected = true,
        )
        val scored = ranker.score(candidate, 100.0)
        assertTrue("Score should exceed 1000.0", scored.score >= 1000.0)
    }

    @Test
    fun `master manifest gets boost over single direct stream without playback`() {
        val masterHls = MediaCandidate(
            candidateId = "mc_hls",
            canonicalIdentity = "ident_hls",
            currentUrl = "https://example.com/master.m3u8",
            kind = CandidateKind.HLS,
            firstSeen = 100.0,
            lastSeen = 100.0,
        )
        val direct = MediaCandidate(
            candidateId = "mc_direct",
            canonicalIdentity = "ident_direct",
            currentUrl = "https://example.com/video.mp4",
            kind = CandidateKind.DIRECT,
            firstSeen = 100.0,
            lastSeen = 100.0,
        )
        val scoredHls = ranker.score(masterHls, 100.0)
        val scoredDirect = ranker.score(direct, 100.0)
        assertTrue(scoredHls.score > scoredDirect.score)
    }

    @Test
    fun `popup context and nested frame apply negative penalties`() {
        val popupCandidate = MediaCandidate(
            candidateId = "mc_popup",
            canonicalIdentity = "ident_popup",
            currentUrl = "https://example.com/ad.mp4",
            kind = CandidateKind.DIRECT,
            firstSeen = 100.0,
            lastSeen = 100.0,
            popupContext = true,
            nestedFrame = true,
        )
        val scored = ranker.score(popupCandidate, 100.0)
        assertTrue("Score should be negative due to penalties", scored.score < 0.0)
    }

    @Test
    fun `decide returns wait when no candidate reaches minimum score`() {
        val lowScoreCandidate = MediaCandidate(
            candidateId = "mc_low",
            canonicalIdentity = "ident_low",
            currentUrl = "https://example.com/ad.mp4",
            kind = CandidateKind.DIRECT,
            firstSeen = 100.0,
            lastSeen = 100.0,
            popupContext = true,
        )
        val decision = ranker.decide(listOf(lowScoreCandidate), 100.0)
        assertNull("Selected candidate should be null for sub-threshold candidate", decision.selectedCandidateId)
        assertTrue("Wait seconds should be > 0", decision.waitSeconds > 0.0)
    }

    @Test
    fun `decide selects clear winner immediately when unambiguous and aged`() {
        val strongCandidate = MediaCandidate(
            candidateId = "mc_strong",
            canonicalIdentity = "ident_strong",
            currentUrl = "https://example.com/master.m3u8",
            kind = CandidateKind.HLS,
            firstSeen = 90.0,
            lastSeen = 100.0,
            userStarted = true,
            primaryPlayer = true,
            playing = true,
            segmentCount = 5,
        )
        val decision = ranker.decide(listOf(strongCandidate), 100.0)
        assertEquals("mc_strong", decision.selectedCandidateId)
        assertEquals(0.0, decision.waitSeconds, 0.001)
    }

    @Test
    fun `mandatory regression scenario - sidebar ad stream vs primary user video`() {
        // Step 1: Sidebar live-widget / ad video starts playing first automatically in nested frame
        val sidebarAdCandidate = MediaCandidate(
            candidateId = "mc_sidebar_ad",
            canonicalIdentity = "ident_ad",
            currentUrl = "https://live.example.com/streams/998877/widget_360p.m3u8",
            kind = CandidateKind.HLS,
            firstSeen = 50.0,
            lastSeen = 100.0,
            playing = true,
            userStarted = false,
            primaryPlayer = false,
            nestedFrame = true,
            nuisanceScore = 15, // Ad/widget penalty
            width = 300,
            height = 168, // Small video area
            segmentCount = 6,
        )

        // Step 2: User interacts with main page server selector and presses Play on primary video
        val primaryVideoCandidate = MediaCandidate(
            candidateId = "mc_primary_video",
            canonicalIdentity = "ident_main",
            currentUrl = "https://cdn.example.com/content/master.m3u8",
            kind = CandidateKind.HLS,
            firstSeen = 95.0,
            lastSeen = 100.0,
            playing = true,
            userStarted = true,
            primaryPlayer = true,
            nestedFrame = false,
            nuisanceScore = 0,
            width = 1920,
            height = 1080,
            segmentCount = 3,
        )

        val scoredAd = ranker.score(sidebarAdCandidate, 100.0)
        val scoredPrimary = ranker.score(primaryVideoCandidate, 100.0)

        // Primary candidate must massively outscore sidebar ad candidate
        assertTrue(
            "Primary video score (${scoredPrimary.score}) must exceed ad video score (${scoredAd.score}) by a large margin",
            scoredPrimary.score > scoredAd.score + 100.0
        )

        // Ranker decision must unambiguously pick primary video
        val decision = ranker.decide(listOf(sidebarAdCandidate, primaryVideoCandidate), 100.0)
        assertEquals("mc_primary_video", decision.selectedCandidateId)
    }
}
