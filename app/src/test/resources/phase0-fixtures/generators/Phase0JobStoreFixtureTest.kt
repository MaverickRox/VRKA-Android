package com.mvrk.vrka

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 0 only. Not part of the production tree. Generates JobStore fixtures by invoking
 * the actual tagged JobStore encoder; derived formats are recorded as derivations.
 */
class Phase0JobStoreFixtureTest {
    private val fixturesRoot = File(System.getenv("VRKA_PHASE0_FIXTURES") ?: error("VRKA_PHASE0_FIXTURES missing"))
    private val tag = System.getenv("VRKA_PHASE0_TAG") ?: error("VRKA_PHASE0_TAG missing")
    private val commit = System.getenv("VRKA_PHASE0_COMMIT") ?: error("VRKA_PHASE0_COMMIT missing")
    private val work = File(fixturesRoot, "_work-$tag").apply { mkdirs() }
    private val out = File(fixturesRoot, tag).apply { mkdirs() }
    private val manifest = JSONArray()

    private fun sampleRequest(url: String = "https://example.com/watch?v=fixture") = DownloadRequest(
        url = url,
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
        prefer60Fps = true,
        audioFormat = AudioFormat.MP3,
        mp3Bitrate = 320,
        isPlaylist = false,
        playlistStart = null,
        playlistEnd = null,
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
        commandTemplateName = "Fixture template",
        commandTemplateArguments = listOf("--limit-rate", "2M"),
        h264CompatibilityMode = true,
        referer = "https://example.com/page",
        origin = "https://example.com",
        customHeaders = mapOf("X-Fixture" to "visible", "Cookie" to "secret-cookie", "Authorization" to "Bearer secret"),
        destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
    )

