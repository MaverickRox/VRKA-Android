package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavIndicatorGeometryTest {
    // 4 equal navigation slots (e.g. 100px width each across 400px navbar)
    // with different content widths (Queue is narrower, Download and Settings are wider)
    private val equalSlots = listOf(
        NavItemMeasuredBounds(slotLeftPx = 0f, slotWidthPx = 100f, contentLeftInSlotPx = 15f, contentWidthPx = 70f), // Download
        NavItemMeasuredBounds(slotLeftPx = 100f, slotWidthPx = 100f, contentLeftInSlotPx = 28f, contentWidthPx = 44f), // Queue (short text)
        NavItemMeasuredBounds(slotLeftPx = 200f, slotWidthPx = 100f, contentLeftInSlotPx = 22f, contentWidthPx = 56f), // History
        NavItemMeasuredBounds(slotLeftPx = 300f, slotWidthPx = 100f, contentLeftInSlotPx = 16f, contentWidthPx = 68f), // Settings
    )

    @Test
    fun test15_fourEqualSlotGeometries() {
        assertEquals(4, equalSlots.size)
        val firstSlotWidth = equalSlots[0].slotWidthPx
        equalSlots.forEach { slot ->
            assertEquals(firstSlotWidth, slot.slotWidthPx, 0.001f)
        }
    }

    @Test
    fun test16_identicalIndicatorHeight() {
        assertEquals(54f, NAV_INDICATOR_HEIGHT.value, 0.001f)
        assertEquals(64f, NAV_BAR_HEIGHT.value, 0.001f)
        assertEquals(5f, NAV_TAB_INSET_VERTICAL.value, 0.001f)
        assertEquals(6f, NAV_TAB_INSET_HORIZONTAL.value, 0.001f)
        assertEquals(
            NAV_BAR_HEIGHT.value - (NAV_TAB_INSET_VERTICAL.value * 2),
            NAV_INDICATOR_HEIGHT.value,
            0.001f,
        )
    }

    @Test
    fun test16b_barAndPillMatchTheReleaseGeometry() {
        // fd12ad5 reference: 64dp bar, 54dp pill, 32dp radii.
        assertEquals(64f, NAV_BAR_HEIGHT.value, 0.001f)
        assertEquals(54f, NAV_INDICATOR_HEIGHT.value, 0.001f)
        assertEquals(32f, NAV_BAR_CORNER_RADIUS.value, 0.001f)
        assertEquals(32f, NAV_INDICATOR_CORNER_RADIUS.value, 0.001f)
        assertEquals(NAV_BAR_HEIGHT.value / 2f, NAV_BAR_CORNER_RADIUS.value, 0.001f)
    }

    @Test
    fun test16e_pressedLensStaysAConcentricCapsule() {
        // The lens is clipped to the 64dp bar at full press. With a 32dp radius the
        // result is a capsule, not a rounded square, and its center never drifts.
        val indicator = NavIndicatorBounds(leftPx = 106f, widthPx = 88f)
        val lens = calculateNavPressLensBounds(indicator, 1f, 64f, 54f)
        assertEquals(64f, lens.heightPx, 0.001f)
        assertEquals(lens.heightPx / 2f, NAV_INDICATOR_CORNER_RADIUS.value, 0.001f)
        assertEquals(
            indicator.leftPx + indicator.widthPx / 2f,
            lens.leftPx + lens.widthPx / 2f,
            0.001f,
        )
    }

    @Test
    fun test16f_labelHasClearanceInsideThePill() {
        assertTrue(NAV_ITEM_HORIZONTAL_CONTENT_PADDING.value > NAV_TAB_INSET_HORIZONTAL.value)
        assertTrue(NAV_LABEL_FONT_SIZE.value in 9.5f..10.2f)
        assertTrue(NAV_LABEL_LINE_HEIGHT.value <= NAV_INDICATOR_HEIGHT.value / 3f)
    }

    @Test
    fun test16c_selectionRoundsToTheNearestSlotAndClamps() {
        assertEquals(0, navSelectedIndex(-0.4f, 3))
        assertEquals(0, navSelectedIndex(0.49f, 3))
        assertEquals(1, navSelectedIndex(0.5f, 3))
        assertEquals(3, navSelectedIndex(3.6f, 3))
        assertEquals(0, navSelectedIndex(2f, 0))
    }

    @Test
    fun test16d_itemFocusFadesWithinOneSlot() {
        assertEquals(1f, navItemFocus(1f, 1), 0.001f)
        assertEquals(0.5f, navItemFocus(1.5f, 1), 0.001f)
        assertEquals(0f, navItemFocus(2f, 1), 0.001f)
        assertEquals(0f, navItemFocus(-1f, 0), 0.001f)
    }

    @Test
    fun test17_identicalIndicatorGeometry() {
        val slotInset = 6f
        val expectedWidth = 100f - (2f * slotInset) // 88f
        for (i in equalSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)
            assertNotNull(pill)
            assertEquals("Pill for slot $i must have exact expected width", expectedWidth, pill!!.widthPx, 0.001f)
        }
    }

    @Test
    fun test18_queuePillNotSmallerThanDownloadOrSettings() {
        val slotInset = 6f
        val downloadPill = calculateNavIndicatorBounds(0f, equalSlots, slotInsetPx = slotInset)!!
        val queuePill = calculateNavIndicatorBounds(1f, equalSlots, slotInsetPx = slotInset)!!
        val historyPill = calculateNavIndicatorBounds(2f, equalSlots, slotInsetPx = slotInset)!!
        val settingsPill = calculateNavIndicatorBounds(3f, equalSlots, slotInsetPx = slotInset)!!

        assertEquals(downloadPill.widthPx, queuePill.widthPx, 0.001f)
        assertEquals(settingsPill.widthPx, queuePill.widthPx, 0.001f)
        assertEquals(historyPill.widthPx, queuePill.widthPx, 0.001f)
    }

    @Test
    fun test19_textClearanceBreathingRoomWithinPill() {
        val slotInset = 6f
        equalSlots.forEachIndexed { index, slot ->
            val pill = calculateNavIndicatorBounds(index.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            val contentAbsoluteLeft = slot.slotLeftPx + slot.contentLeftInSlotPx
            val contentAbsoluteRight = contentAbsoluteLeft + slot.contentWidthPx
            val pillAbsoluteLeft = pill.leftPx
            val pillAbsoluteRight = pill.leftPx + pill.widthPx

            // Content must be entirely inside the pill with breathing room
            assertTrue(
                "Slot $index content left ($contentAbsoluteLeft) must be within pill left ($pillAbsoluteLeft)",
                contentAbsoluteLeft >= pillAbsoluteLeft,
            )
            assertTrue(
                "Slot $index content right ($contentAbsoluteRight) must be within pill right ($pillAbsoluteRight)",
                contentAbsoluteRight <= pillAbsoluteRight,
            )
        }
    }

    @Test
    fun test20_pagerSynchronizationInterpolatesSmoothly() {
        val slotInset = 6f
        val pillAt0 = calculateNavIndicatorBounds(0.0f, equalSlots, slotInsetPx = slotInset)!!
        val pillAtHalf = calculateNavIndicatorBounds(0.5f, equalSlots, slotInsetPx = slotInset)!!
        val pillAt1 = calculateNavIndicatorBounds(1.0f, equalSlots, slotInsetPx = slotInset)!!

        assertEquals(6f, pillAt0.leftPx, 0.001f)
        assertEquals(56f, pillAtHalf.leftPx, 0.001f)
        assertEquals(106f, pillAt1.leftPx, 0.001f)
        assertEquals(pillAt0.widthPx, pillAtHalf.widthPx, 0.001f)
        assertEquals(pillAt0.widthPx, pillAt1.widthPx, 0.001f)
    }

    @Test
    fun test21_tapSynchronizationPositionsDirectlyAtSlot() {
        val slotInset = 6f
        for (i in equalSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            val expectedLeft = (i * 100f) + slotInset
            assertEquals(expectedLeft, pill.leftPx, 0.001f)
        }
    }

    @Test
    fun test22_rtlIndicatorPositionsCorrectlyAtSlots() {
        // In RTL the row lays destination 0 out on the right; measured slot offsets
        // mirror, and the indicator must still derive its bounds from them.
        val rtlSlots = equalSlots.mapIndexed { index, slot ->
            slot.copy(slotLeftPx = 300f - index * 100f)
        }
        val slotInset = 6f
        val containerWidth = 400f
        for (i in rtlSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), rtlSlots, slotInsetPx = slotInset)!!
            assertTrue("Pill leftPx must be non-negative: ${pill.leftPx}", pill.leftPx >= 0f)
            assertTrue(
                "Pill right boundary must be within container: ${pill.leftPx + pill.widthPx}",
                pill.leftPx + pill.widthPx <= containerWidth,
            )
            assertEquals((300f - i * 100f) + slotInset, pill.leftPx, 0.001f)
        }
    }

    @Test
    fun test23_pressLensRestsExactlyOnTheIndicator() {
        val indicator = NavIndicatorBounds(leftPx = 106f, widthPx = 88f)
        val lens = calculateNavPressLensBounds(
            indicator = indicator,
            pressProgress = 0f,
            containerHeightPx = 64f,
            restingHeightPx = 54f,
        )
        assertEquals(106f, lens.leftPx, 0.001f)
        assertEquals(5f, lens.topPx, 0.001f)
        assertEquals(88f, lens.widthPx, 0.001f)
        assertEquals(54f, lens.heightPx, 0.001f)
    }

    @Test
    fun test24_pressLensGrowsWithMornyeHeldFactors() {
        val indicator = NavIndicatorBounds(leftPx = 106f, widthPx = 88f)
        val lens = calculateNavPressLensBounds(
            indicator = indicator,
            pressProgress = 1f,
            containerHeightPx = 64f,
            restingHeightPx = 54f,
        )
        // MornyeSelectionPill: +28% width, +24% height, then the bar clips the height.
        assertEquals(88f * 1.28f, lens.widthPx, 0.001f)
        assertEquals(64f, lens.heightPx, 0.001f)
        assertEquals(0f, lens.topPx, 0.001f)
        assertEquals(106f - (88f * 0.28f) / 2f, lens.leftPx, 0.001f)
        assertEquals(
            indicator.leftPx + indicator.widthPx / 2f,
            lens.leftPx + lens.widthPx / 2f,
            0.001f,
        )
    }

    @Test
    fun test25_pressLensGrowthIsProportionalAndBounded() {
        val indicator = NavIndicatorBounds(leftPx = 0f, widthPx = 88f)
        val half = calculateNavPressLensBounds(indicator, 0.5f, 64f, 54f)
        assertEquals(88f * 1.14f, half.widthPx, 0.001f)
        assertEquals(54f * 1.12f, half.heightPx, 0.001f)
        assertEquals((64f - 54f * 1.12f) / 2f, half.topPx, 0.001f)

        val beyond = calculateNavPressLensBounds(indicator, 2.5f, 64f, 54f)
        val full = calculateNavPressLensBounds(indicator, 1f, 64f, 54f)
        assertEquals(full.widthPx, beyond.widthPx, 0.001f)
        assertEquals(full.heightPx, beyond.heightPx, 0.001f)

        val below = calculateNavPressLensBounds(indicator, -1f, 64f, 54f)
        assertEquals(88f, below.widthPx, 0.001f)
        assertEquals(54f, below.heightPx, 0.001f)
    }

    @Test
    fun test26_pressLensNeverLeavesTheCapsuleVertically() {
        val slotInset = 6f
        for (i in equalSlots.indices) {
            val indicator = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val lens = calculateNavPressLensBounds(indicator, progress, 64f, 54f)
                assertTrue(lens.topPx >= 0f)
                assertTrue(lens.topPx + lens.heightPx <= 64f)
            }
        }
    }

    @Test
    fun test27_rtlPressLensCentersOnTheMirroredSlot() {
        // Destination 0 sits rightmost; press growth must stay centered on it and
        // rely on the bar clip rather than drift toward a neighbour.
        val rtlSlots = equalSlots.mapIndexed { index, slot ->
            slot.copy(slotLeftPx = 300f - index * 100f)
        }
        val indicator = calculateNavIndicatorBounds(1f, rtlSlots, slotInsetPx = 6f)!!
        val lens = calculateNavPressLensBounds(indicator, 1f, 64f, 54f)
        assertEquals(
            indicator.leftPx + indicator.widthPx / 2f,
            lens.leftPx + lens.widthPx / 2f,
            0.001f,
        )
        // The RTL index 1 slot is physically at x=200..300, far enough from the
        // edges that the grown capsule stays inside the bar.
        assertTrue(lens.leftPx >= 0f)
        assertTrue(lens.leftPx + lens.widthPx <= 400f)
    }

    @Test
    fun test28_pressLensRequiresEffectsAndRespectsAccessibility() {
        assertTrue(
            shouldEnableNavPressLens(
                hasBackdrop = true,
                renderEffectSupported = true,
                shaderSupported = true,
                reduceMotion = false,
                highContrast = false,
            ),
        )
        assertFalse(
            shouldEnableNavPressLens(true, true, true, reduceMotion = true, highContrast = false),
        )
        assertFalse(
            shouldEnableNavPressLens(true, true, true, reduceMotion = false, highContrast = true),
        )
        assertFalse(
            shouldEnableNavPressLens(false, true, true, reduceMotion = false, highContrast = false),
        )
        assertFalse(
            shouldEnableNavPressLens(true, false, true, reduceMotion = false, highContrast = false),
        )
        assertFalse(
            shouldEnableNavPressLens(true, true, false, reduceMotion = false, highContrast = false),
        )
    }

    @Test
    fun test29_pressLensStaysCenteredAndWithinItsSlotAtRest() {
        val slotInset = 6f
        for (i in equalSlots.indices) {
            val indicator = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            val lens = calculateNavPressLensBounds(indicator, 0f, 64f, 54f)
            val slot = equalSlots[i]
            assertTrue(lens.leftPx >= slot.slotLeftPx)
            assertTrue(lens.leftPx + lens.widthPx <= slot.slotLeftPx + slot.slotWidthPx)
        }
    }
}
