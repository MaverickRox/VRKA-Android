package com.mvrk.vrka

import org.junit.Assert.assertEquals
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
        assertEquals(56f, NAV_INDICATOR_HEIGHT.value, 0.001f)
        assertEquals(64f, NAV_BAR_HEIGHT.value, 0.001f)
        assertEquals(4f, NAV_TAB_INSET_VERTICAL.value, 0.001f)
        assertEquals(4f, NAV_TAB_INSET_HORIZONTAL.value, 0.001f)
        assertEquals(
            NAV_BAR_HEIGHT.value - (NAV_TAB_INSET_VERTICAL.value * 2),
            NAV_INDICATOR_HEIGHT.value,
            0.001f,
        )
    }

    @Test
    fun test16b_barAndPillAreTrueCapsules() {
        assertEquals(NAV_BAR_HEIGHT.value / 2f, NAV_BAR_CORNER_RADIUS.value, 0.001f)
        assertEquals(NAV_INDICATOR_HEIGHT.value / 2f, NAV_INDICATOR_CORNER_RADIUS.value, 0.001f)
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
}
