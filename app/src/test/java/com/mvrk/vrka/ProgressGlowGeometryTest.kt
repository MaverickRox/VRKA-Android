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
        assertEquals(0f, geometry.coneLengthPx, 0.001f)
        assertEquals(0f, geometry.bloomLengthPx, 0.001f)
        assertEquals(0f, geometry.edgeHalfWidthPx, 0.001f)
        assertEquals(0f, geometry.streakHalfHeightPx, 0.001f)
        assertFalse(geometry.headVisible)
    }

    @Test
    fun wakeIsAnchoredAtTheRealProgressEdgeAndTrailsBackwards() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH / 2f, geometry.fillWidthPx, 0.001f)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
        // Every layer ends at the edge; none of them is allowed to travel past it.
        assertEquals(TRACK_HEIGHT * GLOW_STREAK_LENGTH_RATIO, geometry.streakLengthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_CONE_LENGTH_RATIO, geometry.coneLengthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_BLOOM_LENGTH_RATIO, geometry.bloomLengthPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_EDGE_HALF_WIDTH_RATIO, geometry.edgeHalfWidthPx, 0.001f)
        assertTrue(geometry.streakLengthPx < geometry.coneLengthPx)
        assertTrue(geometry.coneLengthPx < geometry.bloomLengthPx)
        assertTrue(geometry.headVisible)
    }

    @Test
    fun layersDisperseProgressivelyFromTheStreakToTheHaze() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_HEIGHT * GLOW_STREAK_HALF_HEIGHT_RATIO, geometry.streakHalfHeightPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_CONE_HALF_HEIGHT_RATIO, geometry.coneHalfHeightPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_BLOOM_HALF_HEIGHT_RATIO, geometry.bloomHalfHeightPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_EDGE_HALF_HEIGHT_RATIO, geometry.edgeHalfHeightPx, 0.001f)
        assertTrue(geometry.streakHalfHeightPx < geometry.coneHalfHeightPx)
        assertTrue(geometry.coneHalfHeightPx < geometry.bloomHalfHeightPx)
        // The haze still fits inside the canvas; otherwise it would be cut by a flat line.
        assertTrue(geometry.bloomHalfHeightPx < TRACK_HEIGHT * GLOW_CANVAS_HEIGHT_RATIO / 2f)
        // Opacity, not just size, keeps the layers distinguishable.
        assertTrue(GLOW_BLOOM_ALPHA < GLOW_CONE_ALPHA)
        assertTrue(GLOW_CONE_ALPHA < GLOW_STREAK_ALPHA)
        assertTrue(GLOW_STREAK_ALPHA < GLOW_EDGE_ALPHA)
    }

    @Test
    fun completeProgressKeepsTheEdgeAtTheEndOfTheFill() {
        val geometry = glowProgressGeometry(1f, TRACK_WIDTH, TRACK_HEIGHT)

        assertEquals(TRACK_WIDTH, geometry.fillWidthPx, 0.001f)
        assertEquals(TRACK_WIDTH, geometry.headCenterPx, 0.001f)
        assertEquals(TRACK_HEIGHT * GLOW_BLOOM_LENGTH_RATIO, geometry.bloomLengthPx, 0.001f)
        assertTrue(geometry.headVisible)
    }

    @Test
    fun smallProgressClampsTheWakeInsideTheFill() {
        val geometry = glowProgressGeometry(0.01f, TRACK_WIDTH, TRACK_HEIGHT)

        val fill = TRACK_WIDTH * 0.01f
        assertEquals(fill, geometry.fillWidthPx, 0.001f)
        assertTrue(geometry.streakLengthPx <= fill)
        assertTrue(geometry.coneLengthPx <= fill)
        assertTrue(geometry.bloomLengthPx <= fill)
        assertTrue(geometry.edgeHalfWidthPx <= fill)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
    }

    @Test
    fun lightLayersScaleWithTheTrackHeight() {
        val thin = glowProgressGeometry(0.5f, WIDE_TRACK, 4f)
        val thick = glowProgressGeometry(0.5f, WIDE_TRACK, 8f)

        assertEquals(thin.streakHalfHeightPx * 2f, thick.streakHalfHeightPx, 0.001f)
        assertEquals(thin.coneHalfHeightPx * 2f, thick.coneHalfHeightPx, 0.001f)
        assertEquals(thin.bloomHalfHeightPx * 2f, thick.bloomHalfHeightPx, 0.001f)
        assertEquals(thin.edgeHalfHeightPx * 2f, thick.edgeHalfHeightPx, 0.001f)
        assertEquals(thin.edgeHalfWidthPx * 2f, thick.edgeHalfWidthPx, 0.001f)
        assertEquals(thin.streakLengthPx * 2f, thick.streakLengthPx, 0.001f)
        assertEquals(thin.coneLengthPx * 2f, thick.coneLengthPx, 0.001f)
        assertEquals(thin.bloomLengthPx * 2f, thick.bloomLengthPx, 0.001f)
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
        assertEquals(0f, noTrack.streakHalfHeightPx, 0.001f)
        assertEquals(0f, noTrack.coneHalfHeightPx, 0.001f)
        assertEquals(0f, noTrack.bloomHalfHeightPx, 0.001f)
        assertEquals(0f, noTrack.edgeHalfHeightPx, 0.001f)
    }

    private companion object {
        const val TRACK_WIDTH = 400f
        const val WIDE_TRACK = 800f
        const val TRACK_HEIGHT = 8f
    }
}
