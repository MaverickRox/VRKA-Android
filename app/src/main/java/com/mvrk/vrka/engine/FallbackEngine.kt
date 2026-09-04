/**
 * Automatic browser fallback orchestrator.
 *
 * Ported faithfully from Desktop VRKA Build 017 vrka_core/browser_fallback.py
 * ProtectedBrowserFallback (lines 268-614) and vrka_core/watchdog.py
 * AutomaticFallbackExecutor (lines 305-351).
 *
 * Lifecycle: created per fallback-eligible task, runs the bounded observation
 * loop, performs deterministic candidate ranking, and produces a HandoffBundle
 * for the downloader to resume the SAME logical task.
 *
 * This is an INTERNAL engine with NO user-facing UI. GeckoView is invoked
 * programmatically and never exposed as a browser destination.
 */
package com.mvrk.vrka.engine

import android.content.Context
import android.util.Log
import com.mvrk.vrka.GeckoRuntimeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for the fallback observation loop.
 * Ported from Desktop ProtectedBrowserFallback constructor defaults (browser_fallback.py:278-290, vrka_downloader.py:5640-5655).
 */
data class FallbackConfig(
    /** Maximum total seconds to wait for user interaction and media discovery (120s design maximum). */
    val interactionWaitSeconds: Double = 120.0,
    /** Maximum total seconds to wait for candidate stabilization. */
    val stabilizationWaitSeconds: Double = 12.0,
    /** Maximum number of candidate handoff attempts before failing. */
    val maxHandoffAttempts: Int = 3,
    /** Interval between observation polls (ms). */
    val pollIntervalMs: Long = 1000L,
    /** Seconds to delay after uBlock installation before navigating (superseded by deterministic ensureExtensionsReady gate). */
    val contentBlockingWarmupSeconds: Double = 0.0,
)

/** Result of the fallback engine execution. */
sealed class FallbackResult {
    data class Success(val bundle: HandoffBundle) : FallbackResult()
    data class Failed(val reason: String) : FallbackResult()
    data object Cancelled : FallbackResult()
}

/**
 * Per-task browser fallback orchestrator.
 *
 * Manages the full GeckoView lifecycle for one download task:
 * 1. Lazy GeckoView initialization
 * 2. uBlock warmup gate
 * 3. Page navigation
 * 4. Network/media observation via media-detector WebExtension
 * 5. Candidate collection into CandidateStore
 * 6. Bounded stabilization loop with widget-cluster avoidance
 * 7. Deterministic ranking via EngineCandidateRanker
 * 8. HandoffBundle construction & validation probe
 * 9. Cleanup
 */
