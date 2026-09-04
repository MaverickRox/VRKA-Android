package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for EngineStateMachine.
 * Verifies all 18 states, transition guards, and terminal detection.
 */
class EngineStateMachineTest {

    @Test
    fun `initial state is QUEUED`() {
        val sm = EngineStateMachine("task-1")
        assertEquals(EngineDownloadState.QUEUED, sm.state)
        assertEquals(0, sm.sequence)
        assertFalse(sm.isTerminal)
    }

    @Test
    fun `valid transition increments sequence`() {
        val sm = EngineStateMachine("task-1")
        val seq = sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        assertEquals(1, seq)
        assertEquals(EngineDownloadState.DIRECT_ATTEMPT, sm.state)
        assertEquals(1, sm.attempts) // DIRECT_ATTEMPT increments attempts
    }

    @Test
    fun `same-state transition is idempotent`() {
        val sm = EngineStateMachine("task-1")
        sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        val seq = sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        assertEquals(1, seq) // No change
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid transition throws`() {
        val sm = EngineStateMachine("task-1")
        // Cannot go from QUEUED to COMPLETED directly
        sm.transition(EngineDownloadState.COMPLETED)
    }

    @Test
    fun `complete happy path direct download`() {
        val sm = EngineStateMachine("task-1")
        sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        sm.transition(EngineDownloadState.DOWNLOAD_RUNNING)
        sm.transition(EngineDownloadState.POST_PROCESSING)
        sm.transition(EngineDownloadState.COMPLETED)
        assertTrue(sm.isTerminal)
        assertEquals(4, sm.sequence)
    }

    @Test
    fun `complete browser fallback path`() {
        val sm = EngineStateMachine("task-1")
        sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        sm.transition(EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK)
        sm.transition(EngineDownloadState.BROWSER_STARTING)
        sm.transition(EngineDownloadState.BROWSER_WAITING_FOR_MEDIA)
        sm.transition(EngineDownloadState.BROWSER_STABILIZING_CANDIDATES)
        sm.transition(EngineDownloadState.HANDOFF_PREPARING)
        sm.transition(EngineDownloadState.HANDOFF_VALIDATING)
        sm.transition(EngineDownloadState.DOWNLOADER_RESUMED)
        sm.transition(EngineDownloadState.DOWNLOAD_RUNNING)
        sm.transition(EngineDownloadState.POST_PROCESSING)
        sm.transition(EngineDownloadState.COMPLETED)
        assertTrue(sm.isTerminal)
        assertEquals(2, sm.attempts) // DIRECT_ATTEMPT + HANDOFF_VALIDATING
    }

    @Test
    fun `cancellation from any non-terminal state`() {
        for (state in EngineDownloadState.entries) {
            if (state.isTerminal) continue
            val sm = EngineStateMachine("task-cancel")
            // Navigate to this state (find a path)
            navigateToState(sm, state)
            // Should be able to cancel
            sm.transition(EngineDownloadState.CANCELLED)
            assertTrue(sm.isTerminal)
        }
    }

    @Test
    fun `failure from any non-terminal state`() {
        for (state in EngineDownloadState.entries) {
            if (state.isTerminal) continue
            val sm = EngineStateMachine("task-fail")
            navigateToState(sm, state)
            sm.transition(EngineDownloadState.FAILED)
            assertTrue(sm.isTerminal)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `terminal states cannot transition`() {
        val sm = EngineStateMachine("task-done")
        sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        sm.transition(EngineDownloadState.COMPLETED)
        // Should throw
        sm.transition(EngineDownloadState.QUEUED)
    }

    @Test
    fun `fallback recovering allows retry to browser`() {
        val sm = EngineStateMachine("task-retry")
        sm.transition(EngineDownloadState.DIRECT_ATTEMPT)
        sm.transition(EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK)
        sm.transition(EngineDownloadState.BROWSER_STARTING)
        sm.transition(EngineDownloadState.BROWSER_WAITING_FOR_MEDIA)
        sm.transition(EngineDownloadState.BROWSER_STABILIZING_CANDIDATES)
        sm.transition(EngineDownloadState.HANDOFF_PREPARING)
        sm.transition(EngineDownloadState.HANDOFF_VALIDATING)
        // Candidate failed validation
        sm.transition(EngineDownloadState.FALLBACK_RECOVERING)
        // Try next candidate
        sm.transition(EngineDownloadState.HANDOFF_PREPARING)
        sm.transition(EngineDownloadState.HANDOFF_VALIDATING)
        sm.transition(EngineDownloadState.DOWNLOADER_RESUMED)
        sm.transition(EngineDownloadState.COMPLETED)
        assertTrue(sm.isTerminal)
        assertEquals(3, sm.attempts) // 1 direct + 2 handoff validations
    }

    @Test
    fun `state labels are human readable`() {
        assertEquals("Queued", EngineDownloadState.QUEUED.label)
        assertEquals("Downloading", EngineDownloadState.DOWNLOAD_RUNNING.label)
        assertEquals("Waiting for media", EngineDownloadState.BROWSER_WAITING_FOR_MEDIA.label)
        assertEquals("Complete", EngineDownloadState.COMPLETED.label)
    }

    @Test
    fun `isBrowserFallback identifies correct states`() {
        assertTrue(EngineDownloadState.BROWSER_STARTING.isBrowserFallback)
        assertTrue(EngineDownloadState.BROWSER_WAITING_FOR_MEDIA.isBrowserFallback)
        assertTrue(EngineDownloadState.CANDIDATE_SELECTION_REQUIRED.isBrowserFallback)
        assertFalse(EngineDownloadState.DIRECT_ATTEMPT.isBrowserFallback)
        assertFalse(EngineDownloadState.DOWNLOAD_RUNNING.isBrowserFallback)
    }

    /**
     * Navigate a state machine to a target state via a shortest valid path.
     */
    private fun navigateToState(sm: EngineStateMachine, target: EngineDownloadState) {
        if (sm.state == target) return
        val path = findPath(sm.state, target) ?: error("No path from ${sm.state} to $target")
        for (step in path) {
            sm.transition(step)
        }
    }

    private fun findPath(
        from: EngineDownloadState,
        to: EngineDownloadState,
    ): List<EngineDownloadState>? {
        if (from == to) return emptyList()
        val visited = mutableSetOf(from)
        val queue = ArrayDeque<Pair<EngineDownloadState, List<EngineDownloadState>>>()
        queue.add(from to emptyList())
        while (queue.isNotEmpty()) {
            val (current, path) = queue.removeFirst()
            for (next in ENGINE_TRANSITIONS[current] ?: emptySet()) {
                if (next == to) return path + next
                if (next !in visited && !next.isTerminal) {
                    visited.add(next)
                    queue.add(next to path + next)
                }
            }
        }
        return null
    }
}
