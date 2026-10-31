package com.mvrk.vrka

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCardGeometryTest {

    @Test
    fun cardUsesTheSpecifiedFractionOfASmallScreen() {
        val width = shareCardWidth(360.dp)
        assertEquals(324f, width.value, 0.01f)
        assertTrue("Width must stay between 88% and 92%", width >= 360.dp * 0.88f && width <= 360.dp * 0.92f)
    }

    @Test
    fun cardIsCappedAtAComfortableReadingWidth() {
        assertEquals(420f, shareCardWidth(1080.dp).value, 0.01f)
        assertEquals(420f, shareCardWidth(480.dp).value, 0.01f)
    }

    @Test
    fun narrowScreensStillLeaveAVisibleMargin() {
        assertEquals(288f, shareCardWidth(320.dp).value, 0.01f)
        assertTrue(shareCardWidth(320.dp) < 320.dp)
    }

    @Test
    fun cardHeightLeavesTheContextVisible() {
        val maxHeight = shareCardMaxHeight(800.dp)
        assertEquals(680f, maxHeight.value, 0.01f)
        assertTrue(maxHeight <= 800.dp * 0.85f)
        assertTrue(maxHeight >= 800.dp * 0.82f)
    }

    @Test
    fun aLandscapeScreenStillReservesSpaceAbove() {
        val maxHeight = shareCardMaxHeight(360.dp)
        assertEquals(306f, maxHeight.value, 0.01f)
        assertTrue(maxHeight < 360.dp)
    }
}
