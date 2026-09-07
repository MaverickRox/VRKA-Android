package com.mvrk.vrka

import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.mvrk.vrka.engine.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveFallbackState(
    val jobId: String,
    val job: DownloadJob,
    val engine: FallbackEngine,
    val isVisible: Boolean = true,
)

class DownloadExecutionException(
    message: String,
    val exitCode: Int,
    val outputTail: List<String>,
    val transferStarted: Boolean,
) : RuntimeException(message)

class VrkaDownloadManager(
    private val context: Context,
    val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val activeFallbackEngines = ConcurrentHashMap<String, FallbackEngine>()
    private val initialized = AtomicBoolean(false)
    private val store = JobStore(context)
    private val publisher = OutputPublisher(context, settingsRepository)
    private val stagingRoot by lazy { File(context.getExternalFilesDir(null), "staging") }
    private val speedPattern = Regex("""\bat\s+([^\s]+/s)""", RegexOption.IGNORE_CASE)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _activeFallback = MutableStateFlow<ActiveFallbackState?>(null)
    val activeFallback: StateFlow<ActiveFallbackState?> = _activeFallback.asStateFlow()

    private val _runtime = MutableStateFlow(RuntimeStatus())
    val runtime: StateFlow<RuntimeStatus> = _runtime.asStateFlow()

    val diagnosticStore = DiagnosticStore(context, scope)
    val diagnostics: StateFlow<List<DiagnosticEntry>> = diagnosticStore.entries

    fun clearDiagnostics() {
        scope.launch(Dispatchers.IO) {
            diagnosticStore.clear()
        }
    }

    fun dismissFallbackView() {
        _activeFallback.value = _activeFallback.value?.copy(isVisible = false)
    }

    fun showFallbackView(jobId: String) {
        val current = _activeFallback.value
        if (current != null && current.jobId == jobId) {
            _activeFallback.value = current.copy(isVisible = true)
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            _jobs.value = store.load().sortedByDescending(DownloadJob::createdAt)
            for (ignored in persistRequests) {
                store.save(_jobs.value)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (jobId in queue) process(jobId)
        }
    }

    fun enqueue(request: DownloadRequest): String {
        require(isHttpUrl(request.url)) { "Enter a valid http or https URL." }
        val id = UUID.randomUUID().toString()
        val job = DownloadJob(id = id, request = request)
        mutate(persist = true) { listOf(job) + it }
        queue.trySend(id)
        return id
    }

    fun retry(jobId: String): String? {
        val previous = _jobs.value.firstOrNull { it.id == jobId } ?: return null
        if (!previous.state.isTerminal) return null
        val id = UUID.randomUUID().toString()
        val request = previous.request.copy(
            resolvedMediaUrl = null,
            resolvedHeaders = emptyMap(),
        )
        val replacement = DownloadJob(
            id = id,
            request = request,
            title = previous.title,
            detail = "Queued retry",
            attempt = previous.attempt + 1,
        )
        mutate(persist = true) { listOf(replacement) + it }
        queue.trySend(id)
        return id
    }

    fun cancel(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.state.isTerminal) return
        cancelled += jobId
        activeFallbackEngines.remove(jobId)?.cancel()
        if (_activeFallback.value?.jobId == jobId) {
            _activeFallback.value = null
        }
        YoutubeDL.getInstance().destroyProcessById(jobId)
        update(
            jobId,
            state = JobState.CANCELLED,
            detail = "Cancelled",
            error = "",
            persist = true,
        )
        cleanupStaging(jobId)
    }

    fun deleteJob(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (!job.state.isTerminal) return
        scope.launch(Dispatchers.IO) {
            job.outputUris.forEach(publisher::delete)
            mutate(persist = true) { jobs -> jobs.filterNot { it.id == jobId } }
        }
    }

    fun clearFinished() {
        scope.launch(Dispatchers.IO) {
            mutate(persist = true) { list -> list.filterNot { it.state.isTerminal } }
        }
    }

    fun updateRuntime(channel: UpdatePreference) {
        if (_runtime.value.busy) return
        scope.launch(Dispatchers.IO) {
            _runtime.value = _runtime.value.copy(busy = true, message = "Updating yt-dlp")
            runCatching {
                ensureInitialized()
                val updater = SecureComponentUpdater(context)
                val targetTag = updater.fetchLatestReleaseTag(channel)
                val postVersion = updater.updateYtDlp(channel, targetTag).getOrThrow()
                val version = normalizedVersion(postVersion)
                _runtime.value = RuntimeStatus(
                    initialized = true,
                    version = version,
                    message = "yt-dlp " + version + " ready",
                )
            }.onFailure { error ->
                Log.e("VRKA", "Runtime update failed", error)
                val bundled = runCatching {
                    YoutubeDL.getInstance().versionName(context)
                }.getOrNull().orEmpty()
                _runtime.value = RuntimeStatus(
                    initialized = initialized.get(),
                    version = bundled,
                    message = "Update failed; known-good runtime retained: " + safeError(error),
                )
            }
        }
    }

    fun openOutput(uriText: String) {
        val uri = Uri.parse(uriText)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }

    fun shareOutput(uriText: String) {
        val uri = Uri.parse(uriText)
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share with",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun process(jobId: String) {
        if (isCancelled(jobId)) return
        var currentStage = "Direct Extraction"
        update(jobId, state = JobState.PREPARING, detail = "Starting runtime", persist = true)
        DownloadService.start(context)
        try {
            ensureInitialized()
            var job = current(jobId) ?: return
            if (isCancelled(jobId)) return

            val metadata = runCatching {
                YoutubeDL.getInstance().getInfo(DownloadRequestFactory.info(job.request))
            }.getOrNull()
            if (isCancelled(jobId)) return
            if (metadata != null) {
                val title = metadata.title?.take(180).orEmpty()
                if (title.isNotBlank()) {
                    update(jobId, title = title, detail = "Source ready", persist = true)
                }
            }

            job = current(jobId) ?: return
            var failure = runCatching { downloadOnce(job) }.exceptionOrNull()
            if (failure != null) {
                Log.e("VRKA", "Direct attempt failed: ${safeError(failure)}")
            }

            val priorCategories = mutableListOf<FailureCategory>()
            if (failure != null) {
                val firstCategory = classifyDownloadError(failure.message.orEmpty())
                if (firstCategory != FailureCategory.UNKNOWN) {
                    priorCategories.add(firstCategory)
                }
            }

            val execFailure = failure as? DownloadExecutionException
            val initialTransferStarted = execFailure?.transferStarted == true || job.progress > 0f

            // Direct recovery retry: only retry if category in (CLOUDFLARE, HTTP) and transfer has not started
            if (
                failure != null &&
                job.request.resolvedMediaUrl == null &&
                !initialTransferStarted &&
                !isCancelled(jobId) &&
                shouldRetryDirect(failure)
            ) {
                currentStage = "Direct Recovery"
                cleanupStaging(job.id)
                update(
                    job.id,
                    state = JobState.PREPARING,
                    detail = "Retrying direct extraction",
                    persist = true,
                )
                failure = runCatching { downloadOnce(job, recoveryAttempt = true) }.exceptionOrNull()
                if (failure != null) {
                    Log.e("VRKA", "Direct recovery failed: ${safeError(failure)}")
                    val retryCategory = classifyDownloadError(failure.message.orEmpty())
                    if (retryCategory != FailureCategory.UNKNOWN) {
                        priorCategories.add(retryCategory)
                    }
                }
            }

            // Failure classification and browser-fallback eligibility
            if (failure != null && job.request.resolvedMediaUrl == null && !isCancelled(jobId)) {
                val errorMessage = failure.message.orEmpty()
                val latestExecFailure = failure as? DownloadExecutionException
                val outputTailText = latestExecFailure?.outputTail?.joinToString("\n").orEmpty()
                val transferStarted = latestExecFailure?.transferStarted == true ||
                    job.progress > 0f ||
                    isTransferFailureAfterResolution(outputTailText, hasTransferStarted = false)

                val (category, isRecoverable) = classifyAndCheckRecoverable(
                    errorMessage = errorMessage,
                    targetUrl = job.request.url,
                    executionOutput = outputTailText,
                    hasResolvedMediaUrl = false,
                    hasTransferStarted = transferStarted,
                    priorCategories = priorCategories,
                )
                Log.i("VRKA", "Failure classified: category=$category, recoverable=$isRecoverable, transferStarted=$transferStarted, nativeTarget=${isYtdlpNativeTarget(job.request.url)}")

                if (isRecoverable) {
                    // Automatic browser fallback
                    currentStage = "Browser Fallback"
                    update(
                        jobId,
                        state = JobState.BROWSER_FALLBACK,
                        detail = "Direct extraction failed ($category); starting browser fallback",
                        error = "",
                        persist = true,
                    )

                    val engine = FallbackEngine(
                        context = context,
                        taskId = jobId,
                        targetUrl = job.request.url,
                    )
                    activeFallbackEngines[jobId] = engine
                    _activeFallback.value = ActiveFallbackState(jobId, job, engine, isVisible = true)

                    try {
                        val result = engine.execute()
                        when (result) {
                            is FallbackResult.Success -> {
                                val bundle = result.bundle
                                // Dismiss fallback view; transfer proceeds in queue
                                engine.dismiss()
                                activeFallbackEngines.remove(jobId)
                                _activeFallback.value = null

                                val headers = buildMap {
                                    if (bundle.referer.isNotBlank()) put("Referer", bundle.referer)
                                    if (bundle.origin.isNotBlank()) put("Origin", bundle.origin)
                                    if (bundle.userAgent.isNotBlank()) put("User-Agent", bundle.userAgent)
                                    putAll(bundle.headers)
                                }
                                val updated = job.copy(
                                    request = job.request.copy(
                                        resolvedMediaUrl = bundle.mediaUrl,
                                        resolvedHeaders = headers,
                                    ),
                                    state = JobState.PREPARING,
                                    detail = "${bundle.mediaKind.value.uppercase()} candidate selected; downloading",
                                    updatedAt = System.currentTimeMillis(),
                                )
                                replace(updated, persist = true)
                                DownloadService.start(context)

                                // Resume download with the same task (native replay)
                                job = current(jobId) ?: return
                                currentStage = "Native Replay"
                                Log.i("VRKA", "Resuming downloadOnce (native replay) for job $jobId with resolved media: ${bundle.mediaUrl}")
                                failure = runCatching { downloadOnce(job) }.exceptionOrNull()
                                if (failure != null) {
                                    Log.w("VRKA", "Native replay failed: ${failure.message}")
                                    val replayReason = classifyNativeReplayFailure(failure.message ?: "")
                                    val eligible = isEligibleForGeckoTransport(
                                        reason = replayReason,
                                        isBrowserDerivedCandidate = true,
                                    )
                                    if (eligible && !isCancelled(jobId)) {
                                        currentStage = "Gecko Transport"
                                        Log.i("VRKA", "Native replay failed with $replayReason; activating GeckoWebExecutor fallback transport for job $jobId")
                                        failure = runCatching {
                                            downloadViaGeckoTransport(job, bundle, stagingDirectory(job.id))
                                        }.exceptionOrNull()
                                        if (failure != null) {
                                            Log.e("VRKA", "GeckoWebExecutor transport transfer failed: ${failure.message}", failure)
                                        }
                                    }
                                }
                            }
                            is FallbackResult.Failed -> {
                                Log.e("VRKA", "Browser fallback failed: ${result.reason}")
                                failure = RuntimeException(result.reason)
                            }
                            is FallbackResult.Cancelled -> {
                                // Cancellation handled below
                                return
                            }
                        }
                    } finally {
                        engine.cleanup()
                        activeFallbackEngines.remove(jobId)
                        _activeFallback.value = null
                    }
                } else {
                    // Terminal failure — NOT eligible for fallback
                    Log.i("VRKA", "Failure is terminal ($category); no fallback")
                }
            }

            if (failure != null) throw failure
        } catch (error: Throwable) {
            Log.e("VRKA", "Download processing for $jobId failed: ${error.message}", error)
            if (!isCancelled(jobId)) {
                val failureMsg = safeError(error)
                update(
                    jobId,
                    state = JobState.FAILED,
                    detail = "Download failed",
                    error = failureMsg,
                    persist = true,
                )
                val currentJob = current(jobId)
                val stage = if (currentJob?.detail?.contains("Publishing", ignoreCase = true) == true) {
                    "Publishing"
                } else {
                    currentStage
                }
                val categoryName = classifyDownloadError(error.message.orEmpty()).name
                val tailOutput = (error as? DownloadExecutionException)?.outputTail?.takeLast(30)?.joinToString("\n")
                    ?: error.message.orEmpty().take(2000)
                diagnosticStore.record(
                    DiagnosticEntry(
                        jobId = jobId,
                        title = currentJob?.title?.ifBlank { currentJob.request.url }.orEmpty(),
                        stage = stage,
                        failureCategory = categoryName,
                        summary = failureMsg,
                        detail = tailOutput,
                        url = currentJob?.request?.url.orEmpty(),
                        quality = currentJob?.request?.quality?.label.orEmpty(),
                        acquisitionMethod = if (currentJob?.request?.resolvedMediaUrl != null) "Browser Fallback" else "Native yt-dlp",
                    )
                )
            }
            cleanupStaging(jobId)
        } finally {
            activeFallbackEngines.remove(jobId)?.cancel()
            DownloadService.stopIfIdle(context)
        }
    }

    private fun downloadOnce(job: DownloadJob, recoveryAttempt: Boolean = false) {
        if (isCancelled(job.id)) return
        val directory = stagingDirectory(job.id)
        update(
            job.id,
            state = JobState.DOWNLOADING,
            detail = "Downloading",
            error = "",
            persist = true,
        )
        var lastUiUpdate = 0L
        val outputLines = mutableListOf<String>()
        var transferStarted = false

        val response = try {
            YoutubeDL.getInstance().execute(
                DownloadRequestFactory.download(job, directory, recoveryAttempt),
                job.id,
            ) { progress, eta, line ->
                outputLines.add(line)
                if (outputLines.size > 200) {
                    outputLines.removeAt(0)
                }

                val isCommandLineEcho = line.startsWith("[debug]", ignoreCase = true) ||
                    line.contains("--print", ignoreCase = true) ||
                    line.contains("before_dl:", ignoreCase = true) ||
                    line.contains("%(title)s", ignoreCase = true)

                if (!isCommandLineEcho && (TRANSFER_STARTED_MARKERS.any { line.contains(it, ignoreCase = true) } || progress > 0f)) {
                    transferStarted = true
                }

                if (!isCommandLineEcho && line.contains("__VRKA_TITLE__", ignoreCase = true)) {
                    val parsedTitle = line.substringAfter("__VRKA_TITLE__").substringAfter("__vrka_title__").trim()
                    if (parsedTitle.isNotBlank()) {
                        update(job.id, title = parsedTitle)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUiUpdate >= 250 || progress >= 100f) {
                    lastUiUpdate = now
                    val detail = when {
                        line.contains("[Merger]", true) ||
                            line.contains("[ExtractAudio]", true) ||
                            line.contains("[Metadata]", true) -> "Post-processing"
                        else -> "Downloading"
                    }
                    update(
                        job.id,
                        state = if (detail == "Post-processing") {
                            JobState.POSTPROCESSING
                        } else {
                            JobState.DOWNLOADING
                        },
                        progress = progress.coerceIn(0f, 100f),
                        speed = speedPattern.find(line)?.groupValues?.getOrNull(1).orEmpty(),
                        etaSeconds = eta.takeIf { it >= 0 },
                        detail = detail,
                    )
                }
            }
        } catch (e: Exception) {
            val tail = outputLines.takeLast(50)
            val combined = (tail + listOfNotNull(e.message)).joinToString("\n")
            if (TRANSFER_STARTED_MARKERS.any { combined.contains(it, ignoreCase = true) }) {
                transferStarted = true
            }
            throw DownloadExecutionException(
                message = e.message ?: "yt-dlp execution failed",
                exitCode = -1,
                outputTail = tail,
                transferStarted = transferStarted,
            )
        }

        if (isCancelled(job.id)) return
        if (response.exitCode != 0) {
            val errText = response.err.ifBlank { "yt-dlp exited with code " + response.exitCode }
            val tail = (outputLines + response.out.lines()).filter { it.isNotBlank() }.takeLast(50)
            val combined = (tail + errText).joinToString("\n")
            if (TRANSFER_STARTED_MARKERS.any { combined.contains(it, ignoreCase = true) }) {
                transferStarted = true
            }
            throw DownloadExecutionException(
                message = errText,
                exitCode = response.exitCode,
                outputTail = tail,
                transferStarted = transferStarted,
            )
        }
        update(
            job.id,
            state = JobState.POSTPROCESSING,
            progress = 100f,
            detail = "Publishing to Downloads",
            persist = true,
        )
        var outputs = DownloadRequestFactory.outputPaths(response.out)
            .map(::File)
            .filter(File::isFile)
        if (outputs.isEmpty()) {
            outputs = directory.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") }
                .sortedBy(File::lastModified)
                .toList()
        }
        check(outputs.isNotEmpty()) { "yt-dlp completed but produced no output file." }
        requireVideoStreams(job, outputs)
        val published = outputs.mapIndexed { index, file ->
            val outputName = preferredOutputName(job, file, index, outputs.size)
            publisher.publish(file, outputName).toString()
        }
        update(
            job.id,
            state = JobState.DONE,
            progress = 100f,
            detail = if (published.size == 1) "Saved to Downloads/VRKA" else {
                "Saved " + published.size + " files"
            },
            outputUris = published,
            error = "",
            persist = true,
        )
        cleanupStaging(job.id)
    }

    private fun downloadViaGeckoTransport(
        job: DownloadJob,
        bundle: HandoffBundle,
        directory: File,
    ) {
        if (isCancelled(job.id)) return
        directory.mkdirs()

        update(
            job.id,
            state = JobState.DOWNLOADING,
            detail = "Downloading via browser network",
            error = "",
            persist = true,
        )

        val runtime = GeckoRuntimeManager.getInstance(context).runtime
        val transport = GeckoWebExecutorTransport(runtime)

        val headers = buildMap {
            if (bundle.referer.isNotBlank()) put("Referer", bundle.referer)
            if (bundle.origin.isNotBlank()) put("Origin", bundle.origin)
            if (bundle.userAgent.isNotBlank()) put("User-Agent", bundle.userAgent)
            putAll(bundle.headers)
        }

        val isHls = bundle.mediaKind == CandidateKind.HLS || MediaAssembly.isHlsPlaylist(bundle.mediaUrl)
        val isDash = bundle.mediaKind == CandidateKind.DASH || MediaAssembly.isDashManifest(bundle.mediaUrl)
        val outputFile: File

        if (isHls || isDash) {
            val segments: List<String>
            if (isHls) {
                Log.i("VRKA", "Gecko transport downloading HLS playlist for job ${job.id}")
                val playlistRequest = GeckoTransportRequest(url = bundle.mediaUrl, headers = headers)
                val playlistResponse = transport.fetch(playlistRequest)
                val playlistContent = playlistResponse.body?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("Empty playlist response from ${bundle.mediaUrl}")

                var variantUrl = bundle.mediaUrl
                var variantContent = playlistContent

                if (MediaAssembly.isMasterPlaylist(playlistContent)) {
                    val variants = MediaAssembly.parseMasterPlaylist(playlistContent, bundle.mediaUrl)
                    val selected = MediaAssembly.selectVariant(variants, job.request.quality.height)
                        ?: throw IOException("No suitable variant stream found in master playlist")
                    Log.i("VRKA", "Selected HLS variant: $selected")
                    variantUrl = selected
                    val variantResponse = transport.fetch(GeckoTransportRequest(url = selected, headers = headers))
                    variantContent = variantResponse.body?.bufferedReader()?.use { it.readText() }
                        ?: throw IOException("Empty variant playlist response from $selected")
                }

                segments = MediaAssembly.parseVariantSegments(variantContent, variantUrl)
            } else {
                Log.i("VRKA", "Gecko transport downloading DASH manifest for job ${job.id}")
                val mpdRequest = GeckoTransportRequest(url = bundle.mediaUrl, headers = headers)
                val mpdResponse = transport.fetch(mpdRequest)
                val mpdContent = mpdResponse.body?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("Empty DASH manifest response from ${bundle.mediaUrl}")
                segments = MediaAssembly.parseDashSegments(mpdContent, bundle.mediaUrl)
            }

            if (segments.isEmpty()) {
                throw IOException("No media segments found in manifest")
            }

            Log.i("VRKA", "Gecko transport transferring ${segments.size} segments concurrently for job ${job.id}")
            val stagingFile = File(directory, "assembled_media.ts")
            var lastUiUpdate = 0L
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                ConcurrentTransferEngine.downloadHlsSegments(
                    transport = transport,
                    segments = segments,
                    headers = headers,
                    outputFile = stagingFile,
                    directory = directory,
                    maxWorkers = 4,
                    isCancelled = { isCancelled(job.id) },
                    onProgress = { completed, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 250 || completed >= total) {
                            lastUiUpdate = now
                            val progress = ((completed.toFloat() / total) * 100f).coerceIn(0f, 100f)
                            update(
                                job.id,
                                progress = progress,
                                detail = "Downloading segment $completed/$total",
                            )
                        }
                    }
                )
            }
            outputFile = stagingFile
        } else {
            Log.i("VRKA", "Gecko transport downloading direct media for job ${job.id}")
            val directFile = File(directory, "media.mp4")
            var lastUiUpdate = 0L
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                ConcurrentTransferEngine.downloadDirectMedia(
                    transport = transport,
                    url = bundle.mediaUrl,
                    headers = headers,
                    destinationFile = directFile,
                    directory = directory,
                    maxWorkers = 4,
                    isCancelled = { isCancelled(job.id) },
                    onProgress = { written, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 250 || (total > 0 && written >= total)) {
                            lastUiUpdate = now
                            val progress = if (total > 0) ((written.toFloat() / total) * 100f).coerceIn(0f, 100f) else 50f
                            update(
                                job.id,
                                progress = progress,
                                detail = "Downloading via browser network",
                            )
                        }
                    }
                )
            }
            outputFile = directFile
        }

        if (isCancelled(job.id)) return

        update(
            job.id,
            state = JobState.POSTPROCESSING,
            progress = 100f,
            detail = "Publishing to Downloads",
            persist = true,
        )

        val outputs = listOf(outputFile)
        requireVideoStreams(job, outputs)
        val published = outputs.mapIndexed { index, file ->
            val outputName = preferredOutputName(job, file, index, outputs.size)
            publisher.publish(file, outputName).toString()
        }

        update(
            job.id,
            state = JobState.DONE,
            progress = 100f,
            detail = if (published.size == 1) "Saved to Downloads/VRKA" else "Saved ${published.size} files",
            outputUris = published,
            error = "",
            persist = true,
        )
        cleanupStaging(job.id)
    }

    private fun requireVideoStreams(job: DownloadJob, outputs: List<File>) {
        if (job.request.mode != MediaMode.VIDEO) return
        outputs.forEach { file ->
            val media = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val width = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull()
                    val height = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull()
                    Triple(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" ||
                            width?.let { it > 0 } == true,
                        width,
                        height,
                    )
                } finally {
                    retriever.release()
                }
            }.getOrDefault(Triple(false, null, null))
            check(media.first) {
                "Video mode produced no video stream; refusing to publish an audio-only file."
            }
            job.request.quality.height?.let { cap ->
                check(media.third == null || media.third!! <= cap) {
                    "Downloaded video exceeds the requested ${cap}p quality cap."
                }
            }
        }
    }

    private fun preferredOutputName(job: DownloadJob, file: File, index: Int, total: Int): String {
        val title = job.title
            .trim()
            .takeIf { it.isNotBlank() && it != "Browser verification" }
        val host = runCatching { Uri.parse(job.request.url).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .takeIf(String::isNotBlank)
        val stem = title ?: host ?: "VRKA-${job.id.take(8)}"
        val suffix = if (total > 1) " ${index + 1}" else ""
        val extension = file.extension
        return if (extension.isBlank()) stem + suffix else "$stem$suffix.$extension"
    }

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(initialized) {
            if (initialized.get()) return
            _runtime.value = RuntimeStatus(busy = true, message = "Preparing yt-dlp and FFmpeg")
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            DownloadRequestFactory.initNativeLibraryDir(context.applicationInfo.nativeLibraryDir)
            initialized.set(true)
            val version = normalizedVersion(
                YoutubeDL.getInstance().versionName(context).orEmpty(),
            )
            _runtime.value = RuntimeStatus(
                initialized = true,
                version = version,
                message = "yt-dlp " + version + " ready",
            )
        }
    }

    private fun stagingDirectory(jobId: String): File {
        stagingRoot.mkdirs()
        return File(stagingRoot, jobId).apply { mkdirs() }
    }

    private fun cleanupStaging(jobId: String) {
        val child = File(stagingRoot, jobId)
        val rootPath = stagingRoot.absoluteFile.toPath().normalize()
        val childPath = child.absoluteFile.toPath().normalize()
        if (childPath.startsWith(rootPath) && childPath != rootPath) {
            runCatching { child.deleteRecursively() }
        }
    }

    private fun current(jobId: String): DownloadJob? =
        _jobs.value.firstOrNull { it.id == jobId }

    private fun isCancelled(jobId: String): Boolean =
        jobId in cancelled || current(jobId)?.state == JobState.CANCELLED

    private fun replace(job: DownloadJob, persist: Boolean) {
        mutate(persist) { list -> list.map { if (it.id == job.id) job else it } }
    }

    private fun update(
        jobId: String,
        state: JobState? = null,
        title: String? = null,
        detail: String? = null,
        progress: Float? = null,
        speed: String? = null,
        etaSeconds: Long? = null,
        outputUris: List<String>? = null,
        error: String? = null,
        persist: Boolean = false,
    ) {
        mutate(persist) { list ->
            list.map { job ->
                if (job.id != jobId) job else job.copy(
                    state = state ?: job.state,
                    title = title ?: job.title,
                    detail = detail ?: job.detail,
                    progress = progress ?: job.progress,
                    speed = speed ?: job.speed,
                    etaSeconds = etaSeconds ?: job.etaSeconds,
                    outputUris = outputUris ?: job.outputUris,
                    error = error ?: job.error,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun mutate(persist: Boolean, transform: (List<DownloadJob>) -> List<DownloadJob>) {
        synchronized(_jobs) {
            _jobs.value = transform(_jobs.value)
            if (persist) persistRequests.trySend(Unit)
        }
    }

    private fun normalizedVersion(value: String): String =
        value.removePrefix("yt-dlp ").trim()

    private fun shouldRetryDirect(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        val category = classifyDownloadError(message)
        if (category !in setOf(FailureCategory.CLOUDFLARE, FailureCategory.HTTP)) {
            return false
        }
        val nonRecoverableNetworkErrors = listOf(
            "timed out",
            "timeout",
            "network is unreachable",
            "unable to resolve host",
            "name or service not known",
        )
        return nonRecoverableNetworkErrors.none(message::contains)
    }

    private fun safeError(error: Throwable): String {
        val raw = (error.message ?: error::class.java.simpleName)
        val (_, guidance) = formatDownloadError(raw)
        val text = if (guidance.isNotBlank() && guidance != raw) {
            guidance
        } else {
            raw.lineSequence().toList().takeLast(2).joinToString(" ")
        }
        return text
            .replace(Regex("""(?i)(cookie|authorization|token|signature)=?[^\s&]*"""), "$1=[redacted]")
            .replace(Regex("""https?://[^\s]+"""), "[private URL]")
            .take(320)
            .ifBlank { "The operation failed." }
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val uri = Uri.parse(value.trim())
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

    companion object {
        fun create(context: Context): VrkaDownloadManager {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val settings = SettingsRepository(context.applicationContext, scope)
            return VrkaDownloadManager(context.applicationContext, settings, scope)
        }
    }
}
