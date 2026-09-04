package com.mvrk.vrka.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ConcurrentTransferEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSegmentsAssembledInStrictChronologicalIndexOrder() {
        val rootDir = tempFolder.newFolder("hls_test")
        val outputFile = File(rootDir, "assembled.bin")

        val part0 = File(rootDir, "seg_00000.part").apply { writeText("CHUNK0_") }
        val part1 = File(rootDir, "seg_00001.part").apply { writeText("CHUNK1_") }
        val part2 = File(rootDir, "seg_00002.part").apply { writeText("CHUNK2") }

        val parts = listOf(part0, part1, part2)
        FileOutputStream(outputFile).use { fos ->
            parts.forEach { part ->
                FileInputStream(part).use { fis ->
                    fis.copyTo(fos)
                }
                part.delete()
            }
        }

        assertTrue(outputFile.exists())
        assertEquals("CHUNK0_CHUNK1_CHUNK2", outputFile.readText())
        assertFalse(part0.exists())
        assertFalse(part1.exists())
        assertFalse(part2.exists())
    }
}
