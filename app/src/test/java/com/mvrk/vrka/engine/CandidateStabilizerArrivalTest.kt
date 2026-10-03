package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

class CandidateStabilizerArrivalTest {

    private val ranker = EngineCandidateRanker()

    @Test
    fun `StreamingUnity race - segment arriving first is penalized and master manifest wins`() {
        val now = 1000.0
        val store = CandidateStore()

        // 1. First arrival: a media segment from StreamingUnity
        val segmentUrl = "https://vividfalcon52.boats/embed/0001-0840.m4s"
        assertTrue("0001-0840.m4s must be identified as segment", isSegment(segmentUrl, "video/iso.segment"))

        val segmentCandidate = store.observe(
            url = segmentUrl,
            contentType = "video/iso.segment",
            timestamp = now,
            playing = true,
            userStarted = true
        )
        assertNotNull(segmentCandidate)

        // Score the segment candidate: penalty should dominate
        val segmentScore = ranker.score(segmentCandidate!!, now)
        assertTrue("Segment score should be negative due to segment penalty: ${segmentScore.score}", segmentScore.score < 0.0)

        // Decision with only segment should NOT select it (score is below minCandidateScore = 20.0)
        val decisionOnlySegment = ranker.decide(store.values(), now)
        assertNull("Penalized segment should never be selected as handoff candidate", decisionOnlySegment.selectedCandidateId)

        // 2. Second arrival: true HLS master manifest appears
        val masterUrl = "https://megap.shiora.top/hls/master.m3u8"
        assertTrue("master.m3u8 must be identified as master manifest", isMasterManifest(masterUrl))

        val masterCandidate = store.observe(
            url = masterUrl,
            contentType = "application/vnd.apple.mpegurl",
            timestamp = now + 1.5,
            primaryPlayer = true,
            playing = true,
            userStarted = true,
            width = 1920,
            height = 1080
        )
        assertNotNull(masterCandidate)

        // Simulate segment activity on master
        masterCandidate!!.segmentCount = 3
        masterCandidate.requestCount = 2

        val masterScore = ranker.score(masterCandidate, now + 3.0)
        assertTrue("Master manifest should have high positive score: ${masterScore.score}", masterScore.score > 100.0)

        // 3. Stabilization ranking evaluation: Master manifest clearly wins over segment
        val candidates = store.values()
        assertEquals(2, candidates.size)

        val finalDecision = ranker.decide(candidates, now + 4.0)
        assertEquals("Master manifest must be the selected candidate", masterCandidate.candidateId, finalDecision.selectedCandidateId)
        assertNotEquals(segmentCandidate.candidateId, finalDecision.selectedCandidateId)
    }

    @Test
    fun `master manifest outranks child variant playlist`() {
        val now = 2000.0
        val store = CandidateStore()

        val masterCandidate = store.observe(
            url = "https://example.com/hls/master.m3u8",
            contentType = "application/x-mpegurl",
            timestamp = now,
            primaryPlayer = true,
            playing = true
        )!!
        masterCandidate.segmentCount = 2

        val childCandidate = store.observe(
            url = "https://example.com/hls/720p.m3u8?rendition=720p",
            contentType = "application/x-mpegurl",
            timestamp = now,
            primaryPlayer = true,
            playing = true
        )!!
        childCandidate.segmentCount = 2

        val masterScored = ranker.score(masterCandidate, now + 2.0)
        val childScored = ranker.score(childCandidate, now + 2.0)

        assertTrue(
            "Master manifest score (${masterScored.score}) must exceed child rendition score (${childScored.score})",
            masterScored.score > childScored.score
        )
    }
}
