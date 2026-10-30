package com.mvrk.vrka.runtime

import android.content.Context
import android.util.Log
import com.mvrk.vrka.InstalledComponentVersionReader
import com.mvrk.vrka.SecureComponentUpdater
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class YtDlpRuntimeState(
    val snapshot: RuntimeSnapshot = RuntimeSnapshot(),
    val working: Boolean = false,
    val message: String = "",
    val updateTags: Map<String, String> = emptyMap(),
)

/**
 * Owns installation, verification and activation of custom yt-dlp runtimes.
 *
 * Custom binaries are stored separately from the stock binary. Activation points the
 * ytdl-android library at the selected binary without modifying either install. A
 * custom runtime is only ever recorded as installed after it has been executed
 * successfully; a locally computed checksum is treated as integrity metadata, never
 * as proof of publisher authenticity.
 */
class YtDlpRuntimeManager(
    context: Context,
    private val scope: CoroutineScope,
    private val transport: SecureComponentUpdater.HttpTransport = SecureComponentUpdater.DefaultHttpTransport(),
) {
    private val context = context.applicationContext
    private val store = YtDlpRuntimeStore(File(this.context.filesDir, STORE_FILE))
    private val customRoot = File(this.context.filesDir, RUNTIME_DIR)
    private val operationMutex = Mutex()
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow(YtDlpRuntimeState(snapshot = store.load()))
    val state: StateFlow<YtDlpRuntimeState> = _state.asStateFlow()

    @Volatile
    private var busyPredicate: () -> Boolean = { false }

    @Volatile
    var onActiveRuntimeChanged: (() -> Unit)? = null

    fun setBusyPredicate(predicate: () -> Boolean) {
        busyPredicate = predicate
    }

    fun stockBinary(): File = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    fun activeBinary(): File {
        val snapshot = _state.value.snapshot
        val runtime = snapshot.activeRuntime() ?: return stockBinary()
        val binary = binaryFor(runtime.id)
        return if (binary.isFile) binary else stockBinary()
    }

    fun isCustomActive(): Boolean = _state.value.snapshot.activeRuntime() != null

    fun activeVersion(): String {
        val runtime = _state.value.snapshot.activeRuntime()
        if (runtime != null) return runtime.version
        return stockVersion()
    }

    /**
     * Executing the binary is authoritative, but a fresh install (or a device that cannot run
     * it, such as an x86_64 emulator translating ARM64 code) has to fall back to the artifact
     * metadata, the same order the Components card uses. The result is cached against the
     * binary's identity so a component update re-reads it and recomposition stays off disk.
     */
    fun stockVersion(): String {
        val binary = stockBinary()
        val stamp = binary.lastModified() to binary.length()
        if (cachedStockVersionStamp == stamp && cachedStockVersion.isNotEmpty()) {
            return cachedStockVersion
        }
        val version = runCatching { YoutubeDL.getInstance().versionName(context) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: InstalledComponentVersionReader.readYtDlpVersion(binary)
            ?: InstalledComponentVersionReader.readBundledYtDlpVersion(context)
            ?: return ""
        cachedStockVersion = version
        cachedStockVersionStamp = stamp
        return version
    }

    @Volatile
    private var cachedStockVersion: String = ""

    @Volatile
    private var cachedStockVersionStamp: Pair<Long, Long>? = null

    fun activeDescription(): String {
        val runtime = _state.value.snapshot.activeRuntime()
        return runtime?.let { "Custom ${it.repo} ${it.tag}" } ?: "Stock yt-dlp"
    }

    fun customRuntimePathFor(id: String): File = File(File(customRoot, id), "yt-dlp")

    /**
     * Re-applies the persisted selection after [YoutubeDL.init]. Fails closed to stock
     * when the selected custom binary is missing or cannot be activated.
     */
    fun applyActiveRuntime(): Result<Unit> {
        val snapshot = _state.value.snapshot
        val runtime = snapshot.activeRuntime()
        if (runtime == null) return applyBinary(stockBinary())
        val binary = binaryFor(runtime.id)
        if (!binary.isFile) {
            Log.w(TAG, "Active custom runtime ${runtime.id} is missing; reverting to stock")
            saveSnapshot(snapshot.copy(activeKind = RuntimeKind.STOCK, activeId = null), "Custom runtime was missing; using stock yt-dlp.")
            return applyBinary(stockBinary())
        }
        return applyBinary(binary)
    }

    suspend fun activateStock(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronizedActivation {
            saveSnapshot(state.value.snapshot.copy(activeKind = RuntimeKind.STOCK, activeId = null), "Using stock yt-dlp.")
            applyBinary(stockBinary())
        }
    }

    suspend fun activateCustom(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        synchronizedActivation {
            val runtime = state.value.snapshot.runtimes.firstOrNull { it.id == id }
                ?: return@synchronizedActivation Result.failure(IllegalArgumentException("Custom runtime not found."))
            val binary = binaryFor(id)
            if (!binary.isFile) {
                return@synchronizedActivation Result.failure(IllegalStateException("Custom runtime binary is missing. Reinstall it."))
            }
            val applied = applyBinary(binary)
            applied.onSuccess {
                saveSnapshot(
                    state.value.snapshot.copy(activeKind = RuntimeKind.CUSTOM, activeId = id),
                    "Using ${runtime.repo} ${runtime.tag}.",
                )
            }
            applied
        }
    }

    suspend fun install(repoInput: String): Result<String> = withContext(Dispatchers.IO) {
        if (busyPredicate()) {
            return@withContext Result.failure(IllegalStateException("Finish or cancel active downloads before changing the runtime."))
        }
        if (!beginOperation()) {
            return@withContext Result.failure(IllegalStateException("Another runtime operation is already running."))
        }
        try {
            val repo = CustomRuntimeSource.normalizeRepo(repoInput)
                ?: return@withContext Result.failure(IllegalArgumentException("Enter a GitHub repository as owner/repo."))
            if (repo in UPSTREAM_REPOS) {
                return@withContext Result.failure(
                    IllegalArgumentException("This repository is managed as the stock runtime. Choose a custom fork."),
                )
            }
            publishWorking(true, "Checking releases for $repo")
            val candidate = fetchCandidate(repo)
            val snapshot = state.value.snapshot
            val existing = snapshot.runtimes.firstOrNull { it.repo == repo }
            if (existing != null && existing.tag == candidate.tag && binaryFor(existing.id).isFile) {
                publishWorking(false, "${candidate.tag} is already installed.")
                return@withContext Result.success("${candidate.tag} is already installed.")
            }
            val id = existing?.id ?: UUID.randomUUID().toString()
            publishWorking(true, "Downloading ${candidate.tag}")
            val record = downloadAndVerify(id, repo, candidate, existing)
            val updated = snapshot.copy(
                runtimes = snapshot.runtimes.filterNot { it.id == id } + record,
                activeKind = RuntimeKind.CUSTOM,
                activeId = id,
            )
            saveSnapshot(updated, "")
            publishWorking(true, "Verifying activation")
            val activation = runCatching {
                YoutubeDL.getInstance().init(context)
                applyBinary(binaryFor(id)).getOrThrow()
            }
            if (activation.isFailure) {
                saveSnapshot(updated.copy(activeKind = RuntimeKind.STOCK, activeId = null), "")
                notifyActiveChanged()
                return@withContext Result.failure(
                    IllegalStateException("Installed ${candidate.tag}, but activation failed: ${activation.exceptionOrNull()?.message.orEmpty()}"),
                )
            }
            notifyActiveChanged()
            publishWorking(false, "Custom runtime ${candidate.tag} installed and active.")
            Result.success("Custom runtime ${candidate.tag} installed and active.")
        } catch (error: Throwable) {
            Log.e(TAG, "Custom runtime install failed: ${error.message}")
            publishWorking(false, error.message ?: "Custom runtime installation failed.")
            Result.failure(error)
        } finally {
            endOperation()
        }
    }

    suspend fun update(id: String): Result<String> = withContext(Dispatchers.IO) {
        if (busyPredicate()) {
            return@withContext Result.failure(IllegalStateException("Finish or cancel active downloads before changing the runtime."))
        }
        if (!beginOperation()) {
            return@withContext Result.failure(IllegalStateException("Another runtime operation is already running."))
        }
        try {
            val snapshot = state.value.snapshot
            val existing = snapshot.runtimes.firstOrNull { it.id == id }
                ?: return@withContext Result.failure(IllegalArgumentException("Custom runtime not found."))
            publishWorking(true, "Checking releases for ${existing.repo}")
            val candidate = fetchCandidate(existing.repo)
            if (candidate.tag == existing.tag && binaryFor(id).isFile) {
                publishWorking(false, "${existing.repo} is up to date (${existing.tag}).")
                return@withContext Result.success("${existing.repo} is up to date (${existing.tag}).")
            }
            publishWorking(true, "Downloading ${candidate.tag}")
            val record = downloadAndVerify(id, existing.repo, candidate, existing)
            val updated = snapshot.copy(
                runtimes = snapshot.runtimes.filterNot { it.id == id } + record,
            )
            saveSnapshot(updated, "")
            _state.value = _state.value.copy(updateTags = _state.value.updateTags - id)
            if (updated.activeRuntime()?.id == id) {
                applyBinary(binaryFor(id)).onFailure { error ->
                    return@withContext Result.failure(
                        IllegalStateException("Updated runtime could not be activated: ${error.message.orEmpty()}"),
                    )
                }
                notifyActiveChanged()
            }
            publishWorking(false, "Custom runtime updated to ${candidate.tag}.")
            Result.success("Custom runtime updated to ${candidate.tag}.")
        } catch (error: Throwable) {
            Log.e(TAG, "Custom runtime update failed: ${error.message}")
            publishWorking(false, error.message ?: "Custom runtime update failed.")
            Result.failure(error)
        } finally {
            endOperation()
        }
    }

    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        if (!beginOperation()) return@withContext
        try {
            publishWorking(true, "Checking custom runtime updates")
            val tags = mutableMapOf<String, String>()
            var failures = 0
            for (runtime in state.value.snapshot.runtimes) {
                runCatching {
                    val candidate = fetchCandidate(runtime.repo)
                    if (candidate.tag != runtime.tag) tags[runtime.id] = candidate.tag
                }.onFailure {
                    failures++
                    Log.w(TAG, "Update check failed for ${runtime.repo}: ${it.message}")
                }
            }
            _state.value = _state.value.copy(updateTags = tags)
            publishWorking(
                false,
                when {
                    tags.isNotEmpty() -> "${tags.size} custom runtime update(s) available."
                    failures > 0 -> "Some custom runtime update checks failed."
                    else -> "Custom runtimes are up to date."
                },
            )
        } finally {
            endOperation()
        }
    }

    suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        synchronizedActivation {
            val snapshot = state.value.snapshot
            if (snapshot.activeKind == RuntimeKind.CUSTOM && snapshot.activeId == id) {
                return@synchronizedActivation Result.failure(
                    IllegalStateException("Switch to stock yt-dlp before removing the active custom runtime."),
                )
            }
            if (snapshot.runtimes.none { it.id == id }) {
                return@synchronizedActivation Result.failure(IllegalArgumentException("Custom runtime not found."))
            }
            deleteRuntimeDirectory(id)
            saveSnapshot(
                snapshot.copy(runtimes = snapshot.runtimes.filterNot { it.id == id }),
                "Custom runtime removed.",
            )
            Result.success(Unit)
        }
    }

    /**
     * Runs the staged zipapp through the bundled CPython with the same environment
     * contract the library uses, and returns the reported version. A null result
     * means the candidate must not be installed.
     */
    internal fun probeVersion(binary: File): String? {
        val python = File(context.applicationInfo.nativeLibraryDir, "libpython.so")
        if (!python.isFile) {
            Log.w(TAG, "Bundled python missing; cannot verify custom runtime")
            return null
        }
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val outputFile = runCatching {
            File.createTempFile("ytdlp_probe_", ".log", context.cacheDir)
        }.getOrNull() ?: return null
        return try {
            val process = runCatching {
                ProcessBuilder(python.absolutePath, binary.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile)
                    .apply {
                        environment().apply {
                            put(
                                "LD_LIBRARY_PATH",
                                listOf("python", "ffmpeg", "aria2c")
                                    .joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
                            )
                            put("SSL_CERT_FILE", File(packages, "python/usr/etc/tls/cert.pem").absolutePath)
                            put("PYTHONHOME", File(packages, "python/usr").absolutePath)
                            put("HOME", File(packages, "python/usr").absolutePath)
                            put("PATH", (System.getenv("PATH") ?: "") + ":" + context.applicationInfo.nativeLibraryDir)
                            put("TMPDIR", context.cacheDir.absolutePath)
                        }
                    }
                    .start()
            }.getOrElse { error ->
                Log.w(TAG, "Custom runtime probe could not start: ${error.message}")
                return null
            }
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "Custom runtime probe timed out")
                return null
            }
            if (process.exitValue() != 0) {
                Log.w(TAG, "Custom runtime probe exited with ${process.exitValue()}")
                return null
            }
            val text = outputFile.readText().trim()
            text.lineSequence()
                .firstOrNull { it.contains("yt-dlp", ignoreCase = true) }
                ?.substringAfter("yt-dlp", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        } finally {
            outputFile.delete()
        }
    }

    private fun downloadAndVerify(
        id: String,
        repo: String,
        candidate: ReleaseCandidate,
        existing: CustomRuntime?,
    ): CustomRuntime {
        val dir = File(customRoot, id).apply { mkdirs() }
        val temporary = File(dir, "yt-dlp.download.tmp")
        if (temporary.exists()) temporary.delete()
        try {
            val hash = transport.downloadToFile(candidate.asset.downloadUrl, temporary)
            val length = temporary.length()
            if (length <= 0L) error("Downloaded runtime is empty.")
            if (length > CustomRuntimeSource.MAX_RUNTIME_BYTES) error("Downloaded runtime is unexpectedly large.")
            if (candidate.asset.size > 0L && length != candidate.asset.size) {
                error("Downloaded runtime size does not match the published asset.")
            }
            if (existing != null && existing.sha256.isNotBlank() && existing.tag == candidate.tag &&
                !existing.sha256.equals(hash, ignoreCase = true)
            ) {
                error("The published ${candidate.tag} binary changed since it was installed; refusing to replace it.")
            }
            if (!temporary.setExecutable(true, true)) {
                Log.w(TAG, "Could not mark custom runtime executable; python can still load it")
            }
            val version = probeVersion(temporary)
                ?: error("Downloaded runtime did not execute successfully; it was not installed.")
            val target = File(dir, "yt-dlp")
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val now = System.currentTimeMillis()
            return CustomRuntime(
                id = id,
                repo = repo,
                label = "$repo ${candidate.tag}",
                tag = candidate.tag,
                assetName = candidate.asset.name,
                sha256 = hash.lowercase(),
                version = version,
                installedAt = existing?.installedAt ?: now,
                verifiedAt = now,
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun binaryFor(id: String): File = File(File(customRoot, id), "yt-dlp")

    private fun deleteRuntimeDirectory(id: String) {
        val target = File(customRoot, id).canonicalFile
        val root = customRoot.canonicalFile
        if (target.parentFile == root) {
            runCatching { target.deleteRecursively() }
        }
    }

    private fun applyBinary(binary: File): Result<Unit> = runCatching {
        if (!binary.isFile) error("Runtime binary is missing.")
        val field = YoutubeDL::class.java.getDeclaredField("ytdlpPath")
        field.isAccessible = true
        field.set(null, binary)
    }

    private fun synchronizedActivation(block: () -> Result<Unit>): Result<Unit> {
        if (busyPredicate()) {
            return Result.failure(IllegalStateException("Finish or cancel active downloads before changing the runtime."))
        }
        if (!beginOperation()) {
            return Result.failure(IllegalStateException("Another runtime operation is already running."))
        }
        return try {
            val result = block()
            if (result.isSuccess) notifyActiveChanged()
            result
        } finally {
            endOperation()
        }
    }

    private fun beginOperation(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        if (!operationMutex.tryLock()) {
            busy.set(false)
            return false
        }
        return true
    }

    private fun endOperation() {
        runCatching { operationMutex.unlock() }
        busy.set(false)
    }

    private fun notifyActiveChanged() {
        onActiveRuntimeChanged?.invoke()
    }

    private fun publishWorking(working: Boolean, message: String) {
        _state.value = _state.value.copy(working = working, message = message)
    }

    private fun saveSnapshot(snapshot: RuntimeSnapshot, message: String) {
        store.save(snapshot)
        _state.value = _state.value.copy(snapshot = snapshot, message = message.ifBlank { _state.value.message })
    }

    private fun fetchCandidate(repo: String): ReleaseCandidate {
        val bytes = transport.fetchBytes(CustomRuntimeSource.releasesApiUrl(repo))
        val text = bytes.toString(Charsets.UTF_8)
        val releases = CustomRuntimeSource.parseReleases(text)
        if (releases.isEmpty() && !text.trimStart().startsWith("[")) {
            val detail = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
            error("GitHub rejected the release request${if (detail.isBlank()) "." else ": ${detail.take(160)}"}")
        }
        return CustomRuntimeSource.selectCompatible(releases)
            ?: error("No compatible release found. The fork must publish a python zipapp asset named 'yt-dlp'.")
    }

    companion object {
        private const val TAG = "VRKA-Runtime"
        private const val STORE_FILE = "ytdlp_runtimes.json"
        private const val RUNTIME_DIR = "yt-dlp-runtimes"
        private const val PROBE_TIMEOUT_SECONDS = 30L
        private val UPSTREAM_REPOS = setOf(SecureComponentUpdater.REPO_STABLE, SecureComponentUpdater.REPO_NIGHTLY)
    }
}
