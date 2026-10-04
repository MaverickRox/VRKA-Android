package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NavIndicatorGeometryTest {
    private val measuredItems = listOf(
        NavItemMeasuredBounds(slotLeftPx = 0f, slotWidthPx = 100f, contentLeftInSlotPx = 10f, contentWidthPx = 80f),
        NavItemMeasuredBounds(slotLeftPx = 100f, slotWidthPx = 100f, contentLeftInSlotPx = 20f, contentWidthPx = 60f),
        NavItemMeasuredBounds(slotLeftPx = 200f, slotWidthPx = 100f, contentLeftInSlotPx = 8f, contentWidthPx = 84f),
        NavItemMeasuredBounds(slotLeftPx = 300f, slotWidthPx = 100f, contentLeftInSlotPx = 15f, contentWidthPx = 70f),
    )

    @Test
    fun pillCentersOnMeasuredItemContentAcrossAllDestinations() {
        measuredItems.forEachIndexed { index, item ->
            val pill = calculateNavIndicatorBounds(index.toFloat(), measuredItems, contentInsetPx = 4f, slotInsetPx = 4f)
            assertNotNull(pill)
            assertEquals(
                item.slotLeftPx + item.contentLeftInSlotPx + item.contentWidthPx / 2f,
                pill!!.leftPx + pill.widthPx / 2f,
                0.001f,
            )
        }
    }

    @Test
    fun pillGlidesBetweenMeasuredCentersAndAdaptsItsWidth() {
        val first = calculateNavIndicatorBounds(0f, measuredItems, contentInsetPx = 4f, slotInsetPx = 4f)!!
        val middle = calculateNavIndicatorBounds(0.5f, measuredItems, contentInsetPx = 4f, slotInsetPx = 4f)!!
        val second = calculateNavIndicatorBounds(1f, measuredItems, contentInsetPx = 4f, slotInsetPx = 4f)!!

        assertEquals(50f, first.leftPx + first.widthPx / 2f, 0.001f)
        assertEquals(100f, middle.leftPx + middle.widthPx / 2f, 0.001f)
        assertEquals(150f, second.leftPx + second.widthPx / 2f, 0.001f)
        assertEquals((first.widthPx + second.widthPx) / 2f, middle.widthPx, 0.001f)
    }

    @Test
    fun pillRespectsSlotBoundsAtLargeFontWidths() {
        val wideItem = listOf(
            NavItemMeasuredBounds(slotLeftPx = 20f, slotWidthPx = 80f, contentLeftInSlotPx = 0f, contentWidthPx = 80f),
        )

        val pill = calculateNavIndicatorBounds(0f, wideItem, contentInsetPx = 12f, slotInsetPx = 4f)!!
        assertEquals(72f, pill.widthPx, 0.001f)
        assertEquals(24f, pill.leftPx, 0.001f)
        assertEquals(56f, NAV_INDICATOR_HEIGHT.value, 0.001f)
        assertEquals(64f, NAV_BAR_HEIGHT.value, 0.001f)
    }
}
