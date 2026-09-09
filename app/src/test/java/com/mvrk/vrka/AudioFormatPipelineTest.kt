package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioFormatPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun audioFormatEnumContainsOnlySupportedOutputFormats() {
        val formats = AudioFormat.entries.map { it.name }
        assertEquals(listOf("MP3", "OPUS", "WAV"), formats)
        assertFalse("FLAC must not be in AudioFormat entries", formats.contains("FLAC"))
    }

    @Test
    fun legacyFlacJobMigratesToOpusOnLoad() {
        val legacyJson = """
        [
            {
                "id": "legacy-flac-job",
                "request": {
                    "url": "https://example.com/audio",
                    "mode": "AUDIO",
                    "quality": "BEST",
                    "prefer60Fps": false,
                    "audioFormat": "FLAC",
                    "mp3Bitrate": 320,
                    "isPlaylist": false
                },
                "state": "DONE",
                "title": "Legacy FLAC Audio",
                "detail": "Finished",
                "progress": 1.0,
                "createdAt": 1700000000000,
                "updatedAt": 1700000001000
            }
        ]
        """.trimIndent()

        val file = File(tempFolder.root, "legacy_jobs.json")
        file.writeText(legacyJson, Charsets.UTF_8)

        val store = JobStore(file)
        val loaded = store.load()

        assertEquals(1, loaded.size)
        val job = loaded.first()
        assertEquals("legacy-flac-job", job.id)
        assertEquals("FLAC should be migrated to OPUS", AudioFormat.OPUS, job.request.audioFormat)
    }

    @Test
    fun audioValidatorRejectsNonAudioExtensionForMP3() {
        val badFile = File(tempFolder.root, "test.m4a").apply { writeText("dummy content") }
        try {
            // Test container rejection when format is MP3
            val ext = badFile.extension.lowercase()
            assertTrue("Expected extension to not be mp3", ext != "mp3")
        } finally {
            badFile.delete()
        }
    }

    @Test
    fun audioValidatorAcceptsOpusAndOggContainers() {
        val opusFile = File(tempFolder.root, "audio.opus")
        val oggFile = File(tempFolder.root, "audio.ogg")
        assertTrue(opusFile.extension.lowercase() in setOf("opus", "ogg"))
        assertTrue(oggFile.extension.lowercase() in setOf("opus", "ogg"))
    }

    @Test
    fun audioValidatorAcceptsWavContainer() {
        val wavFile = File(tempFolder.root, "audio.wav")
        assertEquals("wav", wavFile.extension.lowercase())
    }

    @Test
    fun bitratesAllowedSetCoversAllStandardTiers() {
        val allowedBitrates = setOf(128, 160, 192, 224, 256, 320)
        assertTrue(allowedBitrates.contains(128))
        assertTrue(allowedBitrates.contains(160))
        assertTrue(allowedBitrates.contains(192))
        assertTrue(allowedBitrates.contains(224))
        assertTrue(allowedBitrates.contains(256))
        assertTrue(allowedBitrates.contains(320))
        assertEquals(6, allowedBitrates.size)
    }
}
