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
    fun urlSanitizationRedactsSensitiveQueryParams() {
        val sensitiveUrl = "https://example.com/private/signed/token_path_secret?v=12345&token=secret_token_abc&key=api_key_xyz&signature=sig_123&quality=hd&x-amz-credential=aws_secret"
        val sanitized = DiagnosticStore.sanitizeUrl(sensitiveUrl)
        assertTrue(sanitized.contains("https://example.com/[redacted path]"))
        assertFalse(sanitized.contains("token_path_secret"))
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
        assertFalse(urlSanitized.contains("private.example"))
        assertFalse(urlSanitized.contains("secret-value"))
    }

    @Test
    fun loadingRewritesLegacyDiagnosticUrlsWithoutTheirPathsOrSecrets() = runBlocking {
        val file = java.io.File(tempFolder.root, "diagnostics.json")
        file.writeText(
            """[{"id":"legacy","timestamp":1,"jobId":"job","title":"title","stage":"transfer","failureCategory":"HTTP","summary":"failure","detail":"","url":"https://cdn.example/path/auth-secret?token=query-secret","quality":"","acquisitionMethod":"Native yt-dlp"}]""",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DiagnosticStore(tempFolder.root, scope)
        kotlinx.coroutines.delay(150)

        val persisted = file.readText()
        assertTrue(persisted.contains("https://cdn.example/[redacted path]"))
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
    fun browserFallbackDiagnosticsKeepTheExactOriginalUrlWithItsQueryString() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = tempFolder.root
        val store = DiagnosticStore(dir, scope)
        val exactUrl = "https://www.example.test/watch?v=aqz-KE-bpKQ&t=42&list=PL9f8&index=7"

        store.record(
            DiagnosticEntry(
                id = "fallback-entry",
                jobId = "job-fallback",
                title = "Fallback clip",
                stage = "Browser Fallback",
                failureCategory = FailureCategory.BROWSER_RECOVERABLE.name,
                summary = "Browser fallback could not find a media stream",
                detail = "Cookie: session=must-not-leak",
                url = exactUrl,
                quality = "Best available",
                acquisitionMethod = DiagnosticStore.BROWSER_FALLBACK_METHOD,
            ),
        )

        val entry = store.entries.value.single()
        assertEquals(exactUrl, entry.url)
        assertTrue(entry.toFormattedString().contains("URL: $exactUrl"))
        assertFalse(entry.detail.contains("must-not-leak"))
        val persisted = java.io.File(dir, "diagnostics.json").readText()
        assertTrue(persisted.contains(exactUrl))
        assertFalse(persisted.contains("must-not-leak"))
    }

    @Test
    fun nativeFailureDiagnosticsStillRedactSensitivePathsAndQueryValues() = runBlocking {
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
        assertTrue(entry.url.contains("[redacted path]"))
        assertFalse(entry.url.contains("private/path"))
        assertFalse(entry.url.contains("query-secret"))
        assertTrue(entry.url.contains("v=123"))
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
