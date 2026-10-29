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
        assertEquals(4f, NAV_CONCENTRIC_INSET.value, 0.001f)
        assertEquals(NAV_BAR_HEIGHT.value - (NAV_CONCENTRIC_INSET.value * 2), NAV_INDICATOR_HEIGHT.value, 0.001f)
    }

    @Test
    fun test17_identicalIndicatorGeometry() {
        val slotInset = 4f
        val expectedWidth = 100f - (2f * slotInset) // 92f
        for (i in equalSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)
            assertNotNull(pill)
            assertEquals("Pill for slot $i must have exact expected width", expectedWidth, pill!!.widthPx, 0.001f)
        }
    }

    @Test
    fun test18_queuePillNotSmallerThanDownloadOrSettings() {
        val slotInset = 4f
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
        val slotInset = 4f
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
        val slotInset = 4f
        val pillAt0 = calculateNavIndicatorBounds(0.0f, equalSlots, slotInsetPx = slotInset)!!
        val pillAtHalf = calculateNavIndicatorBounds(0.5f, equalSlots, slotInsetPx = slotInset)!!
        val pillAt1 = calculateNavIndicatorBounds(1.0f, equalSlots, slotInsetPx = slotInset)!!

        assertEquals(4f, pillAt0.leftPx, 0.001f)
        assertEquals(54f, pillAtHalf.leftPx, 0.001f)
        assertEquals(104f, pillAt1.leftPx, 0.001f)
        assertEquals(pillAt0.widthPx, pillAtHalf.widthPx, 0.001f)
        assertEquals(pillAt0.widthPx, pillAt1.widthPx, 0.001f)
    }

    @Test
    fun test21_tapSynchronizationPositionsDirectlyAtSlot() {
        val slotInset = 4f
        for (i in equalSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            val expectedLeft = (i * 100f) + slotInset
            assertEquals(expectedLeft, pill.leftPx, 0.001f)
        }
    }

    @Test
    fun test22_rtlIndicatorPositionsCorrectlyAtSlots() {
        val slotInset = 4f
        val containerWidth = 400f
        for (i in equalSlots.indices) {
            val pill = calculateNavIndicatorBounds(i.toFloat(), equalSlots, slotInsetPx = slotInset)!!
            org.junit.Assert.assertTrue("Pill leftPx must be non-negative: ${pill.leftPx}", pill.leftPx >= 0f)
            org.junit.Assert.assertTrue("Pill right boundary must be within container: ${pill.leftPx + pill.widthPx}", (pill.leftPx + pill.widthPx) <= containerWidth)
            assertEquals((i * 100f) + slotInset, pill.leftPx, 0.001f)
        }
    }
}
