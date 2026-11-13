package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNameAllocatorTest {

    @Test
    fun availableNameIsKeptWhenThereIsNoCollision() {
        assertEquals("clip.mp4", OutputNameAllocator.next(mutableSetOf(), "clip.mp4"))
    }

    @Test
    fun existingNamesAreReservedAcrossABatch() {
        val existing = mutableSetOf("clip.mp4")
        val first = OutputNameAllocator.next(existing, "clip.mp4")
        existing += first
        val second = OutputNameAllocator.next(existing, "clip.mp4")

        assertEquals("clip (2).mp4", first)
        assertEquals("clip (3).mp4", second)
        assertTrue(first != second)
    }

    @Test
    fun collisionSuffixKeepsTheExtension() {
        val name = OutputNameAllocator.next(mutableSetOf("song (2).mp3"), "song (2).mp3")
        assertEquals("song (2) (2).mp3", name)
    }

    @Test
    fun safeOutputNames_sanitizesLongTitlesWithEmojisAndHashtags() {
        val rawTitle = "🔥 AMAZING DANCE VIDEO! 💃🕺 #viral #trend #dance ".repeat(10) + "\r\nLine 2 with \t tabs & special: <tag>:*?\"/\\|"
        val sanitized = SafeOutputNames.sanitize(rawTitle, "mp4")

        assertTrue("Sanitized name must end with .mp4", sanitized.endsWith(".mp4"))
        val stem = sanitized.substringBeforeLast('.')
        assertTrue("Stem UTF-8 bytes must not exceed 120 bytes (got: ${stem.toByteArray(Charsets.UTF_8).size})", stem.toByteArray(Charsets.UTF_8).size <= 120)
        assertFalse("Stem must not contain newlines or tabs", stem.contains('\n') || stem.contains('\r') || stem.contains('\t'))
        assertFalse("Stem must not contain illegal file characters", stem.any { it in "\\/:*?\"<>|" })
        // Verify valid UTF-8 string without orphan surrogate
        assertEquals(stem, String(stem.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun safeOutputNames_cjkAndCyrillicTitlesAreNormalizedToNfc() {
        // Japanese + Cyrillic + Emojis
        val cjkTitle = "東京の夜景 ✨ Токио ночью 🗼 超高画質 4K"
        val sanitized = SafeOutputNames.sanitize(cjkTitle, "mkv")

        assertTrue(sanitized.endsWith(".mkv"))
        val stem = sanitized.substringBeforeLast('.')
        assertTrue(stem.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(java.text.Normalizer.isNormalized(stem, java.text.Normalizer.Form.NFC))
        assertTrue(stem.contains("東京の夜景"))
        assertTrue(stem.contains("Токио ночью"))
    }
}
