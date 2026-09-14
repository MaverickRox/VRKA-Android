package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FailureClassifier.
 * Verifies error classification and browser-fallback eligibility.
 */
class FailureClassifierTest {

    // --- Classification ---

    @Test
    fun `classifies DRM errors as DRM`() {
        assertEquals(FailureCategory.DRM, classifyDownloadError("ERROR: This video is DRM protected"))
        assertEquals(FailureCategory.DRM, classifyDownloadError("Digital Rights Management detected"))
        assertEquals(FailureCategory.DRM, classifyDownloadError("Media is protected by DRM"))
    }

    @Test
    fun `classifies impersonation errors`() {
        assertEquals(FailureCategory.IMPERSONATION, classifyDownloadError("curl_cffi is not installed"))
        assertEquals(FailureCategory.IMPERSONATION, classifyDownloadError("Unsupported impersonation target"))
    }

    @Test
    fun `classifies Cloudflare challenges`() {
        assertEquals(FailureCategory.CLOUDFLARE, classifyDownloadError("Cloudflare challenge detected"))
        assertEquals(FailureCategory.CLOUDFLARE, classifyDownloadError("Just a moment..."))
        assertEquals(FailureCategory.CLOUDFLARE, classifyDownloadError("Attention Required! | Cloudflare"))
        assertEquals(FailureCategory.CLOUDFLARE, classifyDownloadError("cf-chl- bypass failed"))
    }

    @Test
    fun `classifies cookie and auth errors`() {
        assertEquals(FailureCategory.AUTH_REQUIRED, classifyDownloadError("Sign in to confirm your age"))
        assertEquals(FailureCategory.AUTH_REQUIRED, classifyDownloadError("Login required to view this video"))
        assertEquals(FailureCategory.COOKIES, classifyDownloadError("could not copy chrome cookie database"))
        assertEquals(FailureCategory.COOKIES, classifyDownloadError("no useful cookies found in browser session"))
    }

    @Test
    fun `classifies expired URL errors`() {
        assertEquals(FailureCategory.EXPIRED, classifyDownloadError("URL has expired"))
        assertEquals(FailureCategory.EXPIRED, classifyDownloadError("Signature has expired"))
        assertEquals(FailureCategory.EXPIRED, classifyDownloadError("403 Forbidden: expired url"))
    }

    @Test
    fun `classifies timeout errors`() {
        assertEquals(FailureCategory.NETWORK_ERROR, classifyDownloadError("The connection timed out"))
        assertEquals(FailureCategory.NETWORK_ERROR, classifyDownloadError("Read operation timed out"))
        assertEquals(FailureCategory.NETWORK_ERROR, classifyDownloadError("Connection timeout"))
    }

    @Test
    fun `classifies HTTP errors`() {
        assertEquals(FailureCategory.HTTP, classifyDownloadError("HTTP Error 403: Forbidden"))
        assertEquals(FailureCategory.HTTP, classifyDownloadError("Unable to download webpage"))
        assertEquals(FailureCategory.HTTP, classifyDownloadError("Connection reset by peer"))
        assertEquals(FailureCategory.HTTP, classifyDownloadError("403 forbidden"))
    }

    @Test
    fun `classifies unsupported URL errors`() {
        assertEquals(FailureCategory.UNSUPPORTED, classifyDownloadError("Unsupported URL: https://example.com"))
        assertEquals(FailureCategory.UNSUPPORTED, classifyDownloadError("No suitable extractor found"))
        assertEquals(FailureCategory.UNSUPPORTED, classifyDownloadError("is not a valid url"))
    }

    @Test
    fun `classifies unknown errors`() {
        assertEquals(FailureCategory.UNKNOWN, classifyDownloadError("Something completely unexpected"))
        assertEquals(FailureCategory.UNKNOWN, classifyDownloadError(""))
    }

    // --- Eligibility ---

    @Test
    fun `DRM is never browser-recoverable`() {
        assertFalse(isFailureBrowserRecoverable(FailureCategory.DRM))
    }

    @Test
    fun `impersonation is never browser-recoverable`() {
        assertFalse(isFailureBrowserRecoverable(FailureCategory.IMPERSONATION))
    }

