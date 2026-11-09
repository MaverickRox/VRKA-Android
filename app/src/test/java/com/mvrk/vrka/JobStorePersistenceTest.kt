package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JobStorePersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestStore(filename: String = "download_jobs.json"): JobStore {
        val file = File(tempFolder.root, filename)
        return JobStore(file)
    }

    private fun sampleRequest(url: String = "https://example.com/video") = DownloadRequest(
        url = url,
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
        prefer60Fps = true,
        audioFormat = AudioFormat.MP3,
        mp3Bitrate = 320,
        isPlaylist = false,
        downloadSubtitles = true,
        automaticCaptions = false,
        embedSubtitles = true,
        subtitleLanguages = "en,es",
        embedMetadata = true,
        embedThumbnail = true,
        sponsorBlock = true,
        sponsorCategories = "sponsor,intro",
        trimStart = "00:00:10",
        trimEnd = "00:01:00",
        customArguments = listOf("--no-cache-dir", "--geo-bypass"),
        commandTemplateId = "template-1",
        commandTemplateName = "My safe template",
        commandTemplateArguments = listOf("--limit-rate", "2M"),
        h264CompatibilityMode = true,
    )

    @Test
    fun nonExistentFileReturnsEmptyList() {
        val store = createTestStore("non_existent.json")
        val jobs = store.load()
        assertTrue("Non-existent store should return empty list", jobs.isEmpty())
    }

    @Test
    fun queuePersistenceRoundtripTenJobs() {
        val store = createTestStore()
        val originalJobs = (1..10).map { i ->
            DownloadJob(
                id = "job-$i",
                request = sampleRequest("https://example.com/video/$i"),
                state = JobState.DONE,
                title = "Video Title $i",
                detail = "Saved to storage",
                progress = 1.0f,
                speed = "15 MB/s",
                etaSeconds = 0L,
                createdAt = 1000000L + i,
                updatedAt = 2000000L + i,
                outputUris = listOf("content://media/external/video/media/$i"),
                error = "",
                attempt = 1,
            )
        }

        store.save(originalJobs)
        val loadedJobs = store.load()

        assertEquals(10, loadedJobs.size)
        for (i in 0 until 10) {
            val original = originalJobs[i]
            val loaded = loadedJobs[i]
            assertEquals(original.id, loaded.id)
            assertEquals(original.request.url, loaded.request.url)
            assertEquals(original.request.quality, loaded.request.quality)
            assertEquals(original.request.prefer60Fps, loaded.request.prefer60Fps)
            assertEquals(original.request.sponsorBlock, loaded.request.sponsorBlock)
            assertEquals(original.request.trimStart, loaded.request.trimStart)
            assertEquals(original.request.customArguments, loaded.request.customArguments)
            assertEquals(original.request.commandTemplateId, loaded.request.commandTemplateId)
            assertEquals(original.request.commandTemplateName, loaded.request.commandTemplateName)
            assertEquals(original.request.commandTemplateArguments, loaded.request.commandTemplateArguments)
            assertEquals(original.request.h264CompatibilityMode, loaded.request.h264CompatibilityMode)
            assertEquals(JobState.DONE, loaded.state)
            assertEquals(original.title, loaded.title)
            assertEquals(original.progress, loaded.progress, 0.001f)
            assertEquals(original.outputUris, loaded.outputUris)
        }
    }

    @Test
    fun activeStreamAndPlaylistScopeSurviveReload() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "scoped",
                    request = sampleRequest(),
                    state = JobState.DOWNLOADING,
                    activeStream = ActiveStream.AUDIO,
                    playlistItemIndex = 3,
                    playlistItemCount = 12,
                ),
            ),
        )

        val loaded = store.load().single()
        assertEquals(ActiveStream.AUDIO, loaded.activeStream)
        assertEquals(3, loaded.playlistItemIndex)
        assertEquals(12, loaded.playlistItemCount)
    }

    @Test
    fun publishedFinalSizeSurvivesReload() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "sized",
                    request = sampleRequest("https://example.com/sized"),
                    state = JobState.DONE,
                    estimatedTotalBytes = 171L * 1024 * 1024,
                    estimatedSizeAccuracy = SizeEstimateKind.APPROXIMATE,
                    downloadedBytes = 171L * 1024 * 1024,
                    reportedTotalBytes = 171L * 1024 * 1024,
                    reportedTotalIsExact = false,
                    actualFinalFileBytes = 80L * 1024 * 1024,
                ),
            ),
        )

        val loaded = store.load().single()

        assertEquals(80L * 1024 * 1024, loaded.actualFinalFileBytes)
        assertEquals(171L * 1024 * 1024, loaded.reportedTotalBytes)
        assertEquals(171L * 1024 * 1024, loaded.estimatedTotalBytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, loaded.estimatedSizeAccuracy)
        assertFalse(loaded.reportedTotalIsExact)
    }

    @Test
    fun legacyRecordWithoutPublishedSizeLoadsWithNull() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "legacy",
                    request = sampleRequest("https://example.com/legacy"),
                    state = JobState.DONE,
                    reportedTotalBytes = 5_000_000,
                ),
            ),
        )

        val loaded = store.load().single()
        assertNull(loaded.actualFinalFileBytes)
        assertNull(loaded.estimatedTotalBytes)
        assertEquals(5_000_000L, loaded.reportedTotalBytes)
    }

    @Test
    fun legacyRecordCreditItsTotalToTheTransferNotTheEstimate() {
        val file = File(tempFolder.root, "legacy_total.json")
        file.writeText(
            """
            [{"id":"old","state":"DONE","title":"Legacy","detail":"Complete",
              "totalBytes":48200000,"totalIsExact":true,
              "request":{"url":"https://example.com/old"}}]
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val loaded = JobStore(file).load().single()

        assertEquals(48_200_000L, loaded.reportedTotalBytes)
        assertTrue(loaded.reportedTotalIsExact)
        assertNull("An old transfer total must never be read as a preflight estimate", loaded.estimatedTotalBytes)
    }

    @Test
    fun runtimeUsedByEachJobSurvivesReload() {
        val store = createTestStore()
        val jobs = listOf(
            DownloadJob(
                id = "stock-job",
                request = sampleRequest("https://example.com/a"),
                state = JobState.DONE,
                runtimeName = "Stock yt-dlp",
                runtimeVersion = "2026.09.05",
            ),
            DownloadJob(
                id = "custom-job",
                request = sampleRequest("https://example.com/b"),
                state = JobState.FAILED,
                runtimeName = "Custom example/yt-dlp nightly",
                runtimeVersion = "2026.10.01",
            ),
        )

        store.save(jobs)
        val loaded = store.load().associateBy { it.id }

        assertEquals("Stock yt-dlp", loaded.getValue("stock-job").runtimeName)
        assertEquals("2026.09.05", loaded.getValue("stock-job").runtimeVersion)
        assertEquals("Custom example/yt-dlp nightly", loaded.getValue("custom-job").runtimeName)
        assertEquals("2026.10.01", loaded.getValue("custom-job").runtimeVersion)
    }

    @Test
    fun interruptedActiveJobsRecoverAsFailedOnRestart() {
        val store = createTestStore()
        val activeStates = listOf(
            JobState.QUEUED,
            JobState.PREPARING,
            JobState.WAITING_FOR_USER,
            JobState.BROWSER_FALLBACK,
            JobState.DOWNLOADING,
            JobState.POSTPROCESSING,
        )

        val inFlightJobs = activeStates.mapIndexed { idx, state ->
            DownloadJob(
                id = "active-$idx",
                request = sampleRequest("https://example.com/stream/$idx"),
                state = state,
                title = "In Flight Stream $idx",
                detail = "Downloading 45%",
                progress = 0.45f,
            )
        }

        store.save(inFlightJobs)
        val restoredJobs = store.load()

        assertEquals(activeStates.size, restoredJobs.size)
        for (job in restoredJobs) {
            assertEquals("In-flight job must be recovered as FAILED", JobState.FAILED, job.state)
            assertEquals("Interrupted", job.detail)
            assertTrue(
                "Must inform user download was interrupted with retry prompt",
                job.error.contains("interrupted when Android stopped VRKA") && job.error.contains("Tap retry"),
            )
        }
    }

    @Test
    fun recoveryLoadPreservesInFlightStateAndPublishMarker() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "downloading",
                    request = sampleRequest(),
                    state = JobState.DOWNLOADING,
                    title = "In Flight",
                    detail = "Downloading 12%",
                    progress = 12f,
                ),
                DownloadJob(
                    id = "publishing",
                    request = sampleRequest(),
                    state = JobState.POSTPROCESSING,
                    detail = "Publishing to Downloads",
                    progress = 100f,
                    publishStarted = true,
                ),
            ),
        )

        val restored = store.load(recoverActive = true).associateBy { it.id }

        assertEquals(JobState.DOWNLOADING, restored.getValue("downloading").state)
        assertEquals("Downloading 12%", restored.getValue("downloading").detail)
        assertEquals(12f, restored.getValue("downloading").progress, 0.001f)
        assertEquals(JobState.POSTPROCESSING, restored.getValue("publishing").state)
        assertTrue("Publish marker must survive process death", restored.getValue("publishing").publishStarted)
    }

    @Test
    fun publishMarkerSurvivesTerminalReload() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "done",
                    request = sampleRequest(),
                    state = JobState.DONE,
                    publishStarted = true,
                ),
            ),
        )

        val restored = store.load().single()

        assertEquals(JobState.DONE, restored.state)
        assertTrue(restored.publishStarted)
    }

    @Test
    fun sensitiveCustomHeadersAreNotWrittenOrRestoredFromJobHistory() {
        val file = File(tempFolder.root, "sensitive_jobs.json")
        val store = JobStore(file)
        val request = sampleRequest().copy(
            customHeaders = mapOf(
                "Cookie" to "session=private-cookie",
                "Authorization" to "Bearer private-token",
                "X-Trace-Id" to "trace-123",
            ),
        )
        store.save(listOf(DownloadJob(id = "sensitive-job", request = request)))

        val persisted = file.readText()
        assertFalse(persisted.contains("private-cookie"))
        assertFalse(persisted.contains("private-token"))
        assertTrue(persisted.contains("trace-123"))
        val restored = store.load().single().request.customHeaders
        assertFalse(restored.keys.any(HeaderValidation::isSensitiveHeader))
        assertEquals("trace-123", restored["X-Trace-Id"])
    }

    @Test
    fun terminalJobsRetainTheirExactStateAndErrors() {
        val store = createTestStore()
        val terminalJobs = listOf(
            DownloadJob(
                id = "term-done",
                request = sampleRequest(),
                state = JobState.DONE,
                title = "Finished",
                detail = "Complete",
                progress = 1.0f,
                error = "",
            ),
            DownloadJob(
                id = "term-failed",
                request = sampleRequest(),
                state = JobState.FAILED,
                title = "Errored",
                detail = "Failed at extraction",
                error = "HTTP 404 Not Found",
            ),
            DownloadJob(
                id = "term-cancelled",
                request = sampleRequest(),
                state = JobState.CANCELLED,
                title = "Stopped",
                detail = "Cancelled by user",
                error = "User cancelled download",
            ),
        )

        store.save(terminalJobs)
        val loaded = store.load()

        assertEquals(3, loaded.size)
        assertEquals(JobState.DONE, loaded[0].state)
        assertEquals("", loaded[0].error)

        assertEquals(JobState.FAILED, loaded[1].state)
        assertEquals("HTTP 404 Not Found", loaded[1].error)

        assertEquals(JobState.CANCELLED, loaded[2].state)
        assertEquals("User cancelled download", loaded[2].error)
    }

    @Test
    fun byteCountsSurviveReloadForActiveAndCompletedJobs() {
        val store = createTestStore()
        store.save(
            listOf(
                DownloadJob(
                    id = "active",
                    request = sampleRequest(),
                    state = JobState.DOWNLOADING,
                    downloadedBytes = 12_400_000L,
                    reportedTotalBytes = 48_200_000L,
                    reportedTotalIsExact = true,
                ),
                DownloadJob(
                    id = "done",
                    request = sampleRequest(),
                    state = JobState.DONE,
                    downloadedBytes = 48_200_000L,
                    reportedTotalBytes = 48_200_000L,
                    reportedTotalIsExact = true,
                ),
                DownloadJob(
                    id = "estimate-only",
                    request = sampleRequest(),
                    state = JobState.FAILED,
                    estimatedTotalBytes = 10_000_000L,
                    estimatedSizeAccuracy = SizeEstimateKind.APPROXIMATE,
                ),
            ),
        )

        val restored = store.load().associateBy { it.id }

        assertEquals(12_400_000L, restored.getValue("active").downloadedBytes)
        assertEquals(48_200_000L, restored.getValue("active").reportedTotalBytes)
        assertTrue(restored.getValue("active").reportedTotalIsExact)
        assertEquals(48_200_000L, restored.getValue("done").reportedTotalBytes)
        assertEquals(10_000_000L, restored.getValue("estimate-only").estimatedTotalBytes)
        assertEquals(SizeEstimateKind.APPROXIMATE, restored.getValue("estimate-only").estimatedSizeAccuracy)
        assertNull(restored.getValue("estimate-only").reportedTotalBytes)
    }

    @Test
    fun missingAndEmptyFilesAreNotTreatedAsCorruption() {
        val missing = JobStore(File(tempFolder.root, "absent.json")).loadWithStatus()
        assertEquals(JobStoreLoadState.MISSING, missing.state)
        assertTrue(missing.jobs.isEmpty())
        assertNull(missing.recoveryCopy)

        val emptyFile = File(tempFolder.root, "empty.json").apply { writeText("") }
        val empty = JobStore(emptyFile).loadWithStatus()
        assertEquals(JobStoreLoadState.EMPTY, empty.state)
        assertTrue(empty.jobs.isEmpty())
    }

    @Test
    fun corruptedJsonFileIsPreservedAndReportedInsteadOfBeingOverwritten() {
        val targetFile = File(tempFolder.root, "corrupted_jobs.json")
        val original = "{ this is definitely not valid json !!! }"
        targetFile.writeText(original, Charsets.UTF_8)

        val result = JobStore(targetFile).loadWithStatus()

        assertTrue("Unrecoverable JSON keeps an empty in-memory queue", result.jobs.isEmpty())
        assertEquals(JobStoreLoadState.MALFORMED, result.state)
        assertNotNull(result.recoveryCopy)
        assertEquals(original, result.recoveryCopy!!.readText())
        assertEquals("load must never rewrite the original", original, targetFile.readText())
    }

    @Test
    fun truncatedFileSalvagesCompleteRecordsWithoutTouchingTheOriginal() {
        val targetFile = File(tempFolder.root, "truncated_jobs.json")
        val original = """
            [
              {"id":"one","state":"DONE","request":{"url":"https://example.com/1"}},
              {"id":"two","state":"FAILED","request":{"url":"https://example.com/2"}},
              {"id":"three","state":"DONE","request":{"url":"https://exa
        """.trimIndent()
        targetFile.writeText(original)

        val result = JobStore(targetFile).loadWithStatus()

        assertEquals(JobStoreLoadState.RECOVERED, result.state)
        assertEquals(listOf("one", "two"), result.jobs.map { it.id })
        assertEquals(original, targetFile.readText())
        assertNotNull(result.recoveryCopy)
    }

    @Test
    fun oneMalformedRecordDoesNotDiscardItsValidNeighbours() {
        val targetFile = File(tempFolder.root, "mixed_jobs.json")
        val original = """
            [
              {"id":"valid-one","state":"DONE","request":{"url":"https://example.com/1"}},
              "not an object",
              {"id":123,"state":"DONE","request":{"url":"https://example.com/bad-id"}},
              {"id":"valid-two","state":"FAILED","request":{"url":"https://example.com/2"}}
            ]
        """.trimIndent()
        targetFile.writeText(original)

        val result = JobStore(targetFile).loadWithStatus()

        assertEquals(JobStoreLoadState.RECOVERED, result.state)
        assertEquals(listOf("valid-one", "valid-two"), result.jobs.map { it.id })
        assertEquals(2, result.skippedRecords)
        assertEquals(original, targetFile.readText())
    }

    @Test
    fun duplicateIdsAndUnknownFieldsAreHandledDeterministically() {
        val targetFile = File(tempFolder.root, "duplicate_jobs.json")
        targetFile.writeText(
            """
            [
              {"id":"same","state":"DONE","title":"first","request":{"url":"https://example.com/1"},"futureField":{"x":1}},
              {"id":"same","state":"FAILED","title":"second","request":{"url":"https://example.com/2"}}
            ]
            """.trimIndent(),
        )

        val result = JobStore(targetFile).loadWithStatus()

        assertEquals(JobStoreLoadState.RECOVERED, result.state)
        assertEquals(1, result.jobs.size)
        assertEquals("first", result.jobs.single().title)
        assertEquals(1, result.skippedRecords)
    }

    @Test
    fun unsupportedSchemaVersionIsPreservedAndReported() {
        val targetFile = File(tempFolder.root, "future_jobs.json")
        val original = """{"schemaVersion":99,"jobs":[]}"""
        targetFile.writeText(original)

        val result = JobStore(targetFile).loadWithStatus()

        assertEquals(JobStoreLoadState.UNSUPPORTED_SCHEMA, result.state)
        assertTrue(result.jobs.isEmpty())
        assertNotNull(result.recoveryCopy)
        assertEquals(original, targetFile.readText())
    }

    @Test
    fun schemaVersionTwoRoundtrips() {
        val store = createTestStore("v2.json")
        val result = store.save(
            listOf(DownloadJob(id = "v2", request = sampleRequest(), state = JobState.DONE)),
        )

        assertTrue(result.success)
        val persisted = File(tempFolder.root, "v2.json").readText()
        assertTrue(persisted.contains("\"schemaVersion\":2"))
        assertEquals(1, store.loadWithStatus().jobs.size)
        assertEquals(JobStoreLoadState.OK, store.loadWithStatus().state)
    }

    @Test
    fun failedTemporaryFileCreationDoesNotDestroyTheOriginal() {
        val targetFile = File(tempFolder.root, "jobs.json")
        targetFile.writeText("original")
        val ops = FailingOps().apply { failCreate = true }
        val store = JobStore(targetFile, ops)

        val result = store.save(listOf(DownloadJob(id = "new", request = sampleRequest())))

        assertFalse(result.success)
        assertEquals("original", targetFile.readText())
    }

    @Test
    fun atomicMoveRejectionFallsBackToARegularReplaceMove() {
        val ops = FailingOps().apply { atomicResult = false }
        val store = JobStore(File(tempFolder.root, "jobs.json"), ops)

        val result = store.save(listOf(DownloadJob(id = "fallback", request = sampleRequest())))

        assertTrue(result.success)
        assertEquals(listOf("fallback"), store.load().map { it.id })
    }

    @Test
    fun aFailedFallbackMoveReportsFailureAndKeepsTheOriginal() {
        val targetFile = File(tempFolder.root, "jobs.json")
        targetFile.writeText("original")
        val ops = FailingOps().apply {
            atomicResult = false
            failReplace = true
        }
        val store = JobStore(targetFile, ops)

        val result = store.save(listOf(DownloadJob(id = "new", request = sampleRequest())))

        assertFalse(result.success)
        assertEquals("original", targetFile.readText())
    }

    @Test
    fun fullStorageWriteFailureIsReportedAndPreservesTheOriginal() {
        val targetFile = File(tempFolder.root, "jobs.json")
        targetFile.writeText("original")
        val ops = FailingOps().apply { failWrite = true }
        val store = JobStore(targetFile, ops)

        val result = store.save(listOf(DownloadJob(id = "new", request = sampleRequest())))

        assertFalse(result.success)
        assertTrue(result.error.contains("No space left"))
        assertEquals("original", targetFile.readText())
    }

    @Test
    fun interruptedTemporaryWriteNeverReplacesTheOriginal() {
        val targetFile = File(tempFolder.root, "jobs.json")
        targetFile.writeText("original")
        val ops = FailingOps().apply { partialWrite = true }
        val store = JobStore(targetFile, ops)

        val result = store.save(listOf(DownloadJob(id = "new", request = sampleRequest())))

        assertFalse(result.success)
        assertEquals("original", targetFile.readText())
    }

    @Test
    fun unwritableParentReportsFailureInsteadOfThrowing() {
        val parentFile = File(tempFolder.root, "not-a-directory").apply { writeText("x") }
        val target = File(parentFile, "jobs.json")
        val store = JobStore(target)

        val result = store.save(listOf(DownloadJob(id = "new", request = sampleRequest())))

        assertFalse(result.success)
        assertTrue(target.exists().not())
    }

    @Test
    fun recoveryCopiesAreBounded() {
        val targetFile = File(tempFolder.root, "bounded_jobs.json")
        repeat(6) {
            targetFile.writeText("{ broken $it")
            assertEquals(JobStoreLoadState.MALFORMED, JobStore(targetFile).loadWithStatus().state)
        }

        val copies = tempFolder.root.listFiles { file -> file.name.startsWith("bounded_jobs.corrupt-") }
            ?: emptyArray()
        assertTrue("Expected at most ${JobStore.MAX_RECOVERY_COPIES} recovery copies, found ${copies.size}", copies.size <= JobStore.MAX_RECOVERY_COPIES)
        assertTrue(copies.isNotEmpty())
    }

    private class FailingOps : JobStoreFileOps by DefaultJobStoreFileOps {
        var failCreate = false
        var failWrite = false
        var partialWrite = false
        var failReplace = false
        var atomicResult: Boolean = true
        var silentlySkipMove = false

        override fun createTempFile(parent: File, prefix: String): File {
            if (failCreate) throw java.io.IOException("Could not create temporary file")
            return DefaultJobStoreFileOps.createTempFile(parent, prefix)
        }

        override fun writeText(file: File, text: String) {
            if (failWrite) throw java.io.IOException("No space left on device")
            if (partialWrite) {
                file.writeText(text.take(text.length / 2))
                throw java.io.IOException("Interrupted while writing")
            }
            DefaultJobStoreFileOps.writeText(file, text)
        }

        override fun atomicMove(source: File, target: File): Boolean {
            if (silentlySkipMove) return true
            if (!atomicResult) return false
            return DefaultJobStoreFileOps.atomicMove(source, target)
        }

        override fun replaceMove(source: File, target: File) {
            if (failReplace) throw java.io.IOException("Move failed")
            DefaultJobStoreFileOps.replaceMove(source, target)
        }
    }

    @Test
    fun capsPersistenceAt250Jobs() {
        val store = createTestStore()
        val manyJobs = (1..300).map { i ->
            DownloadJob(
                id = "job-$i",
                request = sampleRequest(),
                state = JobState.DONE,
                title = "Job $i",
            )
        }

        store.save(manyJobs)
        val loaded = store.load()
        assertEquals("Store must cap saved jobs to 250", 250, loaded.size)
    }

    @Test
    fun retentionNeverDiscardsANonterminalJobBeyondTheHistoryCap() {
        val store = createTestStore()
        val history = (1..300).map { i ->
            DownloadJob(
                id = "history-$i",
                request = sampleRequest(),
                state = JobState.DONE,
                updatedAt = 10_000L + i,
            )
        }
        val longRunning = DownloadJob(
            id = "long-running",
            request = sampleRequest(),
            state = JobState.DOWNLOADING,
            updatedAt = 1L,
        )

        val result = store.save(history + longRunning)
        assertTrue(result.success)
        val loaded = store.load(recoverActive = true)
        assertEquals(250, loaded.size)
        assertTrue(
            "An active download must survive the history cap even when it is the oldest record",
            loaded.any { it.id == "long-running" },
        )
    }

    @Test
    fun pinnedTerminalJobSurvivesTheHistoryCapUntilItsPublicationIsReconciled() {
        val store = createTestStore()
        val history = (1..300).map { i ->
            DownloadJob(
                id = "history-$i",
                request = sampleRequest(),
                state = JobState.DONE,
                updatedAt = 10_000L + i,
            )
        }
        val completing = DownloadJob(
            id = "completing",
            request = sampleRequest(),
            state = JobState.DONE,
            outputUris = listOf("content://media/external/downloads/42"),
            updatedAt = 1L,
        )

        val result = store.save(history + completing, pinnedJobIds = setOf("completing"))
        assertTrue(result.success)
        val loaded = store.load().associateBy { it.id }
        assertEquals(250, loaded.size)
        assertEquals(
            listOf("content://media/external/downloads/42"),
            loaded.getValue("completing").outputUris,
        )
    }

    @Test
    fun saveFailsWhenTheFilesystemDoesNotActuallyReplaceTheFile() {
        val targetFile = File(tempFolder.root, "jobs.json")
        targetFile.writeText("""{"schemaVersion":2,"jobs":[]}""")
        val ops = FailingOps().apply { silentlySkipMove = true }
        val store = JobStore(targetFile, ops)

        val result = store.save(
            listOf(DownloadJob(id = "terminal", request = sampleRequest(), state = JobState.DONE)),
            pinnedJobIds = setOf("terminal"),
        )

        assertFalse("A write that never reached the target must not report success", result.success)
        assertTrue(
            result.error.contains("verification", ignoreCase = true) ||
                result.error.contains("exact", ignoreCase = true),
        )
    }
}
