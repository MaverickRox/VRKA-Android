package com.mvrk.vrka

import org.junit.Assert.assertEquals
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
}
