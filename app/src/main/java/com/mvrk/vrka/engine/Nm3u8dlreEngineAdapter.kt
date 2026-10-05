package com.mvrk.vrka.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Specialist N_m3u8DL-RE CLI engine adapter.
 *
 * Provides capability-gated execution of the modern N_m3u8DL-RE media extractor.
 * On Android, this engine is strictly gated pending physical verification.
 */
class Nm3u8dlreEngineAdapter(
    private val context: Context
) : MediaDownloadEngine {

    override val engineName: String = "Nm3u8dlreEngineAdapter"

    companion object {
        private const val TAG = "VRKA-Nm3u8dlre"

        /**
         * Capability gate flag for Android runtime verification.
         * N_m3u8DL-RE requires a native binary compatible with Android bionic/arm64-v8a.
         * Remains disabled until verified on physical test devices.
         */
        const val CAPABILITY_FLAG = "ANDROID_RUNTIME_VERIFICATION_PENDING_PHYSICAL_TEST"

        private val SPEED_REGEX = Regex("""([0-9.]+\s*[KMGT]?B/s)""", RegexOption.IGNORE_CASE)
        private val PERCENT_REGEX = Regex("""([0-9.]+)%""")
        private val PROGRESS_SEG_REGEX = Regex("""(\d+)/(\d+)""")
    }

    override fun isAvailable(): Boolean {
        // Strict capability check
        val capabilityEnabled = false // Gated under CAPABILITY_FLAG pending physical tests
        if (!capabilityEnabled) {
            return false
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDir, "libnm3u8dlre.so")
        return binary.exists() && binary.canExecute()
    }

    override suspend fun download(
        handoffContext: MediaHandoffContext,
        onProgress: suspend (EngineDownloadProgress) -> Unit,
        cancellationToken: EngineCancellationToken
    ): EngineExecutionResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext EngineExecutionResult.Failed(
                IllegalStateException("N_m3u8DL-RE is disabled by gate: $CAPABILITY_FLAG"),
                "Engine unavailable on current runtime"
            )
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDir, "libnm3u8dlre.so")
        val destination = handoffContext.stagingOutputFile
        val saveDir = destination.parentFile ?: context.cacheDir
        val saveName = destination.nameWithoutExtension

        val reqContext = RequestContext(
            targetUrl = handoffContext.url,
            pageUrl = handoffContext.originalPageUrl,
            baseHeaders = handoffContext.headers
        )
        val effectiveHeaders = reqContext.resolveHeaders(handoffContext.url)

        val command = mutableListOf<String>()
        command.add(binary.absolutePath)
        command.add(handoffContext.url)
        command.add("--save-dir")
        command.add(saveDir.absolutePath)
        command.add("--save-name")
        command.add(saveName)
        command.add("--auto-select")
        command.add("--no-log")

        effectiveHeaders.forEach { (k, v) ->
            command.add("-H")
            command.add("$k: $v")
        }

        onProgress(EngineDownloadProgress.Initializing("Starting N_m3u8DL-RE specialist transfer"))

        var process: Process? = null
        val stderrLines = mutableListOf<String>()

        try {
            val pb = ProcessBuilder(command)
            pb.directory(saveDir)
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc
            val reader = proc.inputStream.bufferedReader()

            while (true) {
                if (cancellationToken.isCancelled()) {
                    destroyProcessSafely(proc)
                    destination.delete()
                    return@withContext EngineExecutionResult.Cancelled
                }

                val line = reader.readLine() ?: break
                stderrLines.add(line)
                if (stderrLines.size > 200) stderrLines.removeAt(0)

                val pctMatch = PERCENT_REGEX.find(line)
                val speedMatch = SPEED_REGEX.find(line)
                val segMatch = PROGRESS_SEG_REGEX.find(line)

                if (pctMatch != null || speedMatch != null || segMatch != null) {
                    val pct = pctMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val speed = speedMatch?.groupValues?.get(1).orEmpty()
                    val segText = segMatch?.value.orEmpty()

                    val detail = buildString {
                        if (segText.isNotEmpty()) append("Segment $segText ")
                        if (speed.isNotEmpty()) append("• $speed")
                    }.trim()

                    onProgress(
                        EngineDownloadProgress.Downloading(
                            bytesRead = destination.length(),
                            totalBytes = null,
                            percentage = pct.coerceIn(1f, 99f),
                            detail = detail.ifBlank { "Downloading stream" }
                        )
                    )
                }
            }

            val exitCode = proc.waitFor()
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }

            if (exitCode != 0) {
                val errorSummary = stderrLines.takeLast(25).joinToString("\n")
                destination.delete()
                return@withContext EngineExecutionResult.Failed(
                    IOException("N_m3u8DL-RE exited with code $exitCode:\n$errorSummary"),
                    "CLI process execution error"
                )
            }

            // Look for produced file
            val candidates = saveDir.listFiles { _, name -> name.startsWith(saveName) }
            val produced = candidates?.maxByOrNull { it.length() }
            if (produced == null || produced.length() == 0L) {
                return@withContext EngineExecutionResult.Failed(
                    IOException("N_m3u8DL-RE finished but output file is missing"),
                    "Output file verification failed"
                )
            }

            onProgress(EngineDownloadProgress.Finalizing("Specialist transfer verified"))
            return@withContext EngineExecutionResult.Success(produced, produced.length())

        } catch (t: Throwable) {
            if (cancellationToken.isCancelled()) {
                destination.delete()
                return@withContext EngineExecutionResult.Cancelled
            }
            destination.delete()
            return@withContext EngineExecutionResult.Failed(t, "N_m3u8DL-RE error: ${t.message}")
        } finally {
            process?.let { destroyProcessSafely(it) }
        }
    }

    private fun destroyProcessSafely(process: Process) {
        runCatching {
            process.destroy()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }
}
