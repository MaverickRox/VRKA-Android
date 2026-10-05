package com.mvrk.vrka

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Result of a stream resolution attempt.
 */
data class ResolvedStreamInfo(
    val generationId: Long,
    val resolvedQuality: VideoQuality,
    val actualHeight: Int? = null,
    val mediaUrl: String? = null,
    val title: String? = null,
    val isDirect: Boolean = true,
)

/**
 * Session snapshot representing the current stream selection before and after the commit boundary.
 */
data class StreamSelectionSession(
    val requestId: String = UUID.randomUUID().toString(),
    val generationId: Long = 0L,
    val url: String = "",
    val quality: VideoQuality = VideoQuality.BEST,
    val mode: MediaMode = MediaMode.VIDEO,
    val state: StreamSelectionState = StreamSelectionState.IDLE,
    val resolvedInfo: ResolvedStreamInfo? = null,
    val errorMessage: String? = null,
)

/**
 * Coordinates stream selection lifecycle with explicit request/generation IDs,
 * strict commit boundaries, and immediate rejection of stale asynchronous callbacks.
 */
class StreamSelectionCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private var activeJob: Job? = null
    private var currentGeneration: Long = 0L

    private val _session = MutableStateFlow(StreamSelectionSession())
    val session: StateFlow<StreamSelectionSession> = _session.asStateFlow()

    /**
     * Updates target quality before the commit boundary.
     * Cancels any pending resolution and rejects any future callbacks from older generations.
     */
    fun selectQuality(
        url: String,
        quality: VideoQuality,
        mode: MediaMode = MediaMode.VIDEO,
        resolveAction: (suspend (generationId: Long) -> ResolvedStreamInfo?)? = null,
    ) {
        scope.launch {
            mutex.withLock {
                // Cancel existing resolution job immediately
                activeJob?.cancel()
                activeJob = null

                currentGeneration++
                val newGen = currentGeneration
                val newRequestId = UUID.randomUUID().toString()

                val state = if (resolveAction != null && url.isNotBlank()) {
                    StreamSelectionState.RESOLVING
                } else {
                    StreamSelectionState.IDLE
                }

                _session.value = StreamSelectionSession(
                    requestId = newRequestId,
                    generationId = newGen,
                    url = url,
                    quality = quality,
                    mode = mode,
                    state = state,
                    resolvedInfo = null,
                    errorMessage = null,
                )

                if (resolveAction != null && url.isNotBlank()) {
                    val job = launch {
                        try {
                            val result = resolveAction(newGen)
                            onResolutionComplete(newGen, result)
                        } catch (ce: CancellationException) {
                            // Cancelled by a newer generation or explicit cancel
                        } catch (t: Throwable) {
                            onResolutionFailed(newGen, t.message ?: "Failed to resolve stream")
                        }
                    }
                    activeJob = job
                }
            }
        }
    }

    /**
     * Delivers an asynchronous resolver result.
     * Strictly verifies that generationId matches the latest generation; otherwise discards it.
     */
    fun onResolutionComplete(generationId: Long, result: ResolvedStreamInfo?) {
        scope.launch {
            mutex.withLock {
                if (generationId != currentGeneration) {
                    // Stale callback: user changed selection while resolution was in flight
                    return@withLock
                }

                val current = _session.value
                if (current.generationId != generationId) {
                    return@withLock
                }

                if (result != null) {
                    _session.value = current.copy(
                        state = StreamSelectionState.RESOLVED,
                        resolvedInfo = result,
                        errorMessage = null,
                    )
                } else {
                    _session.value = current.copy(
                        state = StreamSelectionState.IDLE,
                    )
                }
            }
        }
    }

    /**
     * Delivers an asynchronous resolver failure.
     * Strictly verifies generationId matches before modifying state.
     */
    fun onResolutionFailed(generationId: Long, error: String) {
        scope.launch {
            mutex.withLock {
                if (generationId != currentGeneration) return@withLock
                val current = _session.value
                if (current.generationId != generationId) return@withLock

                _session.value = current.copy(
                    state = StreamSelectionState.FAILED,
                    errorMessage = error,
                )
            }
        }
    }

    /**
     * Cancels active resolution and returns coordinator to IDLE state.
     */
    fun cancelResolution() {
        scope.launch {
            mutex.withLock {
                activeJob?.cancel()
                activeJob = null
                currentGeneration++
                val current = _session.value
                _session.value = current.copy(
                    generationId = currentGeneration,
                    state = StreamSelectionState.CANCELLED,
                )
            }
        }
    }

    fun requestQuality(quality: VideoQuality) {
        val current = _session.value
        selectQuality(url = current.url, quality = quality, mode = current.mode)
    }

    /**
     * COMMIT BOUNDARY:
     * Commits the current user selection into an immutable DownloadRequest snapshot.
     * After this point, subsequent changes to UI stream selection will NOT affect the committed request.
     */
    fun commit(request: DownloadRequest): DownloadRequest {
        val current = _session.value
        _session.value = current.copy(
            state = StreamSelectionState.QUEUED,
        )
        return request.copy(
            quality = if (current.url == request.url) current.quality else request.quality,
            mode = if (current.url == request.url) current.mode else request.mode,
            resolvedMediaUrl = if (current.url == request.url) (current.resolvedInfo?.mediaUrl ?: request.resolvedMediaUrl) else request.resolvedMediaUrl,
        )
    }

    suspend fun commitAsync(request: DownloadRequest): DownloadRequest = mutex.withLock {
        commit(request)
    }

    fun reset() {
        scope.launch {
            mutex.withLock {
                activeJob?.cancel()
                activeJob = null
                currentGeneration++
                _session.value = StreamSelectionSession(
                    generationId = currentGeneration,
                    state = StreamSelectionState.IDLE,
                )
            }
        }
    }
}
