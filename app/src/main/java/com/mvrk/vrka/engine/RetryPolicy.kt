/**
 * Bounded automatic retry policy for transient direct-extraction failures.
 * Pure domain logic with no Android framework dependency.
 */
package com.mvrk.vrka.engine

import com.mvrk.vrka.JobState

/**
 * Describes whether a failed direct attempt may be repeated automatically.
 */
data class RetryDecision(
    val retryable: Boolean,
    val reason: String = "",
)

/**
 * Bounded, capped exponential backoff with jitter.
 *
 * Attempt numbering starts at 1 for the first retry (the original attempt is 0).
 * The delay is capped so a flaky network can never stall a job indefinitely, and
 * jitter prevents several queued jobs from retrying in lockstep.
 */
object RetryPolicy {
    /** Total direct attempts per job, including the initial one. */
    const val MAX_TOTAL_ATTEMPTS = 3

    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 20_000L
    const val JITTER_FRACTION = 0.25

    private val PERMANENT_DNS_MARKERS = listOf(
        "name or service not known",
        "nodename nor servname",
        "no address associated with hostname",
        "getaddrinfo failed",
        "unknown host",
        "name_not_resolved",
    )

    private val PERMANENT_HTTP_FAILURES = listOf(
        "401",
        "403",
        "404",
        "410",
        "forbidden",
        "unauthorized",
        "not found",
        "gone",
    )

    private val TRANSIENT_NETWORK_MARKERS = listOf(
        "connection reset",
        "connection refused",
        "connection aborted",
        "reset by peer",
        "broken pipe",
        "eof occurred",
        "incompleteread",
        "incomplete read",
        "temporarily unavailable",
        "service unavailable",
        "bad gateway",
        "gateway timeout",
        "too many requests",
        "rate limit",
        "timed out",
        "timeout",
        "network is unreachable",
        "socket",
    )

    fun delayForRetry(retryNumber: Int, random: () -> Double = { Math.random() }): Long {
        val exponent = (retryNumber - 1).coerceAtLeast(0).coerceAtMost(10)
        val raw = BASE_DELAY_MS shl exponent
        val capped = raw.coerceAtMost(MAX_DELAY_MS)
        val jittered = capped * (1.0 + JITTER_FRACTION * (random().coerceIn(0.0, 1.0) * 2.0 - 1.0))
        return jittered.toLong().coerceIn(0L, MAX_DELAY_MS)
    }

    /**
     * A failure is retryable only when nothing was transferred and the error is
     * plausibly temporary. Permanent rejections are left to the browser fallback
     * or reported to the user immediately.
     */
    fun decide(
        category: FailureCategory,
        errorMessage: String,
        transferStarted: Boolean,
        hasResolvedMediaUrl: Boolean,
    ): RetryDecision {
        if (transferStarted || hasResolvedMediaUrl) {
            return RetryDecision(false, "transfer already started")
        }
        val lower = errorMessage.lowercase()
        return when (category) {
            FailureCategory.NETWORK_ERROR,
            FailureCategory.TIMEOUT,
            -> if (PERMANENT_DNS_MARKERS.any(lower::contains)) {
                RetryDecision(false, "host could not be resolved")
            } else {
                RetryDecision(true, "temporary network failure")
            }

            FailureCategory.HTTP -> when {
                PERMANENT_HTTP_FAILURES.any(lower::contains) ->
                    RetryDecision(false, "permanent HTTP rejection")
                TRANSIENT_NETWORK_MARKERS.any(lower::contains) ||
                    Regex("""\b(408|425|429|5\d\d)\b""").containsMatchIn(lower) ->
                    RetryDecision(true, "transient HTTP failure")
                else -> RetryDecision(false, "HTTP failure is not transient")
            }

            FailureCategory.CLOUDFLARE ->
                RetryDecision(true, "bot challenge may be transient")

            else -> RetryDecision(false, "not a transient failure")
        }
    }
}

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
