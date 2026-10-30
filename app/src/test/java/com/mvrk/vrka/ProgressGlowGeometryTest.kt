package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGlowGeometryTest {
    @Test
    fun zeroProgressDrawsNoFillOrSheen() {
        val geometry = glowProgressGeometry(0f, TRACK_WIDTH, reduceMotion = false)

        assertEquals(0f, geometry.fillWidthPx, 0.001f)
        assertEquals(0f, geometry.headCenterPx, 0.001f)
        assertEquals(0f, geometry.glowAlpha, 0.001f)
        assertFalse(geometry.sheenVisible)
    }

    @Test
    fun midProgressFillsHalfTheTrackAndShowsTheSheen() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, reduceMotion = false)

        assertEquals(TRACK_WIDTH / 2f, geometry.fillWidthPx, 0.001f)
        assertEquals(geometry.fillWidthPx, geometry.headCenterPx, 0.001f)
        assertTrue(geometry.glowAlpha > 0f)
        assertTrue(geometry.sheenVisible)
    }

    @Test
    fun completeProgressStopsTheSheen() {
        val geometry = glowProgressGeometry(1f, TRACK_WIDTH, reduceMotion = false)

        assertEquals(TRACK_WIDTH, geometry.fillWidthPx, 0.001f)
        assertFalse(geometry.sheenVisible)
    }

    @Test
    fun reduceMotionKeepsTheFillButRemovesTheSheen() {
        val geometry = glowProgressGeometry(0.5f, TRACK_WIDTH, reduceMotion = true)

        assertEquals(TRACK_WIDTH / 2f, geometry.fillWidthPx, 0.001f)
        assertTrue(geometry.glowAlpha > 0f)
        assertFalse(geometry.sheenVisible)
    }

    @Test
    fun invalidValuesClampSafely() {
        assertEquals(0f, glowProgressGeometry(Float.NaN, TRACK_WIDTH, false).fillWidthPx, 0.001f)
        assertEquals(0f, glowProgressGeometry(0.5f, -10f, false).fillWidthPx, 0.001f)
        assertEquals(TRACK_WIDTH, glowProgressGeometry(2f, TRACK_WIDTH, false).fillWidthPx, 0.001f)
        assertEquals(0f, glowProgressGeometry(-1f, TRACK_WIDTH, false).fillWidthPx, 0.001f)
        assertEquals(0f, glowProgressGeometry(0.5f, Float.POSITIVE_INFINITY, false).fillWidthPx, 0.001f)
    }

    private companion object {
        const val TRACK_WIDTH = 200f
    }
}
