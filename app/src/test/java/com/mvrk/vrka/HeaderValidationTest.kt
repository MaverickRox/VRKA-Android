package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderValidationTest {

    @Test
    fun validHeaderNamesPassValidation() {
        val validNames = listOf(
            "Authorization",
            "Content-Type",
            "X-Custom-Header_1",
            "User-Agent",
            "Accept-Language",
            "Referer",
            "Origin",
            "X-API-Key",
        )
        for (name in validNames) {
            val result = HeaderValidation.validateHeaderName(name)
            assertTrue("Expected '$name' to be valid, but got: $result", result is HeaderValidationResult.Valid)
        }
    }

    @Test
    fun blankOrInvalidHeaderNamesFailValidation() {
        val invalidNames = listOf(
            "",
            "   ",
            "Header Name", // space not allowed in token
            "Header:Value", // colon not allowed
            "Header@Name", // @ not allowed
            "Header(Name)", // parens not allowed
            "Header/Name", // slash not allowed
        )
        for (name in invalidNames) {
            val result = HeaderValidation.validateHeaderName(name)
            assertTrue("Expected '$name' to be invalid", result is HeaderValidationResult.Invalid)
        }
    }

    @Test
    fun crlfInjectionInHeaderNameOrValueIsRejected() {
        val crlfNames = listOf(
            "Header\rName",
            "Header\nName",
            "Header\r\nName",
            "Header%0dName",
            "Header%0aName",
            "Header%0D%0AName",
        )
        for (name in crlfNames) {
            val result = HeaderValidation.validateHeaderName(name)
            assertTrue("Expected CRLF in name '$name' to be rejected", result is HeaderValidationResult.Invalid)
            assertTrue((result as HeaderValidationResult.Invalid).reason.contains("CRLF", ignoreCase = true))
        }

        val crlfValues = listOf(
            "Value\rInjected",
            "Value\nInjected",
            "Value\r\nInjected",
            "Value%0dInjected",
            "Value%0aInjected",
            "Value%0D%0AInjected",
        )
        for (value in crlfValues) {
            val result = HeaderValidation.validateHeaderValue(value)
            assertTrue("Expected CRLF in value '$value' to be rejected", result is HeaderValidationResult.Invalid)
            assertTrue((result as HeaderValidationResult.Invalid).reason.contains("CRLF", ignoreCase = true))
        }
    }

    @Test
    fun sensitiveHeadersAreCorrectlyIdentified() {
        assertTrue(HeaderValidation.isSensitiveHeader("Authorization"))
        assertTrue(HeaderValidation.isSensitiveHeader("authorization"))
        assertTrue(HeaderValidation.isSensitiveHeader("AUTHORIZATION"))
        assertTrue(HeaderValidation.isSensitiveHeader("Proxy-Authorization"))
        assertTrue(HeaderValidation.isSensitiveHeader("Cookie"))
        assertTrue(HeaderValidation.isSensitiveHeader("Set-Cookie"))
        assertTrue(HeaderValidation.isSensitiveHeader("X-API-Key"))
        assertTrue(HeaderValidation.isSensitiveHeader("Api-Key"))
        assertTrue(HeaderValidation.isSensitiveHeader("X-Auth-Token"))
        assertTrue(HeaderValidation.isSensitiveHeader("Secret-Token"))

        assertFalse(HeaderValidation.isSensitiveHeader("User-Agent"))
        assertFalse(HeaderValidation.isSensitiveHeader("Accept"))
        assertFalse(HeaderValidation.isSensitiveHeader("Content-Type"))
        assertFalse(HeaderValidation.isSensitiveHeader("Referer"))
        assertFalse(HeaderValidation.isSensitiveHeader("Origin"))
    }

    @Test
    fun precedenceResolutionRespectsHierarchy() {
        val request = DownloadRequest(
            url = "https://example.com/video",
            referer = "https://typed-referer.example",
            origin = "https://typed-origin.example",
            customHeaders = mapOf(
                "Referer" to "https://custom-referer.example",
                "Origin" to "https://custom-origin.example",
                "X-Custom-1" to "CustomValue1",
                "User-Agent" to "CustomUA",
            ),
            resolvedHeaders = mapOf(
                "REFERER" to "https://resolved-referer.example",
                "USER-AGENT" to "ResolvedUA",
                "Cookie" to "session=secret123",
            ),
        )

        val effective = HeaderValidation.resolveEffectiveHeaders(request)

        // Resolved headers win over everything else
        assertEquals("https://resolved-referer.example", effective["Referer"])
        assertEquals("ResolvedUA", effective["User-Agent"])
        assertEquals("session=secret123", effective["Cookie"])

        // Origin was not in resolved, so typed origin wins over customHeaders
        assertEquals("https://typed-origin.example", effective["Origin"])

        // Custom header is preserved
        assertEquals("CustomValue1", effective["X-Custom-1"])
    }

    @Test
    fun caseInsensitiveDeduplicationPreservesLastOrSpecific() {
        val pairs = listOf(
            "Content-Type" to "text/plain",
            "content-type" to "application/json",
            "X-Track" to "123",
            "x-track" to "456",
        )
        val parsed = HeaderValidation.parseHeaderPairs(pairs)
        assertEquals(2, parsed.size)
        assertEquals("application/json", parsed["content-type"])
        assertEquals("456", parsed["x-track"])
    }

    @Test
    fun textRedactionHidesSensitiveHeaderValues() {
        val input = "Header Dump:\nAuthorization: Bearer secret_jwt_payload_here\nCookie: session=abc12345; id=xyz\nAccept: */*"
        val redacted = HeaderValidation.redactSensitiveHeaderInText(input)

        assertFalse(redacted.contains("secret_jwt_payload_here"))
        assertFalse(redacted.contains("abc12345"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("Accept: */*"))
    }
}
