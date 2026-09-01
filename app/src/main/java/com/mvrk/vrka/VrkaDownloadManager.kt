package com.mvrk.vrka

import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VrkaDownloadManager(
    private val context: Context,
    val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val browserWaiters = ConcurrentHashMap<String, CompletableDeferred<BrowserHandoff?>>()
    private val initialized = AtomicBoolean(false)
    private val store = JobStore(context)
    private val publisher = OutputPublisher(context, settingsRepository)
    private val stagingRoot = File(context.getExternalFilesDir(null), "staging")
    private val speedPattern = Regex("""\bat\s+([^\s]+/s)""", RegexOption.IGNORE_CASE)

    private val _jobs = MutableStateFlow(
        store.load().sortedByDescending(DownloadJob::createdAt),
    )
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _browserJobId = MutableStateFlow<String?>(null)
    val browserJobId: StateFlow<String?> = _browserJobId.asStateFlow()

    private val _runtime = MutableStateFlow(RuntimeStatus())
    val runtime: StateFlow<RuntimeStatus> = _runtime.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
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
        browserWaiters.remove(jobId)?.complete(null)
        YoutubeDL.getInstance().destroyProcessById(jobId)
        update(
            jobId,
            state = JobState.CANCELLED,
            detail = "Cancelled",
            error = "",
            persist = true,
        )
        if (_browserJobId.value == jobId) _browserJobId.value = null
        cleanupStaging(jobId)
    }

    fun acceptBrowserHandoff(jobId: String, handoff: BrowserHandoff) {
        val waiter = browserWaiters[jobId] ?: return
        if (applyHandoff(jobId, handoff) == null) return
        if (waiter.complete(handoff)) {
            browserWaiters.remove(jobId, waiter)
            if (_browserJobId.value == jobId) _browserJobId.value = null
        }
    }

    fun closeBrowser(jobId: String) {
        val waiter = browserWaiters[jobId] ?: return
        if (waiter.complete(null)) {
            browserWaiters.remove(jobId, waiter)
            if (_browserJobId.value == jobId) _browserJobId.value = null
        }
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
                val selected = if (channel == UpdatePreference.NIGHTLY) {
                    YoutubeDL.UpdateChannel.NIGHTLY
                } else {
                    YoutubeDL.UpdateChannel.STABLE
                }
                YoutubeDL.getInstance().updateYoutubeDL(context, selected)
                val version = normalizedVersion(
                    YoutubeDL.getInstance().versionName(context).orEmpty(),
                )
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
            if (
                failure != null &&
                job.request.resolvedMediaUrl == null &&
                !isCancelled(jobId) &&
                shouldRetryDirect(failure)
            ) {
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
                }
            }
            if (failure != null && job.request.resolvedMediaUrl == null && !isCancelled(jobId)) {
                waitForBrowser(job, failure) ?: return
                job = current(jobId) ?: return
                failure = runCatching { downloadOnce(job) }.exceptionOrNull()
            }
            if (failure != null) throw failure
        } catch (error: Throwable) {
            if (!isCancelled(jobId)) {
                update(
                    jobId,
                    state = JobState.FAILED,
                    detail = "Download failed",
                    error = safeError(error),
                    persist = true,
                )
            }
        } finally {
            browserWaiters.remove(jobId)
            if (_browserJobId.value == jobId) _browserJobId.value = null
            DownloadService.stopIfIdle(context)
        }
    }

    private suspend fun waitForBrowser(
        job: DownloadJob,
        extractionError: Throwable,
    ): BrowserHandoff? {
        if (isCancelled(job.id)) return null
        update(
            job.id,
            state = JobState.BROWSER_FALLBACK,
            detail = "Direct extraction needs a browser session",
            error = "",
            persist = true,
        )
        DownloadService.stopIfIdle(context)
        val waiter = CompletableDeferred<BrowserHandoff?>()
        browserWaiters[job.id] = waiter
        _browserJobId.value = job.id
        update(
            job.id,
            state = JobState.WAITING_FOR_USER,
            detail = "Complete any legitimate page verification, then play the media",
            error = safeError(extractionError),
            persist = true,
        )
        val handoff = waiter.await()
        if (handoff == null && !isCancelled(job.id)) {
            update(
                job.id,
                state = JobState.FAILED,
                detail = "Browser fallback closed",
                error = "No downloadable non-DRM media was handed off.",
                persist = true,
            )
        }
        return handoff
    }

    private fun applyHandoff(jobId: String, handoff: BrowserHandoff): DownloadJob? {
        val existing = current(jobId) ?: return null
        val headers = buildMap {
            putAll(handoff.candidate.headers)
            if (handoff.cookies.isNotBlank()) put("Cookie", handoff.cookies)
            if (handoff.userAgent.isNotBlank()) put("User-Agent", handoff.userAgent)
            if (handoff.referer.isNotBlank()) put("Referer", handoff.referer)
            runCatching { Uri.parse(handoff.referer).buildUpon().path(null).query(null).build() }
                .getOrNull()
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let { put("Origin", it) }
        }
        val updated = existing.copy(
            request = existing.request.copy(
                resolvedMediaUrl = handoff.candidate.url,
                resolvedHeaders = headers,
            ),
            title = existing.title.ifBlank { handoff.title.take(180) },
            state = JobState.PREPARING,
            detail = handoff.candidate.kind + " detected; browser released",
            updatedAt = System.currentTimeMillis(),
        )
        replace(updated, persist = true)
        DownloadService.start(context)
        return updated
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
        val response = YoutubeDL.getInstance().execute(
            DownloadRequestFactory.download(job, directory, recoveryAttempt),
            job.id,
        ) { progress, eta, line ->
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
        if (isCancelled(job.id)) return
        check(response.exitCode == 0) {
            response.err.ifBlank { "yt-dlp exited with code " + response.exitCode }
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
            .lineSequence()
            .toList().takeLast(2)
            .joinToString(" ")
        return raw
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