    @Test
    fun `timeout is never browser-recoverable`() {
        assertFalse(isFailureBrowserRecoverable(FailureCategory.TIMEOUT))
    }

    @Test
    fun `cloudflare is browser-recoverable`() {
        assertTrue(isFailureBrowserRecoverable(FailureCategory.CLOUDFLARE))
    }

    @Test
    fun `HTTP errors are browser-recoverable`() {
        assertTrue(isFailureBrowserRecoverable(FailureCategory.HTTP))
    }

    @Test
    fun `expired errors are browser-recoverable`() {
        assertTrue(isFailureBrowserRecoverable(FailureCategory.EXPIRED))
    }

    @Test
    fun `cookie errors are browser-recoverable`() {
        assertTrue(isFailureBrowserRecoverable(FailureCategory.COOKIES))
    }

    @Test
    fun `YouTube URLs are browser-recoverable for bot or auth challenges but stay direct on other failures per Phase 7`() {
        val ytUrls = listOf(
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ",
            "https://music.youtube.com/watch?v=test",
            "https://m.youtube.com/watch?v=12345",
        )
        for (url in ytUrls) {
            assertTrue("Should be recognized as yt-dlp native target: $url", isYtdlpNativeTarget(url))
            // Auth/bot challenges and HTTP restrictions are browser-recoverable per Phase 7
            assertTrue(isFailureBrowserRecoverable(FailureCategory.COOKIES, targetUrl = url))
            assertTrue(isFailureBrowserRecoverable(FailureCategory.CLOUDFLARE, targetUrl = url))
            assertTrue(isFailureBrowserRecoverable(FailureCategory.HTTP, targetUrl = url))
            // Other failures stay direct / terminal
            assertFalse(isFailureBrowserRecoverable(FailureCategory.UNSUPPORTED, targetUrl = url))
            assertFalse(isFailureBrowserRecoverable(FailureCategory.TIMEOUT, targetUrl = url))
            assertFalse(isFailureBrowserRecoverable(FailureCategory.UNKNOWN, targetUrl = url))
            assertFalse(isFailureBrowserRecoverable(FailureCategory.DRM, targetUrl = url))
            assertFalse(isFailureBrowserRecoverable(FailureCategory.IMPERSONATION, targetUrl = url))
        }
    }

    @Test
    fun `transfer failure after resolution is never browser-recoverable`() {
        // Destination marker in output
        assertFalse(isFailureBrowserRecoverable(
            category = FailureCategory.HTTP,
            executionOutput = "[download] Destination: /storage/emulated/0/media.mp4\nHTTP Error 403",
        ))
        // Title marker in output
        assertFalse(isFailureBrowserRecoverable(
            category = FailureCategory.HTTP,
            executionOutput = "__vrka_title__Big Buck Bunny\nHTTP Error 403: Forbidden",
        ))
        // hasTransferStarted flag
        assertFalse(isFailureBrowserRecoverable(
            category = FailureCategory.HTTP,
            hasTransferStarted = true,
        ))
        // hasResolvedMediaUrl flag
        assertFalse(isFailureBrowserRecoverable(
            category = FailureCategory.HTTP,
            hasResolvedMediaUrl = true,
        ))
    }

    @Test
    fun `command-line debug echo of print options is not mistaken for transfer started`() {
        val debugOutput = """
            [debug] Command-line config: ['--newline', '--progress', '--print', 'before_dl:__VRKA_TITLE__%(title)s', '--print', 'after_move:__VRKA_OUTPUT__%(filepath)s']
            [generic] Extracting URL: https://missav.ws/en/jur-787
            [generic] jur-787: Downloading webpage
            ERROR: [generic] Got HTTP Error 403 caused by Cloudflare anti-bot challenge
        """.trimIndent()
        assertFalse(isTransferFailureAfterResolution(executionOutput = debugOutput))
        assertTrue(isFailureBrowserRecoverable(
            category = FailureCategory.CLOUDFLARE,
            executionOutput = debugOutput,
            targetUrl = "https://missav.ws/en/jur-787",
        ))
    }

