/**
 * Explicit download state machine with 18 states and transition guards.
 *
 * Ported faithfully from Desktop VRKA Build 017 vrka_core/candidates.py
 * DownloadState (lines 42-61), DownloadStateMachine (lines 177-199),
 * and _TRANSITIONS (lines 70-174).
 *
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.engine

/**
 * Complete download state enumeration with 18 states.
 *
 * Ported from Desktop DownloadState (candidates.py:42-61).
 */
enum class EngineDownloadState(val value: String) {
    QUEUED("queued"),
    DIRECT_ATTEMPT("direct_attempt"),
    DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK("direct_failed_eligible_for_fallback"),
    BROWSER_STARTING("browser_starting"),
    BROWSER_WAITING_FOR_MEDIA("browser_waiting_for_media"),
    BROWSER_INTERACTION_REQUIRED("browser_interaction_required"),
    BROWSER_STABILIZING_CANDIDATES("browser_stabilizing_candidates"),
    CANDIDATE_SELECTION_REQUIRED("candidate_selection_required"),
    HANDOFF_PREPARING("handoff_preparing"),
    HANDOFF_VALIDATING("handoff_validating"),
    BROWSER_CONTEXT_TRANSFER("browser_context_transfer"),
    DOWNLOADER_RESUMED("downloader_resumed"),
    DOWNLOAD_RUNNING("download_running"),
    POST_PROCESSING("post_processing"),
    FALLBACK_RECOVERING("fallback_recovering"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean
        get() = this in TERMINAL_ENGINE_STATES

    /** True when the state represents active foreground work (for notification/service). */
    val isForegroundWork: Boolean
        get() = this in setOf(
            DIRECT_ATTEMPT, DOWNLOAD_RUNNING, POST_PROCESSING,
            HANDOFF_VALIDATING, BROWSER_CONTEXT_TRANSFER,
        )

    /** True when the state represents active browser fallback. */
    val isBrowserFallback: Boolean
        get() = this in setOf(
            BROWSER_STARTING, BROWSER_WAITING_FOR_MEDIA,
            BROWSER_INTERACTION_REQUIRED, BROWSER_STABILIZING_CANDIDATES,
            CANDIDATE_SELECTION_REQUIRED,
        )

    /** Human-readable label for UI presentation. */
    val label: String
        get() = when (this) {
            QUEUED -> "Queued"
            DIRECT_ATTEMPT -> "Extracting"
            DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK -> "Preparing fallback"
            BROWSER_STARTING -> "Starting browser"
            BROWSER_WAITING_FOR_MEDIA -> "Waiting for media"
            BROWSER_INTERACTION_REQUIRED -> "Interaction required"
            BROWSER_STABILIZING_CANDIDATES -> "Stabilizing"
            CANDIDATE_SELECTION_REQUIRED -> "Selection required"
            HANDOFF_PREPARING -> "Preparing handoff"
            HANDOFF_VALIDATING -> "Validating transfer"
            BROWSER_CONTEXT_TRANSFER -> "Browser transfer"
            DOWNLOADER_RESUMED -> "Resuming download"
            DOWNLOAD_RUNNING -> "Downloading"
            POST_PROCESSING -> "Finalizing"
            FALLBACK_RECOVERING -> "Recovering"
            COMPLETED -> "Complete"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
        }

    companion object {
        fun fromValue(value: String): EngineDownloadState =
            entries.firstOrNull { it.value == value } ?: QUEUED
    }
}

val TERMINAL_ENGINE_STATES = setOf(
    EngineDownloadState.COMPLETED,
    EngineDownloadState.FAILED,
    EngineDownloadState.CANCELLED,
)

/**
 * Explicit transition table.
 *
 * Ported from Desktop _TRANSITIONS (candidates.py:70-174).
 * Cancellation and failure can happen from every non-terminal state.
 */
private val BASE_TRANSITIONS: Map<EngineDownloadState, Set<EngineDownloadState>> = mapOf(
    EngineDownloadState.QUEUED to setOf(
        EngineDownloadState.DIRECT_ATTEMPT,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.DIRECT_ATTEMPT to setOf(
        EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK,
        EngineDownloadState.DOWNLOAD_RUNNING,
        EngineDownloadState.COMPLETED,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK to setOf(
        EngineDownloadState.BROWSER_STARTING,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.BROWSER_STARTING to setOf(
        EngineDownloadState.BROWSER_WAITING_FOR_MEDIA,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.BROWSER_WAITING_FOR_MEDIA to setOf(
        EngineDownloadState.BROWSER_INTERACTION_REQUIRED,
        EngineDownloadState.BROWSER_STABILIZING_CANDIDATES,
        EngineDownloadState.HANDOFF_PREPARING,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.BROWSER_INTERACTION_REQUIRED to setOf(
        EngineDownloadState.BROWSER_WAITING_FOR_MEDIA,
        EngineDownloadState.BROWSER_STABILIZING_CANDIDATES,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.BROWSER_STABILIZING_CANDIDATES to setOf(
        EngineDownloadState.BROWSER_WAITING_FOR_MEDIA,
        EngineDownloadState.CANDIDATE_SELECTION_REQUIRED,
        EngineDownloadState.HANDOFF_PREPARING,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.CANDIDATE_SELECTION_REQUIRED to setOf(
        EngineDownloadState.HANDOFF_PREPARING,
        EngineDownloadState.BROWSER_WAITING_FOR_MEDIA,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.HANDOFF_PREPARING to setOf(
        EngineDownloadState.HANDOFF_VALIDATING,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.HANDOFF_VALIDATING to setOf(
        EngineDownloadState.DOWNLOADER_RESUMED,
        EngineDownloadState.BROWSER_CONTEXT_TRANSFER,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.BROWSER_CONTEXT_TRANSFER to setOf(
        EngineDownloadState.DOWNLOADER_RESUMED,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.DOWNLOADER_RESUMED to setOf(
        EngineDownloadState.DOWNLOAD_RUNNING,
        EngineDownloadState.POST_PROCESSING,
        EngineDownloadState.COMPLETED,
        EngineDownloadState.FALLBACK_RECOVERING,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.DOWNLOAD_RUNNING to setOf(
        EngineDownloadState.POST_PROCESSING,
        EngineDownloadState.COMPLETED,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.POST_PROCESSING to setOf(
        EngineDownloadState.COMPLETED,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.FALLBACK_RECOVERING to setOf(
        EngineDownloadState.BROWSER_STARTING,
        EngineDownloadState.BROWSER_WAITING_FOR_MEDIA,
        EngineDownloadState.BROWSER_STABILIZING_CANDIDATES,
        EngineDownloadState.CANDIDATE_SELECTION_REQUIRED,
        EngineDownloadState.HANDOFF_PREPARING,
        EngineDownloadState.FAILED,
        EngineDownloadState.CANCELLED,
    ),
    EngineDownloadState.COMPLETED to emptySet(),
    EngineDownloadState.FAILED to emptySet(),
    EngineDownloadState.CANCELLED to emptySet(),
)

/**
 * Computed transition table with FAILED and CANCELLED reachable from every
 * non-terminal state, preventing executor exceptions from stranding the worker.
 *
 * Ported from Desktop _TRANSITIONS augmentation (candidates.py:166-174).
 */
val ENGINE_TRANSITIONS: Map<EngineDownloadState, Set<EngineDownloadState>> =
    BASE_TRANSITIONS.mapValues { (state, targets) ->
        if (state !in TERMINAL_ENGINE_STATES) {
            targets + setOf(EngineDownloadState.FAILED, EngineDownloadState.CANCELLED)
        } else {
            targets
        }
    }

/**
 * Explicit transition guard attached to exactly one logical task ID.
 *
 * Ported from Desktop DownloadStateMachine (candidates.py:177-199).
 */
class EngineStateMachine(
    val taskId: String,
    var state: EngineDownloadState = EngineDownloadState.QUEUED,
    var sequence: Int = 0,
    var attempts: Int = 0,
) {
    /**
     * Transition to a new state. Returns the new sequence number.
     * Throws [IllegalStateException] if the transition is not valid.
     */
    fun transition(target: EngineDownloadState): Int {
        if (target == state) return sequence

        val allowed = ENGINE_TRANSITIONS[state] ?: emptySet()
        if (target !in allowed) {
            throw IllegalStateException(
                "Invalid fallback transition: ${state.value} -> ${target.value}"
            )
        }

        state = target
        sequence += 1

        if (target in setOf(
                EngineDownloadState.DIRECT_ATTEMPT,
                EngineDownloadState.HANDOFF_VALIDATING,
            )
        ) {
            attempts += 1
        }

        return sequence
    }

    val isTerminal: Boolean
        get() = state.isTerminal
}
