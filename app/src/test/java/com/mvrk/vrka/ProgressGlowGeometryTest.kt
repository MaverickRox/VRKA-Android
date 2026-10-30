package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGlowGeometryTest {
    @Test
    fun zeroProgressDrawsOnlyTheTrack() {
        val geometry = glowProgressGeometry(0f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(0f, geometry.fillWidthPx, 0.001f)
        assertEquals(0f, geometry.headCenterPx, 0.001f)
        assertEquals(0f, geometry.headWidthPx, 0.001f)
        assertEquals(0f, geometry.trailLengthPx, 0.001f)
        assertFalse(geometry.headVisible)
    }

    @Test
    fun midProgressFillsHalfAndPutsTheHeadAtTheLeadingEdge() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH / 2f, geometry.fillWidthPx, 0.001f)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_HEAD_WIDTH_RATIO, geometry.headWidthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_TRAIL_LENGTH_RATIO, geometry.trailLengthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_HALF_WIDTH_RATIO, geometry.glowHalfWidthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_BLOOM_RADIUS_RATIO, geometry.bloomRadiusPx, 0.001f)
        assertTrue(geometry.headVisible)
    }

    @Test
    fun completeProgressKeepsTheHeadAtTheEndOfTheFill() {
        val geometry = glowProgressGeometry(1f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH, geometry.fillWidthPx, 0.001f)
        assertEquals(TRACK_WIDTH, geometry.headCenterPx, 0.001f)
        assertTrue(geometry.headVisible)
    }

    @Test
    fun smallProgressClampsTheHeadAndTrailInsideTheFill() {
        val geometry = glowProgressGeometry(0.01f, TRACK_WIDTH, TRACK_HEIGHT)

        val fill = TRACK_WIDTH * 0.01f
        assertEquals(fill, geometry.fillWidthPx, 0.001f)
        assertTrue(geometry.headWidthPx <= fill)
        assertTrue(geometry.trailLengthPx <= fill)
        // The bright head still ends exactly at the real progress position.
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
    }

    @Test
    fun lightAndBloomScaleWithTheTrackHeight() {
        val thin = glowProgressGeometry(0.5f, TRACK_WIDTH, 4f)
        val thick = glowProgressGeometry(0.5f, TRACK_WIDTH, 8f)

        assertEquals(thin.glowHalfWidthPx * 2f, thick.glowHalfWidthPx, 0.001f)
        assertEquals(thin.bloomRadiusPx * 2f, thick.bloomRadiusPx, 0.001f)
        assertEquals(thin.headWidthPx * 2f, thick.headWidthPx, 0.001f)
        assertEquals(thin.trailLengthPx * 2f, thick.trailLengthPx, 0.001f)
    }

    @Test
    fun invalidValuesClampSafely() {
        assertEquals(0f, glowProgressGeometry(Float.NaN, TRACK_WIDTH, TRACK_HEIGHT).fillWidthPx, 0.001f)
        assertEquals(0f, glowProgressGeometry(0.5f, -10f, TRACK_HEIGHT).fillWidthPx, 0.001f)
        assertEquals(TRACK_WIDTH, glowProgressGeometry(2f, TRACK_WIDTH, TRACK_HEIGHT).fillWidthPx, 0.001f)
        assertEquals(0f, glowProgressGeometry(-1f, TRACK_WIDTH, TRACK_HEIGHT).fillWidthPx, 0.001f)
        assertEquals(
            0f,
            glowProgressGeometry(0.5f, Float.POSITIVE_INFINITY, TRACK_HEIGHT).fillWidthPx,
            0.001f,
        )
        val noTrack = glowProgressGeometry(0.5f, TRACK_WIDTH, Float.NaN)
        assertEquals(TRACK_WIDTH / 2f, noTrack.fillWidthPx, 0.001f)
        assertEquals(0f, noTrack.headWidthPx, 0.001f)
        assertEquals(0f, noTrack.glowHalfWidthPx, 0.001f)
    }

    private companion object {
        const val TRACK_WIDTH = 200f
        const val TRACK_HEIGHT = 8f
    }
}