    @Test
    fun `unsupported is recoverable when generic extractor fetched a page`() {
        assertTrue(isFailureBrowserRecoverable(
            category = FailureCategory.UNSUPPORTED,
            errorMessage = "ERROR: Unsupported URL",
            executionOutput = "[generic] Falling back on generic information extractor\n[generic] Downloading webpage",
        ))
        assertTrue(isFailureBrowserRecoverable(
            category = FailureCategory.UNSUPPORTED,
            errorMessage = "ERROR: Unsupported URL",
            executionOutput = "[generic] Extracting information",
        ))
    }

    @Test
    fun `unsupported is not recoverable for bare unsupported URL without fetch evidence`() {
        assertFalse(isFailureBrowserRecoverable(
            category = FailureCategory.UNSUPPORTED,
            errorMessage = "ERROR: Unsupported URL: foo://bar",
            executionOutput = "",
        ))
    }

    @Test
    fun `prior categories inherit browser recoverability`() {
        assertTrue(isFailureBrowserRecoverable(
            category = FailureCategory.UNSUPPORTED,
            errorMessage = "Unsupported URL",
            priorCategories = listOf(FailureCategory.HTTP),
        ))
    }

    @Test
    fun `unknown errors are not browser-recoverable`() {
        assertFalse(isFailureBrowserRecoverable(FailureCategory.UNKNOWN))
    }

    // --- classifyAndCheckRecoverable convenience ---

    @Test
    fun `classifyAndCheckRecoverable returns correct pair`() {
        val (cat, recoverable) = classifyAndCheckRecoverable("Cloudflare challenge detected")
        assertEquals(FailureCategory.CLOUDFLARE, cat)
        assertTrue(recoverable)

        val (cat2, recoverable2) = classifyAndCheckRecoverable("This video is DRM protected")
        assertEquals(FailureCategory.DRM, cat2)
        assertFalse(recoverable2)
    }

    // --- Native Replay Failure Classification ---