class FallbackEngine(
    private val context: Context,
    val taskId: String,
    val targetUrl: String,
    private val config: FallbackConfig = FallbackConfig(),
) {
    private val store = CandidateStore()
    private val ranker = EngineCandidateRanker()
    private val stateMachine = EngineStateMachine(taskId, state = EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK)
    private val cancelled = AtomicBoolean(false)

    private val _engineState = MutableStateFlow(EngineDownloadState.DIRECT_FAILED_ELIGIBLE_FOR_FALLBACK)
    val engineState: StateFlow<EngineDownloadState> = _engineState.asStateFlow()

    private val _candidateCount = MutableStateFlow(0)
    val candidateCount: StateFlow<Int> = _candidateCount.asStateFlow()

    private val _activeSession = MutableStateFlow<GeckoSession?>(null)
    val activeSession: StateFlow<GeckoSession?> = _activeSession.asStateFlow()

    private val ingestedBridgeCandidateIds = HashSet<String>()
    private var session: GeckoSession? = null

    /** Cancel the fallback operation. Thread-safe. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Execute the browser fallback pipeline.
     *
     * Must be called from a coroutine on Dispatchers.IO or equivalent.
     * Returns a [FallbackResult] representing success, failure, or cancellation.
     */
    suspend fun execute(): FallbackResult {
        try {
            val result = doExecute()
            if (result !is FallbackResult.Success) {
                cleanup()
            }
            return result
        } catch (t: Throwable) {
            cleanup()
            throw t
        }
    }

    private suspend fun doExecute(): FallbackResult {
        checkCancelled()

        // Phase 1: Initialize GeckoView and verify extensions via deterministic readiness gate
        transition(EngineDownloadState.BROWSER_STARTING)
        val runtimeManager = GeckoRuntimeManager.getInstance(context)

        // Semantic readiness gate: Fallback must NOT navigate to the target URL until the
        // required uBlock and Media Detector initialization has completed and permissions
        // in private browsing are active.
        val extensionsReady = runtimeManager.ensureExtensionsReady(timeoutMs = 15000L)
        if (!extensionsReady || !runtimeManager.uBlockActive.value) {
            transition(EngineDownloadState.FAILED)
            return FallbackResult.Failed(
                "Content blocking (uBlock Origin) failed to initialize within deadline. Fallback aborted to prevent exposure to advertisements."
            )
        }
        checkCancelled()

        // Phase 2: Create task-scoped session and navigate
        val geckoSession = withContext(Dispatchers.Main) {
            val newSession = GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(true)  // Task-scoped isolation
                    .useTrackingProtection(true)
                    .build()
            )
            setupObservation(newSession, runtimeManager)
            newSession.open(runtimeManager.runtime)
            session = newSession
            _activeSession.value = newSession
            newSession
        }
        checkCancelled()

        transition(EngineDownloadState.BROWSER_WAITING_FOR_MEDIA)

        // Navigate to target URL
        withContext(Dispatchers.Main) {
            geckoSession.loadUri(targetUrl)
        }

        // Phase 3: Bounded observation loop
        val observationStart = System.nanoTime() / 1_000_000_000.0
        val maxWaitSeconds = config.interactionWaitSeconds
        var firstCandidateSeen: Double? = null

        while (!cancelled.get()) {
            delay(config.pollIntervalMs)
            checkCancelled()

            // Collect incoming media events from WebExtension bridge → CandidateStore
            collectFromBridge(runtimeManager)

            val now = System.nanoTime() / 1_000_000_000.0
            val elapsed2 = now - observationStart
            val candidates = store.values()
            _candidateCount.value = candidates.size

            if (candidates.isEmpty()) {
                if (elapsed2 > maxWaitSeconds) {
                    transition(EngineDownloadState.FAILED)
                    return FallbackResult.Failed(
                        "No playable media was observed in the fallback browser within ${maxWaitSeconds.toInt()}s"
                    )
                }
                continue
            }

            if (firstCandidateSeen == null) {
                firstCandidateSeen = now
            }

            // Desktop parity guard: if the store only contains sidebar live-widget media (numeric stream ID cams),
            // keep waiting for the user to select a server and press Play.
            if (store.storeOnlyWidgetShaped()) {
                if (elapsed2 > maxWaitSeconds) {
                    transition(EngineDownloadState.FAILED)
                    return FallbackResult.Failed(
                        "The requested media did not appear: only sidebar/live-widget streams were observed. Select the server and press Play in the browser window, then try again."
                    )
                }
                transition(EngineDownloadState.BROWSER_WAITING_FOR_MEDIA)
                continue
            }

            // Candidates exist — attempt ranking and stabilization
            transition(EngineDownloadState.BROWSER_STABILIZING_CANDIDATES)
            val decision = ranker.decide(candidates, now)

            val stabilizationElapsed = now - firstCandidateSeen

            Log.d(TAG, "[AUDIT] Fallback Session ID: $taskId | Candidates: ${candidates.size} | Decision: ${decision.selectedCandidateId} | Wait: ${decision.waitSeconds}s | Elapsed: ${"%.1f".format(elapsed2)}s (stabilization: ${"%.1f".format(stabilizationElapsed)}s) | Best score: ${decision.ranked.firstOrNull()?.score}")

            if (decision.selectedCandidateId != null) {
                // Clear winner — proceed to handoff
                Log.i(TAG, "Selected candidate ${decision.selectedCandidateId}: ${decision.explanation}")
                return attemptHandoff(decision.selectedCandidateId, now)
            }

            if (decision.waitSeconds > 0 && stabilizationElapsed < config.stabilizationWaitSeconds && elapsed2 < maxWaitSeconds) {
                // Still stabilizing within bounded stabilization window
                continue
            }

            // Stabilization window finished — take the best available candidate
            // Desktop Build 017 lines 553–555 parity:
            // if not chosen_id and decision.ranked: chosen_id = decision.ranked[0].candidate_id
            if (decision.ranked.isNotEmpty()) {
                val bestId = decision.ranked[0].candidateId
                Log.i(TAG, "Stabilization window completed (${"%.1f".format(stabilizationElapsed)}s); selecting top ranked candidate $bestId (score ${decision.ranked[0].score})")
                return attemptHandoff(bestId, now)
            }

            if (elapsed2 > maxWaitSeconds) {
                transition(EngineDownloadState.FAILED)
                return FallbackResult.Failed(
                    "Candidate stabilization timed out after ${maxWaitSeconds.toInt()}s"
                )
            }
        }

        return FallbackResult.Cancelled
    }

    /**
     * Attempt handoff for ranked candidates with validation probe.
     *
     * Implements the multi-candidate retry loop (up to maxHandoffAttempts).
     * Ported from Desktop _validate_media_candidate (browser_fallback.py:478-530):
     * each candidate is probed with yt-dlp --simulate before committing.
     * Failed/expired candidates fall through to the next ranked candidate.
     */
    private suspend fun attemptHandoff(
        initialCandidateId: String,
        now: Double,
    ): FallbackResult {
        checkCancelled()

        // Build the full ranked list, starting with the selected candidate
        val candidates = store.values()
        val ranked = ranker.decide(candidates, now).ranked
        val candidateOrder = mutableListOf(initialCandidateId)
        ranked.forEach { rc ->
            if (rc.candidateId != initialCandidateId) candidateOrder.add(rc.candidateId)
        }

        for ((attempt, candidateId) in candidateOrder.withIndex()) {
            if (attempt >= config.maxHandoffAttempts) break
            checkCancelled()

            val candidate = store.get(candidateId) ?: continue
            store.select(candidateId)

            Log.i(TAG, """
                [AUDIT] Candidate Handoff Attempt ${attempt + 1}:
                - Task ID: $taskId
                - Candidate ID: $candidateId
                - URL: ${candidate.currentUrl}
                - Kind: ${candidate.kind}
                - Content Type: ${candidate.contentType}
                - Dimensions: ${candidate.width ?: "?"}x${candidate.height ?: "?"}
                - Primary Player: ${candidate.primaryPlayer}
                - User Started: ${candidate.userStarted}
                - Playing: ${candidate.playing}
                - Score: ${candidate.rankScore}
            """.trimIndent())

            transition(EngineDownloadState.HANDOFF_PREPARING)
            val bundle = buildHandoffBundle(candidate)

            transition(EngineDownloadState.HANDOFF_VALIDATING)

            // Probe candidate with yt-dlp --simulate (getInfo)
            val probeOk = validateCandidateProbe(bundle)
            if (probeOk) {
                store.markHandoff(candidateId, success = true)
                transition(EngineDownloadState.DOWNLOADER_RESUMED)
                Log.i(TAG, "[AUDIT] Validation Result: SUCCESS for candidate $candidateId. Transfer context created (${bundle.headers.size} headers). Transitioning to DOWNLOADER_RESUMED.")
                return FallbackResult.Success(bundle)
            }

            // Probe failed — mark and try next candidate
            store.markHandoff(candidateId, success = false)
            Log.w(TAG, "[AUDIT] Validation Result: PROBE FAILED for candidate $candidateId (attempt ${attempt + 1})")

            if (attempt + 1 < config.maxHandoffAttempts && attempt + 1 < candidateOrder.size) {
                transition(EngineDownloadState.FALLBACK_RECOVERING)
            }
        }

        // Parity with Desktop Build 017: If probe failed for all candidates (e.g. server blocks
        // Python urllib / OpenSSL probe, but stream was active in browser), do not fail terminal
        // without attempting transfer of the highest-ranked browser candidate.
        val topCandidateId = candidateOrder.firstOrNull()
        if (topCandidateId != null) {
            val topCandidate = store.get(topCandidateId)
            if (topCandidate != null && topCandidate.lifecycle != CandidateLifecycle.REJECTED) {
                Log.w(TAG, "All candidate probes failed; proceeding with top ranked candidate $topCandidateId to transfer")
                store.select(topCandidateId)
                transition(EngineDownloadState.DOWNLOADER_RESUMED)
                return FallbackResult.Success(buildHandoffBundle(topCandidate))
            }
        }

        transition(EngineDownloadState.FAILED)
        return FallbackResult.Failed(
            "All ${minOf(candidateOrder.size, config.maxHandoffAttempts)} candidate handoff attempts failed validation"
        )
    }

    /**
     * Validate a candidate via yt-dlp --simulate probe.
     * Ported from Desktop _validate_media_candidate (browser_fallback.py:478-530).
     *
     * Returns true if the candidate URL is reachable and extractable,
     * false if the probe fails (expired token, geo-block, DRM, etc.).
     */
    private fun validateCandidateProbe(bundle: HandoffBundle): Boolean {
        return try {
            val request = com.yausername.youtubedl_android.YoutubeDLRequest(bundle.mediaUrl).apply {
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--legacy-server-connect")
                addOption("--extractor-args", "generic:impersonate")
                val qjsPath = com.mvrk.vrka.DownloadRequestFactory.getQuickJsPath()
                if (qjsPath != null) {
                    addOption("--js-runtimes", "quickjs:$qjsPath")
                }
                val ua = bundle.userAgent.ifBlank { com.mvrk.vrka.DownloadRequestFactory.DESKTOP_USER_AGENT }
                addOption("--user-agent", ua)
                if (bundle.referer.isNotBlank()) {
                    addOption("--referer", bundle.referer)
                }
                // Inject the same session headers the downloader will use
                bundle.headers.entries
                    .filter {
                        it.value.isNotBlank() &&
                        !it.key.equals("User-Agent", true) &&
                        !it.key.equals("Referer", true)
                    }
                    .take(12)
                    .forEach { (name, value) ->
                        addCommands(listOf("--add-header", "$name:$value"))
                    }
            }
            com.yausername.youtubedl_android.YoutubeDL.getInstance().getInfo(request)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Candidate probe failed: ${e.message?.take(120)}")
            false
        }
    }

    /**
     * Construct a HandoffBundle from a selected candidate.
     * Ported from Desktop HandoffBundle creation (browser_fallback.py:584-605).
     */
    private fun buildHandoffBundle(candidate: MediaCandidate): HandoffBundle {
        val candidateUa = candidate.requiredHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ua = if (!candidateUa.isNullOrBlank()) candidateUa else com.mvrk.vrka.DownloadRequestFactory.DESKTOP_USER_AGENT
        val candidateReferer = candidate.requiredHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val ref = if (!candidateReferer.isNullOrBlank()) candidateReferer else targetUrl
        val origin = extractOrigin(targetUrl)

        return HandoffBundle(
            taskId = taskId,
            candidateId = candidate.candidateId,
            mediaUrl = candidate.currentUrl,
            mediaKind = candidate.kind,
            userAgent = ua,
            referer = ref,
            origin = origin,
            cookies = emptyList(), // Handled via Cookie header for now
            headers = buildTransferHeaders(candidate, ua, ref, origin),
            expectedContentTypes = listOf(candidate.contentType),
            observedStatus = 0,
            observedContentType = candidate.responseContentType,
            expectedDurationSeconds = candidate.durationSeconds ?: 0.0,
        )
    }

    /**
     * Build the set of headers required for transfer.
     * Ported from Desktop header sanitization (browser_fallback.py:588-591).
     * Desktop filters to: accept, accept-language, origin, referer, user-agent
     */
    private fun buildTransferHeaders(
        candidate: MediaCandidate,
        userAgent: String,
        referer: String,
        origin: String,
    ): Map<String, String> {
        return buildMap {
            put("User-Agent", userAgent)
            put("Referer", referer)
            if (origin.isNotBlank()) put("Origin", origin)

            // Add other essential headers from the observation
            candidate.requiredHeaders.forEach { (key, value) ->
                if (key.equals("Cookie", true) || key.equals("Range", true)) {
                    put(key, value)
                }
            }
        }
    }

    private fun extractOrigin(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        } catch (_: Exception) { "" }
    }

    /**
     * Set up media observation via the existing runtime-level media bridge.
     *
     * The media-detector WebExtension sends messages via sendNativeMessage("browser", ...),
     * which are received by GeckoRuntimeManager.mediaBridge (set as the runtime-level
     * MessageDelegate in initializeExtensions). We collect those candidates into our
     * CandidateStore by observing the mediaBridge's candidates flow.
     *
     * Additionally, set up navigation/progress delegates for popup blocking and
     * page load error handling.
     */
    private fun setupObservation(
        geckoSession: GeckoSession,
        runtimeManager: GeckoRuntimeManager,
    ) {
        // Clear the bridge for this new session
        runtimeManager.mediaBridge.clear()

        // Wire session-scoped message delegate for media-detector extension
        runtimeManager.mediaDetector?.let { detector ->
            geckoSession.webExtensionController.setMessageDelegate(detector, runtimeManager.mediaBridge, "browser")
        }

        // Set up navigation/progress delegates for observation
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                // Block popups (Desktop: NewWindowRequested.Handled = true — suppress popup without altering main session)
                Log.d(TAG, "Suppressed popup request for: $uri")
                return GeckoResult.fromValue(null)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Log.w(TAG, "Fallback page load error: ${error.code} for $uri")
                return GeckoResult.fromValue(null)
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
                    Log.w(TAG, "Fallback page load failed for task $taskId")
                }
            }
        }
    }

    /**
     * Collect candidates from the GeckoMediaBridge into the engine's CandidateStore.
     * Called periodically during the observation loop.
     */
    private fun collectFromBridge(runtimeManager: GeckoRuntimeManager) {
        val bridgeCandidates = runtimeManager.mediaBridge.candidates.value
        val now = System.nanoTime() / 1_000_000_000.0

        for (bridgeCandidate in bridgeCandidates) {
            if (!ingestedBridgeCandidateIds.add(bridgeCandidate.id)) {
                continue
            }
            val url = bridgeCandidate.url
            val mimeType = bridgeCandidate.mimeType
            val resolution = bridgeCandidate.resolution

            // Parse resolution into width/height if not directly provided
            val (parsedW, parsedH) = parseResolution(resolution)
            val width = bridgeCandidate.width ?: parsedW
            val height = bridgeCandidate.height ?: parsedH

            // Determine if this is a segment belonging to an active manifest
            val isSeg = isSegment(url, mimeType)
            val parentUrl = bridgeCandidate.pageUrl
            val segmentParent = if (isSeg && parentUrl.isNotBlank() && (parentUrl.contains(".m3u8") || parentUrl.contains(".mpd"))) {
                parentUrl
            } else {
                ""
            }

            val candidate = store.observe(
                url = url,
                contentType = mimeType,
                timestamp = now,
                segmentParentUrl = segmentParent,
                width = width,
                height = height,
                durationSeconds = bridgeCandidate.durationSeconds,
                playing = bridgeCandidate.playing,
                userStarted = bridgeCandidate.userStarted,
                primaryPlayer = bridgeCandidate.primaryPlayer,
                nuisanceScore = bridgeCandidate.nuisanceScore,
                requiredHeaders = bridgeCandidate.headers,
            )
            if (candidate != null) {
                // Desktop Build 017 browser_fallback.py:581 parity:
                // candidate.request_count = max(candidate.request_count, int(item.get("request_count") or 2))
                candidate.requestCount = maxOf(candidate.requestCount, 2)
            }
        }
    }

    private fun parseResolution(resolution: String): Pair<Int?, Int?> {
        if (resolution.isBlank()) return null to null
        val match = Regex("(\\d+)x(\\d+)").find(resolution)
        return if (match != null) {
            match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        } else {
            val heightMatch = Regex("(\\d+)p").find(resolution)
            if (heightMatch != null) {
                val h = heightMatch.groupValues[1].toIntOrNull()
                if (h != null) ((h * 16 / 9)) to h else null to null
            } else null to null
        }
    }

    private fun transition(target: EngineDownloadState) {
        try {
            stateMachine.transition(target)
            _engineState.value = target
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state transition: ${e.message}")
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Fallback cancelled for task $taskId")
    }

    fun dismiss() {
        try {
            _activeSession.value = null
            val s = session
            session = null
            if (s != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dismiss session error for task $taskId: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            _activeSession.value = null
            val s = session
            session = null
            if (s != null) {
                // Close on main thread
                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    try { s.close() } catch (_: Exception) {}
                }
            }
            store.clear()
            ingestedBridgeCandidateIds.clear()
            try {
                GeckoRuntimeManager.getInstance(context).mediaBridge.clear()
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error for task $taskId: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VRKA-FallbackEngine"
    }
}