    private fun job(
        id: String,
        state: JobState = JobState.QUEUED,
        publishStarted: Boolean = false,
        outputUris: List<String> = emptyList(),
        error: String = "",
        attempt: Int = 1,
        createdAt: Long = 1_700_000_000_000L,
    ) = DownloadJob(
        id = id,
        request = sampleRequest("https://example.com/watch?v=$id"),
        state = state,
        title = "Fixture $id",
        detail = "Fixture detail $id",
        progress = if (state.isTerminal) 100f else 42.5f,
        speed = if (state == JobState.DOWNLOADING) "4.2 MB/s" else "",
        etaSeconds = if (state == JobState.DOWNLOADING) 12L else null,
        estimatedTotalBytes = 48_200_000L,
        estimatedSizeAccuracy = SizeEstimateKind.APPROXIMATE,
        downloadedBytes = if (state.isTerminal) 48_200_000L else 12_400_000L,
        reportedTotalBytes = 48_200_000L,
        reportedTotalIsExact = state.isTerminal,
        actualFinalFileBytes = if (state == JobState.DONE) 48_000_000L else null,
        createdAt = createdAt,
        updatedAt = createdAt + 1_000L,
        outputUris = outputUris,
        error = error,
        attempt = attempt,
        actualResolution = if (state == JobState.DONE) "1080p" else null,
        runtimeName = "Stock yt-dlp",
        runtimeVersion = "2026.09.05",
        publishStarted = publishStarted,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newStoreFile(name: String): File = File(work, "$name.work.json")

    private fun saveRaw(name: String, jobs: List<DownloadJob>): String {
        val file = newStoreFile(name)
        JobStore(file).save(jobs)
        return file.readText(Charsets.UTF_8)
    }

    private fun record(
        caseName: String,
        text: String,
        description: String,
        derivation: String,
        fileName: String = "$caseName.json",
    ) {
        val target = File(out, fileName)
        target.writeText(text, Charsets.UTF_8)
        manifest.put(
            JSONObject()
                .put("case", caseName)
                .put("file", "$tag/$fileName")
                .put("bytes", target.length())
                .put("sha256", sha256(target.readBytes()))
                .put("sourceTag", tag)
                .put("sourceCommit", commit)
                .put("sourceEncoder", "app/src/main/java/com/mvrk/vrka/JobStore.kt encode()")
                .put("description", description)
                .put("derivation", derivation),
        )
    }

    @Test
    fun generateJobStoreCorpus() {
        val allStates = JobState.values().map { state ->
            job("state-${state.name.lowercase()}", state = state, publishStarted = state == JobState.POSTPROCESSING)
        }
        val rawSchema = saveRaw("all-states", allStates)
        record(
            "all-job-states",
            rawSchema,
            "All JobState values encoded by the tagged JobStore.",
            if (tag == "v5.1.0") "direct encoder output (v5.1.0 writes a bare JSON array)" else "direct encoder output",
        )

        val terminal = listOf(
            job("done-no-hashes", JobState.DONE, outputUris = listOf("content://media/external/downloads/101"), createdAt = 1_700_000_000_001L),
            job("failed", JobState.FAILED, error = "HTTP 404 Not Found", createdAt = 1_700_000_000_002L),
            job("cancelled", JobState.CANCELLED, error = "User cancelled download", createdAt = 1_700_000_000_003L),
        )
        record(
            "terminal-history-no-hashes",
            saveRaw("terminal", terminal),
            "Terminal History records with provider URIs and no publication hashes.",
            if (tag == "v5.1.0") "direct encoder output (bare array)" else "direct encoder output",
        )

        val inFlight = listOf(
            job("downloading", JobState.DOWNLOADING),
            job("publishing", JobState.POSTPROCESSING, publishStarted = true),
            job("waiting", JobState.WAITING_FOR_USER),
            job("fallback", JobState.BROWSER_FALLBACK),
        )
        record(
            "in-flight-and-publishing",
            saveRaw("in-flight", inFlight),
            "Nonterminal records, including a publishStarted=true publishing job.",
            if (tag == "v5.1.0") "direct encoder output (bare array)" else "direct encoder output",
        )

        if (tag != "v5.1.0") {
            val schema2 = JSONObject(rawSchema)
            schema2.put("schemaVersion", 1)
            record(
                "schema1-jobs",
                schema2.toString(),
                "Object-wrapped JobStore schema 1 using the same encoder-produced job objects.",
                "derived: schemaVersion changed from 2 to 1",
            )
            record(
                "legacy-array-jobs",
                JSONObject(rawSchema).getJSONArray("jobs").toString(),
                "Legacy bare JSON job array using encoder-produced job objects.",
                "derived: jobs array extracted from schema 2",
            )
        } else {
            record(
                "legacy-array-jobs",
                rawSchema,
                "Native v5.1.0 JobStore output: a bare JSON job array.",
                "direct encoder output",
            )
            val wrapped = JSONObject()
            wrapped.put("schemaVersion", 1)
            wrapped.put("jobs", JSONArray(rawSchema))
            record(
                "schema1-jobs",
                wrapped.toString(),
                "Object-wrapped schema 1 using v5.1.0 encoder-produced job objects.",
                "derived: v5.1.0 array wrapped as schemaVersion 1",
            )
        }

        if (tag == "v5.1.0") {
            val array = JSONArray(rawSchema)
            array.put(array.getJSONObject(0))
            record(
                "duplicate-job-ids",
                array.toString(),
                "Two encoder-produced records with the same job ID.",
                "derived: first v5.1.0 array record duplicated",
            )
        } else {
            val duplicate = JSONObject(rawSchema)
            val jobs = duplicate.getJSONArray("jobs")
            jobs.put(jobs.getJSONObject(0))
            duplicate.put("jobs", jobs)
            record(
                "duplicate-job-ids",
                duplicate.toString(),
                "Two encoder-produced records with the same job ID.",
                "derived: first schema-2 record duplicated",
            )
        }

        val truncated = rawSchema.take((rawSchema.length * 0.6).toInt())
        record(
            "truncated-jobstore",
            truncated,
            "Partially written JobStore file (truncated at 60%).",
            "derived: encoder output truncated",
        )

        val unsupported = JSONObject()
        unsupported.put("schemaVersion", 99)
        unsupported.put("jobs", JSONArray())
        record(
            "unsupported-schema",
            unsupported.toString(),
            "Unsupported schema version with an empty jobs array.",
            "derived: version-only wrapper",
        )

        record(
            "empty-jobstore",
            "",
            "Empty JobStore file.",
            "derived: zero bytes",
        )

        manifest.put(
            JSONObject()
                .put("case", "missing-jobstore")
                .put("file", JSONObject.NULL)
                .put("sourceTag", tag)
                .put("sourceCommit", commit)
                .put("description", "No JobStore file exists (first run / data cleared).")
                .put("derivation", "absence"),
        )

        File(out, "manifest-jobstore.json").writeText(manifest.toString(2), Charsets.UTF_8)
        assertTrue(manifest.length() >= 8)
    }
}
