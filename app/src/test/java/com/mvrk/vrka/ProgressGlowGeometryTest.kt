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
        assertEquals(0f, geometry.streakLengthPx, 0.001f)
        assertEquals(0f, geometry.nearBloomHalfHeightPx, 0.001f)
        assertEquals(0f, geometry.farBloomHalfHeightPx, 0.001f)
        assertFalse(geometry.headVisible)
    }

    @Test
    fun wakeTrailsBackwardsOverALongStreak() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH / 2f, geometry.fillWidthPx, 0.001f)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_STREAK_LENGTH_RATIO, geometry.streakLengthPx, 0.001f)
        // A short halo around the dot is the failure mode this test guards against.
        assertTrue(geometry.streakLengthPx >= TRACK_HEIGHT * 12f)
        assertTrue(geometry.headVisible)
    }

    @Test
    fun opticalTeardropGrowsWiderAndFainterThanItsCore() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(
            TRACK_HEIGHT * GLOW_BLOOM_NEAR_HALF_HEIGHT_RATIO,
            geometry.nearBloomHalfHeightPx,
            0.001f,
        )
        assertEquals(
            TRACK_HEIGHT * GLOW_BLOOM_FAR_HALF_HEIGHT_RATIO,
            geometry.farBloomHalfHeightPx,
            0.001f,
        )
        assertTrue(geometry.nearBloomHalfHeightPx < geometry.farBloomHalfHeightPx)
        // The haze must fade inside the component; otherwise the canvas would cut a flat line.
        assertTrue(geometry.farBloomHalfHeightPx < TRACK_HEIGHT * GLOW_CANVAS_HEIGHT_RATIO / 2f)
        assertTrue(GLOW_BLOOM_NEAR_HALF_HEIGHT_RATIO >= 1.5f)
        assertTrue(GLOW_BLOOM_FAR_HALF_HEIGHT_RATIO >= 2f)
    }

    @Test
    fun completeProgressKeepsTheHeadAtTheEndOfTheFill() {
        val geometry = glowProgressGeometry(1f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH, geometry.fillWidthPx, 0.001f)
        assertEquals(TRACK_WIDTH, geometry.headCenterPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_STREAK_LENGTH_RATIO, geometry.streakLengthPx, 0.001f)
        assertEquals(
            TRACK_HEIGHT * GLOW_BLOOM_FAR_HALF_HEIGHT_RATIO,
            geometry.farBloomHalfHeightPx,
            0.001f,
        )
        assertTrue(geometry.headVisible)
    }

    @Test
    fun smallProgressClampsTheStreakInsideTheFill() {
        val geometry = glowProgressGeometry(0.01f, TRACK_WIDTH, TRACK_HEIGHT)

        val fill = TRACK_WIDTH * 0.01f
        assertEquals(fill, geometry.fillWidthPx, 0.001f)
        assertTrue(geometry.streakLengthPx <= fill)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
        // The teardrop path is clamped to the bar in the renderer; its vertical scale is
        // independent of progress, so a short bar still has a readable head bloom.
        assertTrue(geometry.nearBloomHalfHeightPx > 0f)
    }

    @Test
    fun lightLayersScaleWithTheTrackHeight() {
        val thin = glowProgressGeometry(0.5f, WIDE_TRACK, 4f)
        val thick = glowProgressGeometry(0.5f, WIDE_TRACK, 8f)

        assertEquals(thin.nearBloomHalfHeightPx * 2f, thick.nearBloomHalfHeightPx, 0.001f)
        assertEquals(thin.farBloomHalfHeightPx * 2f, thick.farBloomHalfHeightPx, 0.001f)
        assertEquals(thin.streakLengthPx * 2f, thick.streakLengthPx, 0.001f)
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
        assertEquals(0f, noTrack.nearBloomHalfHeightPx, 0.001f)
        assertEquals(0f, noTrack.farBloomHalfHeightPx, 0.001f)
    }

    private companion object {
        const val TRACK_WIDTH = 400f
        const val WIDE_TRACK = 800f
        const val TRACK_HEIGHT = 8f
    }
}
