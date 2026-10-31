/**
 * Terminal-state guards shared by the download manager.
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.engine

import com.mvrk.vrka.JobState

/**
 * Guard against late callbacks mutating jobs that already reached a terminal state.
 */
object JobStateGuard {
    /**
     * A requested state transition is applied only when the current job is live,
     * or when it is an idempotent repeat of the current terminal state.
     */
    fun allowsStateChange(current: JobState, requested: JobState?): Boolean {
        if (!current.isTerminal) return true
        return requested != null && requested == current
    }

    /**
     * Non-state updates (progress, title, speed) are only meaningful while a job is live.
     */
    fun allowsMetadataUpdate(current: JobState): Boolean = !current.isTerminal
}
