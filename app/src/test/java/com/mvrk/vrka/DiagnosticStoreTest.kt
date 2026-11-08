package com.mvrk.vrka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import com.mvrk.vrka.engine.FailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun urlSanitizationPreservesTheRouteAndRedactsSensitiveQueryParams() {
        val sensitiveUrl = "https://example.com/private/signed/token_path_secret?v=12345&token=secret_token_abc&key=api_key_xyz&signature=sig_123&quality=hd&x-amz-credential=aws_secret"
        val sanitized = DiagnosticStore.sanitizeUrl(sensitiveUrl)
        assertTrue(sanitized.startsWith("https://example.com/private/"))
        assertTrue(sanitized.contains("/[REDACTED]"))
        assertFalse(sanitized.contains("token_path_secret"))
        assertFalse(sanitized.contains("signed"))
        assertTrue(sanitized.contains("v=12345"))
        assertTrue(sanitized.contains("quality=hd"))
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("key=[REDACTED]"))
        assertTrue(sanitized.contains("signature=[REDACTED]"))
        assertTrue(sanitized.contains("x-amz-credential=[REDACTED]"))
        assertFalse(sanitized.contains("secret_token_abc"))
        assertFalse(sanitized.contains("api_key_xyz"))
        assertFalse(sanitized.contains("sig_123"))
        assertFalse(sanitized.contains("aws_secret"))
    }

    @Test
    fun sanitizationHandlesEncodedNamesCaseVariationsAndDuplicates() {
        val sanitized = DiagnosticStore.sanitizeUrl(
            "https://cdn.example.test/media/123.mp4?%74oken=first&TOKEN=second&X-Amz-Signature=sig&quality=hd&token=third",
        )

        assertTrue(sanitized.startsWith("https://cdn.example.test/media/123.mp4?"))
        assertFalse(sanitized.contains("=first"))
        assertFalse(sanitized.contains("=second"))
        assertFalse(sanitized.contains("=third"))
        assertFalse(sanitized.contains("=sig&"))
        assertTrue(sanitized.contains("X-Amz-Signature=[REDACTED]"))
        assertTrue(sanitized.contains("quality=hd"))
        assertEquals(4, Regex("\\[REDACTED]").findAll(sanitized).count())
    }

    @Test
    fun secretRedactionProtectsAuthorizationAndCookies() {
        val rawDetail = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test\nCookie: session=secret123; user=admin\npassword=super_secret\nProxy-Authorization: Basic dXNlcjpwYXNz\nX-API-Key: sensitive_api_key_999"
        val sanitized = DiagnosticStore.sanitizeText(rawDetail)
        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("super_secret"))
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9.test"))
        assertFalse(sanitized.contains("dXNlcjpwYXNz"))
        assertFalse(sanitized.contains("sensitive_api_key_999"))
        assertTrue(sanitized.contains("[REDACTED]"))
        val urlSanitized = DiagnosticStore.sanitizeText("Referer: https://private.example/watch?session=secret-value")
        assertTrue(urlSanitized.contains("private.example/watch"))
        assertFalse(urlSanitized.contains("secret-value"))
    }

    @Test
    fun embeddedSignedCdnUrlsAreSanitizedInsideAllTextFields() = runBlocking {
        val signed = "https://rr3---sn-q4flrnes.googlevideo.com/videoplayback?expire=1793494800&sig=SUPER_SECRET_SIG&ipbits=8&token=SECRET_TOKEN#fragment-secret"
        val summary = "failed at $signed after retry"
        val detail = """{"url":"$signed","fallback":"https:\/\/cdn.example.test\/media.mp4?Policy=secret-policy&amp;Key-Pair-Id=secret-key"}"""
        val title = "clip $signed"

        val sanitizedSummary = DiagnosticStore.sanitizeText(summary)
        val sanitizedDetail = DiagnosticStore.sanitizeText(detail)
        val sanitizedTitle = DiagnosticStore.sanitizeText(title)

        for (text in listOf(sanitizedSummary, sanitizedDetail, sanitizedTitle)) {
            assertFalse(text.contains("SUPER_SECRET_SIG"))
            assertFalse(text.contains("SECRET_TOKEN"))
            assertFalse(text.contains("1793494800"))
            assertFalse(text.contains("fragment-secret"))
            assertFalse(text.contains("secret-policy"))
            assertFalse(text.contains("secret-key"))
        }
        assertTrue(sanitizedSummary.contains("rr3---sn-q4flrnes.googlevideo.com/videoplayback"))
        assertTrue(sanitizedSummary.contains("sig=[REDACTED]"))
        assertTrue(sanitizedSummary.contains("token="))
        assertTrue(sanitizedSummary.contains("[REDACTED]"))
        assertTrue(sanitizedDetail.contains("cdn.example.test/media.mp4"))
    }

    @Test
    fun multiplePercentEncodedAndDuplicateUrlsInTextAreSanitized() {
        val text = "first https://a.example.test/a?%74oken=one&amp;TOKEN=two&v=1 second https://b.example.test/b?signature=sig&signature=sig2"
        val sanitized = DiagnosticStore.sanitizeText(text)

        assertFalse(sanitized.contains("one"))
        assertFalse(sanitized.contains("two"))
        assertFalse(sanitized.contains("sig="))
        assertTrue(sanitized.contains("a.example.test/a"))
        assertTrue(sanitized.contains("b.example.test/b"))
        assertTrue(sanitized.contains("v=1"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun loadingRewritesLegacyDiagnosticUrlsWithoutSecrets() = runBlocking {
        val file = java.io.File(tempFolder.root, "diagnostics.json")
        file.writeText(
            """[{"id":"legacy","timestamp":1,"jobId":"job","title":"title","stage":"transfer","failureCategory":"HTTP","summary":"failure","detail":"","url":"https://cdn.example/path/auth-secret?token=query-secret","quality":"","acquisitionMethod":"Native yt-dlp"}]""",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DiagnosticStore(tempFolder.root, scope)
        kotlinx.coroutines.delay(150)

        val persisted = file.readText()
        assertTrue(persisted.contains("https://cdn.example/path/"))
        assertFalse(persisted.contains("auth-secret"))
        assertFalse(persisted.contains("query-secret"))
    }

    @Test
    fun formattingProducesReadableReport() {
        val entry = DiagnosticEntry(
            jobId = "test-job-1",
            title = "Test Video Title",
            stage = "Direct Extraction",
            failureCategory = "HTTP",
            summary = "HTTP 403 Forbidden",
            detail = "ERROR: Server returned 403",
            url = "https://example.com/watch?v=123",
            quality = "1080p",
            acquisitionMethod = "Native yt-dlp",
        )
        val formatted = entry.toFormattedString()
        assertTrue(formatted.contains("Job ID: test-job-1"))
        assertTrue(formatted.contains("Title: Test Video Title"))
        assertTrue(formatted.contains("Stage: Direct Extraction"))
        assertTrue(formatted.contains("Category: HTTP"))
        assertTrue(formatted.contains("Quality: 1080p"))
        assertTrue(formatted.contains("Method: Native yt-dlp"))
        assertTrue(formatted.contains("Summary: HTTP 403 Forbidden"))
        assertTrue(formatted.contains("ERROR: Server returned 403"))
    }

    @Test
    fun browserFallbackDiagnosticsRedactSignedQueryValuesBeforePersistingAndExporting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = tempFolder.root
        val store = DiagnosticStore(dir, scope)
        val signedUrl = "https://www.example.test/watch?v=aqz-KE-bpKQ&t=42&list=PL9f8&index=7&token=temporary-secret&X-Amz-Signature=signature-value"

        store.record(
            DiagnosticEntry(
                id = "fallback-entry",
                jobId = "job-fallback",
                title = "Fallback clip",
                stage = "Browser Fallback",
                failureCategory = FailureCategory.BROWSER_RECOVERABLE.name,
                summary = "Browser fallback could not find a media stream",
                detail = "Cookie: session=must-not-leak",
                url = signedUrl,
                quality = "Best available",
                acquisitionMethod = DiagnosticStore.BROWSER_FALLBACK_METHOD,
            ),
        )

        val entry = store.entries.value.single()
        assertTrue(entry.url.startsWith("https://www.example.test/watch?"))
        assertTrue(entry.url.contains("v=aqz-KE-bpKQ"))
        assertTrue(entry.url.contains("t=42"))
        assertFalse(entry.url.contains("temporary-secret"))
        assertFalse(entry.url.contains("signature-value"))
        assertFalse(entry.url.contains("token=temporary-secret"))
        val exported = entry.toFormattedString()
        assertFalse(exported.contains("temporary-secret"))
        assertFalse(exported.contains("signature-value"))
        assertFalse(entry.detail.contains("must-not-leak"))
        val persisted = java.io.File(dir, "diagnostics.json").readText()
        assertFalse(persisted.contains("temporary-secret"))
        assertFalse(persisted.contains("signature-value"))
        assertFalse(persisted.contains("must-not-leak"))
        assertTrue(persisted.contains("v=aqz-KE-bpKQ"))
    }

    @Test
    fun exportSanitizesEntriesThatWereConstructedWithAnUnsignedUrl() {
        val entry = DiagnosticEntry(
            jobId = "export",
            title = "Export",
            stage = "transfer",
            failureCategory = "HTTP",
            summary = "failure",
            detail = "",
            url = "https://cdn.example.test/path?token=raw-secret&v=1",
            quality = "",
            acquisitionMethod = "Native yt-dlp",
        )

        val exported = entry.toFormattedString()

        assertFalse(exported.contains("raw-secret"))
        assertTrue(exported.contains("token=[REDACTED]"))
        assertTrue(exported.contains("v=1"))
    }

    @Test
    fun nativeFailureDiagnosticsPreserveTheRouteAndRedactQueryValues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(tempFolder.root, scope)

        store.record(
            DiagnosticEntry(
                id = "native-entry",
                jobId = "job-native",
                title = "Native clip",
                stage = "transfer",
                failureCategory = "HTTP",
                summary = "HTTP 403",
                detail = "",
                url = "https://cdn.example.test/private/path?token=query-secret&v=123",
                quality = "",
                acquisitionMethod = "Native yt-dlp",
            ),
        )

        val entry = store.entries.value.single()
        assertTrue(entry.url.contains("/private/path"))
        assertFalse(entry.url.contains("query-secret"))
        assertTrue(entry.url.contains("v=123"))
    }

    @Test
    fun failureSourceUrlIsStoredSafelyAndExportIsOptIn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(tempFolder.root, scope)
        val original = "https://page.example.test/watch?v=abc&foo=bar&token=temporary-secret&expire=1793494800&ipbits=8"
        val candidate = "https://cdn.example.test/media.mp4?sig=signature&quality=hd"

        store.record(
            DiagnosticEntry(
                id = "failed-job",
                jobId = "job-1",
                title = "Failed clip",
                stage = "Downloading",
                failureCategory = "HTTP",
                summary = "HTTP 403",
                detail = "failed",
                url = original,
                sourceUrl = original,
                candidateUrl = candidate,
                jobAttempt = 2,
                quality = "1080p",
                acquisitionMethod = DiagnosticStore.BROWSER_FALLBACK_METHOD,
            ),
        )

        val entry = store.entries.value.single()
        assertFalse(entry.url.contains("temporary-secret"))
        assertFalse(entry.sourceUrl.contains("temporary-secret"))
        assertFalse(entry.sourceUrl.contains("signature"))
        assertFalse(entry.sourceUrl.contains("1793494800"))
        assertFalse(entry.sourceUrl.contains("ipbits=8"))
        assertTrue(entry.sourceUrl.contains("v=abc"))
        assertTrue(entry.sourceUrl.contains("foo=bar"))
        assertTrue(entry.candidateUrl.contains("cdn.example.test/media.mp4"))
        assertFalse(entry.candidateUrl.contains("signature"))
        assertEquals(2, entry.jobAttempt)

        val normal = entry.toFormattedString(includeSourceUrl = false)
        assertFalse(normal.contains("foo=bar"))
        assertTrue(normal.contains("Attempt: 2"))
        val optedIn = entry.toFormattedString(includeSourceUrl = true)
        assertTrue(optedIn.contains("foo=bar"))
        assertFalse(optedIn.contains("temporary-secret"))
        assertFalse(optedIn.contains("signature"))
        assertTrue(optedIn.contains("Candidate: https://cdn.example.test/media.mp4"))

        val reloaded = DiagnosticStore(tempFolder.root, scope)
        kotlinx.coroutines.delay(150)
        val persisted = reloaded.entries.value.single()
        assertTrue(persisted.sourceUrl.contains("foo=bar"))
        assertFalse(persisted.sourceUrl.contains("temporary-secret"))
        assertEquals(2, persisted.jobAttempt)
    }

    @Test
    fun successfulRetryClearsUrlsButKeepsFailureContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(tempFolder.root, scope)
        store.record(
            DiagnosticEntry(
                id = "retry-failure",
                jobId = "retry-job",
                title = "Retry clip",
                stage = "Downloading",
                failureCategory = "HTTP",
                summary = "old failure",
                detail = "failed once",
                url = "https://page.example.test/watch?v=1",
                sourceUrl = "https://page.example.test/watch?v=1",
                quality = "1080p",
                acquisitionMethod = "Native yt-dlp",
            ),
        )

        store.clearUrlsForJob("retry-job")

        val entry = store.entries.value.single()
        assertEquals("", entry.url)
        assertEquals("", entry.sourceUrl)
        assertEquals("", entry.candidateUrl)
        assertEquals("old failure", entry.summary)
    }

    @Test
    fun legacyDiagnosticsWithoutSourceFieldsRemainCompatible() = runBlocking {
        val file = java.io.File(tempFolder.root, "diagnostics.json")
        file.writeText(
            """[{"id":"legacy","timestamp":1,"jobId":"job","title":"title","stage":"transfer","failureCategory":"HTTP","summary":"failure","detail":"","url":"https://cdn.example/path?token=secret","quality":"","acquisitionMethod":"Native yt-dlp"}]""",
        )
        val store = DiagnosticStore(tempFolder.root, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        kotlinx.coroutines.delay(150)
        val entry = store.entries.value.single()
        assertEquals("", entry.sourceUrl)
        assertEquals("", entry.candidateUrl)
        assertEquals(0, entry.jobAttempt)
        assertFalse(entry.url.contains("secret"))
    }

    @Test
    fun diagnosticReportRecordsTheRuntimeAndVersionThatRanTheJob() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = tempFolder.root
        val store = DiagnosticStore(dir, scope)

        store.record(
            DiagnosticEntry(
                id = "runtime-entry",
                jobId = "job-runtime",
                title = "Runtime clip",
                stage = "transfer",
                failureCategory = "HTTP",
                summary = "HTTP 403",
                detail = "",
                url = "https://cdn.example.test/clip",
                quality = "1080p",
                acquisitionMethod = "Native yt-dlp",
                runtime = "Custom example/yt-dlp nightly • yt-dlp 2026.10.01",
            ),
        )

        val entry = store.entries.value.single()
        assertTrue(
            entry.toFormattedString()
                .contains("Runtime: Custom example/yt-dlp nightly • yt-dlp 2026.10.01"),
        )
        val reloaded = DiagnosticStore(dir, scope)
        kotlinx.coroutines.delay(150)
        assertEquals(
            "Custom example/yt-dlp nightly • yt-dlp 2026.10.01",
            reloaded.entries.value.single().runtime,
        )
    }

    @Test
    fun diagnosticReportOmitsTheRuntimeLineWhenNoRuntimeWasRecorded() {
        val entry = DiagnosticEntry(
            jobId = "job-legacy",
            title = "Legacy entry",
            stage = "transfer",
            failureCategory = "HTTP",
            summary = "HTTP 403",
            detail = "",
            url = "https://example.com/watch?v=1",
            quality = "",
            acquisitionMethod = "Native yt-dlp",
        )
        assertFalse(entry.toFormattedString().contains("Runtime:"))
    }

    @Test
    fun storePersistenceBoundedRetentionAndClear() = runBlocking {        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = tempFolder.root
        val store = DiagnosticStore(dir, scope)

        for (i in 1..55) {
            store.record(
                DiagnosticEntry(
                    id = "entry-$i",
                    timestamp = i.toLong(),
                    jobId = "job-$i",
                    title = "Title $i",
                    stage = "Direct Extraction",
                    failureCategory = "HTTP",
                    summary = "Error $i",
                    detail = "Details $i",
                    url = "https://example.com/watch?v=$i",
                    quality = "1080p",
                    acquisitionMethod = "Native yt-dlp",
                )
            )
        }

        val entries = store.entries.value
        assertEquals(50, entries.size)
        assertEquals("entry-55", entries.first().id)
        assertFalse(entries.any { it.id == "entry-1" })

        val reloadedStore = DiagnosticStore(dir, scope)
        kotlinx.coroutines.delay(150)
        assertEquals(50, reloadedStore.entries.value.size)
        assertEquals("entry-55", reloadedStore.entries.value.first().id)

        reloadedStore.clear()
        assertEquals(0, reloadedStore.entries.value.size)
    }
}
