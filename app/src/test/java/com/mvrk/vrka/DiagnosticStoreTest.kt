package com.mvrk.vrka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
        val sensitiveUrl = "https://example.com/video?v=12345&token=secret_token_abc&key=api_key_xyz&signature=sig_123&quality=hd"
        val sanitized = DiagnosticStore.sanitizeUrl(sensitiveUrl)
        assertTrue(sanitized.contains("v=12345"))
        assertTrue(sanitized.contains("quality=hd"))
        assertTrue(sanitized.contains("token=%5Bredacted%5D") || sanitized.contains("token=[redacted]"))
        assertTrue(sanitized.contains("key=%5Bredacted%5D") || sanitized.contains("key=[redacted]"))
        assertTrue(sanitized.contains("signature=%5Bredacted%5D") || sanitized.contains("signature=[redacted]"))
        assertFalse(sanitized.contains("secret_token_abc"))
        assertFalse(sanitized.contains("api_key_xyz"))
        assertFalse(sanitized.contains("sig_123"))
    }

    @Test
    fun secretRedactionProtectsAuthorizationAndCookies() {
        val rawDetail = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test\nCookie: session=secret123; user=admin\npassword=super_secret"
        val sanitized = DiagnosticStore.sanitizeText(rawDetail)
        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("super_secret"))
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9.test"))
        assertTrue(sanitized.contains("[redacted]"))
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
    fun storePersistenceBoundedRetentionAndClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
