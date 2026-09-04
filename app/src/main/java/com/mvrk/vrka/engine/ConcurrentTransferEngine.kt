package com.mvrk.vrka.engine

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-connection and concurrent segment transfer engine.
 *
 * Implements Desktop VRKA Build 017 transfer semantics:
 * - Parallel byte-range workers for direct media files supporting HTTP ranges.
 * - Parallel segment workers for HLS/DASH manifest streams.
 * - Ordered assembly, bounded memory buffering, and guaranteed cleanup of partial parts.
 * - Graceful fallback to single-stream sequential transfer when ranges are unsupported.
 */
object ConcurrentTransferEngine {
    private const val TAG = "VRKA-ConcurrentTransfer"
    private const val DEFAULT_CONCURRENCY = 4
    private const val MIN_SPLIT_SIZE = 4 * 1024 * 1024L // 4 MB minimum for byte ranges

    /**
     * Download HLS segments concurrently using bounded parallel workers.
     * Segments are assembled in strict chronological index order into [outputFile].
     * Temporary segment part files are immediately deleted after assembly to keep disk usage bounded.
     */
    suspend fun downloadHlsSegments(
        transport: GeckoWebExecutorTransport,
        segments: List<String>,
        headers: Map<String, String>,
        outputFile: File,
        directory: File,
        maxWorkers: Int = DEFAULT_CONCURRENCY,
        isCancelled: () -> Boolean,
        onProgress: (completedCount: Int, totalCount: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (outputFile.exists()) outputFile.delete()
        val tempDir = File(directory, "hls_parts_").apply { mkdirs() }

        try {
            val semaphore = Semaphore(maxWorkers.coerceIn(1, 8))
            val completedMap = ConcurrentHashMap<Int, File>()
            var nextIndexToAssemble = 0

            FileOutputStream(outputFile).use { fos ->
                val downloadJobs = segments.mapIndexed { index, segUrl ->
                    launch {
                        semaphore.withPermit {
                            if (isCancelled()) return@withPermit
                            val partFile = File(tempDir, "seg_%05d.part".format(index))
                            var attempts = 0
                            var success = false
                            var lastError: Throwable? = null

                            while (attempts < 3 && !success && !isCancelled()) {
                                attempts++
                                try {
                                    if (partFile.exists()) partFile.delete()
                                    FileOutputStream(partFile).use { partOut ->
                                        transport.downloadToStream(
                                            request = GeckoTransportRequest(url = segUrl, headers = headers),
                                            output = partOut,
                                            isCancelled = isCancelled,
                                        )
                                    }
                                    success = true
                                    completedMap[index] = partFile
                                } catch (t: Throwable) {
                                    lastError = t
                                    if (isCancelled()) break
                                    delay(250L * attempts)
                                }
                            }

                            if (!success && !isCancelled()) {
                                throw IOException("Failed to download HLS segment  after  attempts: ", lastError)
                            }
                        }
                    }
                }

                // Sequential assembler loop that streams finished parts in strict index order
                while (nextIndexToAssemble < segments.size) {
                    if (isCancelled()) {
                        downloadJobs.forEach { it.cancel() }
                        throw GeckoTransportException.Cancelled("HLS segment download cancelled")
                    }

                    val partFile = completedMap[nextIndexToAssemble]
                    if (partFile != null && partFile.exists()) {
                        FileInputStream(partFile).use { fis ->
                            val buf = ByteArray(32768)
                            var read: Int
                            while (fis.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                            }
                        }
                        partFile.delete()
                        completedMap.remove(nextIndexToAssemble)
                        nextIndexToAssemble++
                        onProgress(nextIndexToAssemble, segments.size)
                    } else {
                        downloadJobs.firstOrNull { it.isCancelled }?.let {
                            throw GeckoTransportException.Cancelled("Segment job cancelled")
                        }
                        delay(50L)
                    }
                }

                downloadJobs.joinAll()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Download direct media file using concurrent byte ranges if supported by the server.
     * Falls back to sequential single-connection streaming if ranges are unsupported or file is small.
     */
    suspend fun downloadDirectMedia(
        transport: GeckoWebExecutorTransport,
        url: String,
        headers: Map<String, String>,
        destinationFile: File,
        directory: File,
        maxWorkers: Int = DEFAULT_CONCURRENCY,
        isCancelled: () -> Boolean,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val rangeProbeHeaders = headers + ("Range" to "bytes=0-0")
        var totalLength: Long = -1L
        var supportsRanges = false

        try {
            val probeResponse = transport.fetch(
                GeckoTransportRequest(url = url, headers = rangeProbeHeaders),
                timeoutMs = 15000L,
            )
            probeResponse.use { resp ->
                if (resp.statusCode == 206) {
                    supportsRanges = true
                    val cr = resp.headers["content-range"].orEmpty()
                    val totalStr = cr.substringAfterLast("/", "")
                    totalLength = totalStr.toLongOrNull() ?: -1L
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Range probe failed: ; falling back to single stream")
        }

        if (supportsRanges && totalLength >= MIN_SPLIT_SIZE) {
            Log.i(TAG, "Server supports ranges; transferring  bytes with  concurrent workers")
            downloadWithByteRanges(
                transport = transport,
                url = url,
                headers = headers,
                totalLength = totalLength,
                destinationFile = destinationFile,
                directory = directory,
                workerCount = maxWorkers,
                isCancelled = isCancelled,
                onProgress = onProgress,
            )
        } else {
            Log.i(TAG, "Server does not support ranges or file is small; downloading sequentially")
            transport.downloadToFile(
                request = GeckoTransportRequest(url = url, headers = headers),
                destinationFile = destinationFile,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
        }
    }

    private suspend fun downloadWithByteRanges(
        transport: GeckoWebExecutorTransport,
        url: String,
        headers: Map<String, String>,
        totalLength: Long,
        destinationFile: File,
        directory: File,
        workerCount: Int,
        isCancelled: () -> Boolean,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempDir = File(directory, "ranges_").apply { mkdirs() }
        val chunkSize = (totalLength + workerCount - 1) / workerCount

        try {
            val partFiles = (0 until workerCount).map { index ->
                File(tempDir, "range_%02d.part".format(index))
            }
            val writtenPerPart = ConcurrentHashMap<Int, Long>()

            val jobs = (0 until workerCount).map { index ->
                val start = index * chunkSize
                val end = minOf(totalLength - 1, (index + 1) * chunkSize - 1)
                val partFile = partFiles[index]

                launch {
                    if (isCancelled() || start > end) return@launch
                    val rangeHeaders = headers + ("Range" to "bytes=-")
                    var success = false
                    var attempts = 0
                    var lastError: Throwable? = null

                    while (attempts < 3 && !success && !isCancelled()) {
                        attempts++
                        try {
                            if (partFile.exists()) partFile.delete()
                            FileOutputStream(partFile).use { fos ->
                                transport.downloadToStream(
                                    request = GeckoTransportRequest(url = url, headers = rangeHeaders),
                                    output = fos,
                                    onProgress = { read, _ ->
                                        writtenPerPart[index] = read
                                        val totalWritten = writtenPerPart.values.sum()
                                        onProgress(totalWritten, totalLength)
                                    },
                                    isCancelled = isCancelled,
                                )
                            }
                            success = true
                        } catch (t: Throwable) {
                            lastError = t
                            if (isCancelled()) break
                            delay(250L * attempts)
                        }
                    }

                    if (!success && !isCancelled()) {
                        throw IOException("Failed range chunk  [..] after  attempts: ", lastError)
                    }
                }
            }

            jobs.joinAll()
            if (isCancelled()) throw GeckoTransportException.Cancelled("Byte-range transfer cancelled")

            if (destinationFile.exists()) destinationFile.delete()
            FileOutputStream(destinationFile).use { fos ->
                partFiles.forEach { part ->
                    if (part.exists()) {
                        FileInputStream(part).use { fis ->
                            val buf = ByteArray(32768)
                            var read: Int
                            while (fis.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                            }
                        }
                    }
                }
            }
            onProgress(totalLength, totalLength)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
