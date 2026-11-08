package com.mvrk.vrka

/**
 * Identity check for a best-effort Browser Fallback estimate. A candidate estimate may only
 * be applied while the job still points at the same candidate and attempt; a replaced or
 * expired candidate must never inherit an older candidate's size.
 */
internal object FallbackEstimateIdentity {
    fun stillApplies(job: DownloadJob?, attempt: Int, mediaUrl: String): Boolean {
        if (job == null || job.state.isTerminal) return false
        return job.attempt == attempt && job.request.resolvedMediaUrl == mediaUrl
    }
}