    @Test
    fun `classifies native replay TLS handshake failure`() {
        val reason = classifyNativeReplayFailure("SSLV3_ALERT_HANDSHAKE_FAILURE: The server may not support the current cipher list")
        assertEquals(NativeReplayFailureReason.TLS_HANDSHAKE_FAILURE, reason)
        assertTrue(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = true))
        assertFalse(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = false))
    }

    @Test
    fun `classifies native replay HTTP 403 Forbidden`() {
        val reason = classifyNativeReplayFailure("HTTP Error 403: Forbidden")
        assertEquals(NativeReplayFailureReason.HTTP_FORBIDDEN_OR_AUTH, reason)
        assertTrue(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = true))
        assertFalse(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = false))
    }

    @Test
    fun `classifies native replay connection reset`() {
        val reason = classifyNativeReplayFailure("Connection reset by peer")
        assertEquals(NativeReplayFailureReason.CONNECTION_RESET, reason)
        assertTrue(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = true))
    }

    @Test
    fun `classifies native replay DRM as non-eligible`() {
        val reason = classifyNativeReplayFailure("DRM protected stream; decryption key required")
        assertEquals(NativeReplayFailureReason.DRM_PROTECTED, reason)
        assertFalse(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = true))
    }

    @Test
    fun `classifies native replay 404 as non-eligible client error`() {
        val reason = classifyNativeReplayFailure("HTTP Error 404: Not Found")
        assertEquals(NativeReplayFailureReason.HTTP_CLIENT_ERROR, reason)
        assertFalse(isEligibleForGeckoTransport(reason, isBrowserDerivedCandidate = true))
    }

    // --- 16 Required Explicit Test Cases ---

    @Test
    fun `Case 01 - Unable to extract flashvars results in BROWSER_RECOVERABLE`() {
        val err = "ERROR: [generic] Unable to extract flashvars; please report this issue on [private URL]"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.BROWSER_RECOVERABLE, category)
        assertTrue(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 02 - KVS player identification followed by flashvars failure results in BROWSER_RECOVERABLE`() {
        val err = "ERROR: [kvs] Unable to extract flashvars"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.BROWSER_RECOVERABLE, category)
        assertTrue(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 03 - Generic player parsing failure results in BROWSER_RECOVERABLE`() {
        val err = "ERROR: Failed to parse player data"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.BROWSER_RECOVERABLE, category)
        assertTrue(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 04 - Generic embedded-player extraction failure results in BROWSER_RECOVERABLE`() {
        val err = "ERROR: embedded-player extraction failed"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.BROWSER_RECOVERABLE, category)
        assertTrue(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 05 - Generic webpage parser failure results in BROWSER_RECOVERABLE`() {
        val err = "ERROR: Failed to parse webpage data: No video formats found"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.BROWSER_RECOVERABLE, category)
        assertTrue(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 06 - DNS failure results in NETWORK_ERROR`() {
        val err = "ERROR: <urlopen error [Errno -2] Name or service not known>"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.NETWORK_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 07 - Connection timeout results in NETWORK_ERROR`() {
        val err = "ERROR: The read operation timed out"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.NETWORK_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 08 - TLS certificate failure results in TLS_ERROR`() {
        val err = "ERROR: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.TLS_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 09 - User cancellation results in CANCELLED`() {
        val err = "Operation cancelled by user"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.CANCELLED, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 10 - Storage failure results in STORAGE_ERROR`() {
        val err = "IOException: No space left on device"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.STORAGE_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 11 - FFmpeg failure after successful extraction results in POST_EXTRACTION_ERROR`() {
        val err = "ffmpeg error: conversion failed with exit code 1"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.POST_EXTRACTION_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 12 - Genuine authentication failure results in AUTH_REQUIRED`() {
        val err = "ERROR: Sign in to confirm your age"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.AUTH_REQUIRED, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err, targetUrl = "https://example.com/video"))
    }

    @Test
    fun `Case 13 - Internal programming exception results in INTERNAL_ERROR`() {
        val err = "java.lang.NullPointerException: parameter cannot be null"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.INTERNAL_ERROR, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 14 - Unknown unrelated error results in UNKNOWN`() {
        val err = "some unrecognized arbitrary failure"
        val category = classifyDownloadError(err)
        assertEquals(FailureCategory.UNKNOWN, category)
        assertFalse(isFailureBrowserRecoverable(category, errorMessage = err))
    }

    @Test
    fun `Case 15 - Existing 123AV browser fallback path remains valid`() {
        val cfError = "ERROR: [generic] Got HTTP Error 403 caused by Cloudflare anti-bot challenge"
        val (cat1, rec1) = classifyAndCheckRecoverable(
            errorMessage = cfError,
            targetUrl = "https://123av.com/en/v/snos-313-uncensored-leaked",
        )
        assertEquals(FailureCategory.CLOUDFLARE, cat1)
        assertTrue("123AV Cloudflare challenge must remain browser-recoverable", rec1)

        val httpError = "HTTP Error 403: Forbidden"
        val (cat2, rec2) = classifyAndCheckRecoverable(
            errorMessage = httpError,
            targetUrl = "https://123av.com/en/v/snos-313-uncensored-leaked",
        )
        assertEquals(FailureCategory.HTTP, cat2)
        assertTrue("123AV HTTP 403 must remain browser-recoverable", rec2)
    }

    @Test
    fun `Case 16 - Fallback loop protection prevents recursive fallback activation`() {
        // When media URL is already resolved (after browser fallback handoff),
        // subsequent failure must NEVER be browser-recoverable.
        val failureAfterHandoff = isFailureBrowserRecoverable(
            category = FailureCategory.BROWSER_RECOVERABLE,
            errorMessage = "Stream disconnected",
            hasResolvedMediaUrl = true,
        )
        assertFalse("Resolved media cannot trigger browser fallback again", failureAfterHandoff)

        val failureAfterTransferStarted = isFailureBrowserRecoverable(
            category = FailureCategory.BROWSER_RECOVERABLE,
            errorMessage = "HTTP Error 403",
            hasTransferStarted = true,
        )
        assertFalse("Started transfer cannot trigger browser fallback again", failureAfterTransferStarted)
    }
}
