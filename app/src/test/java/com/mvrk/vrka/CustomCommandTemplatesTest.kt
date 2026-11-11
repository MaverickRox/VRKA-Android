package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCommandTemplatesTest {
    @Test
    fun h264CompatibilityModeIsOffByDefault() {
        assertFalse(AppSettings().h264CompatibilityMode)
    }

    @Test
    fun quotedArgumentParsingPreservesEachTokenWithoutShellExpansion() {
        val parsed = CustomCommandPolicy.parseArgumentLine(
            "--limit-rate \"2 MB/s\" --add-header 'X-Test: a b' --add-header \"X-Escaped: hello \\\"world\\\"\" --retries 3\\ 0",
        )
        assertEquals(
            listOf("--limit-rate", "2 MB/s", "--add-header", "X-Test: a b", "--add-header", "X-Escaped: hello \"world\"", "--retries", "3 0"),
            parsed,
        )
    }

    @Test
    fun unmatchedQuotesAndIncompleteEscapesAreRejected() {
        assertEquals(
            "Template contains an unmatched quote.",
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CustomCommandPolicy.parseArgumentLine("--limit-rate \"2M")
            }.message,
        )
        assertEquals(
            "Template ends with an incomplete escape.",
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CustomCommandPolicy.parseArgumentLine("--limit-rate 2M\\")
            }.message,
        )
    }

    @Test
    fun shellExecutionAndOutputPathOptionsAreBlocked() {
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--exec", "echo pwned")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--EXEC", "echo pwned")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--exec=after_move:whoami")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("-o", "/sdcard/out.mp4")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("-P", "/sdcard")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--external-downloader", "curl")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--downloader", "curl")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--Downloader=aria2c")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--config", "/tmp/config")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--Config-Locations", "/tmp/config")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--proxy", "http://127.0.0.1:8080")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--netrc")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--no-check-certificates")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--prefer-insecure")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--add-header", "Authorization:Bearer secret")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--add-headers", "Authorization:Bearer secret")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--add-header", "X-Device-Token:secret")))
        assertNull(CustomCommandPolicy.validateTemplate("Safe", listOf("--limit-rate", "2M")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--add-header", "Cookie:session=abc")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--add-header=Cookie:session=abc")))
        assertNull(CustomCommandPolicy.validateTemplate("Safe", listOf("--add-header", "X-Requested-With:VRKA")))
    }

    @Test
    fun templateCannotAppendAnotherPositionalUrl() {
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--no-playlist", "https://other.example/video")))
        assertNotNull(CustomCommandPolicy.validateTemplate("Unsafe", listOf("--limit-rate")))
    }

    @Test
    fun externalUrlIsValidatedAsOneHttpArgumentEvenWhenItContainsOptionText() {
        val signedUrl = "https://cdn.example/video?next=--exec%20touch"
        assertTrue(CustomCommandPolicy.isHttpUrl(signedUrl))
        assertFalse(CustomCommandPolicy.isHttpUrl("https://cdn.example/video --exec whoami"))
        assertFalse(CustomCommandPolicy.isHttpUrl("file:///tmp/video"))
        assertFalse(CustomCommandPolicy.isHttpUrl("https://user:pass@cdn.example/video"))
    }

    @Test
    fun deletingSelectedTemplateSelectsRemainingTemplateAndLastDeleteRestoresStarter() {
        val first = YtDlpCommandTemplate(id = "first", name = "First", arguments = listOf("--no-playlist"))
        val second = YtDlpCommandTemplate(id = "second", name = "Second", arguments = listOf("--limit-rate", "2M"))

        val afterDelete = CustomCommandPolicy.deleteTemplate(
            templates = listOf(first, second),
            selectedTemplateId = "first",
            deleteTemplateId = "first",
        )
        assertEquals(listOf(second), afterDelete.templates)
        assertEquals("second", afterDelete.selectedTemplateId)

        val afterLastDelete = CustomCommandPolicy.deleteTemplate(
            templates = listOf(second),
            selectedTemplateId = "second",
            deleteTemplateId = "second",
        )
        assertEquals(listOf(CustomCommandPolicy.starterTemplate()), afterLastDelete.templates)
        assertEquals(CustomCommandPolicy.starterTemplate().id, afterLastDelete.selectedTemplateId)
    }
}
