package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadAccountingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun playlistPublishedMediaSizeIsTheSumOfAllMediaOutputs() {
        val first = tempFolder.newFile("001.mp4").apply { writeBytes(ByteArray(1_000_000)) }
        val second = tempFolder.newFile("002.webm").apply { writeBytes(ByteArray(2_000_000)) }
        val subtitles = tempFolder.newFile("001.en.srt").apply { writeBytes(ByteArray(500_000)) }

        assertEquals(3_000_000L, DownloadAccounting.mediaOutputBytes(listOf(first, second, subtitles)))
    }

    @Test
    fun sidecarsAreNeverCountedAsMedia() {
        val subtitles = tempFolder.newFile("subtitle.vtt").apply { writeBytes(ByteArray(10)) }
        assertFalse(DownloadAccounting.isMediaExtension("vtt"))
        assertNull(DownloadAccounting.mediaOutputBytes(listOf(subtitles)))
    }

    @Test
    fun allDeclaredVideoAndAudioExtensionsAreMedia() {
        listOf("mp4", "mkv", "webm", "mov", "m4v", "ts", "mp3", "m4a", "mka", "aac", "opus", "flac", "wav", "ogg")
            .forEach { extension ->
                assertTrue("$extension must count as media", DownloadAccounting.isMediaExtension(extension))
            }
    }
}
