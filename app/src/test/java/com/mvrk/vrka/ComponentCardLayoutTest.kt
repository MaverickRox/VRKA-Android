package com.mvrk.vrka

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentCardLayoutTest {

    @Test
    fun a360dpPhoneCardStacksTheActionsBelowTheVersionText() {
        // 360 dp screen - 40 dp screen padding - 32 dp card padding.
        assertTrue(componentHeaderStacks(288.dp))
    }

    @Test
    fun theStackThresholdItselfAlreadyFitsInline() {
        assertFalse(componentHeaderStacks(COMPONENT_HEADER_STACK_WIDTH_DP.dp))
        assertTrue(componentHeaderStacks((COMPONENT_HEADER_STACK_WIDTH_DP - 1).dp))
    }

    @Test
    fun wideCardsKeepBadgeAndActionBesideTheText() {
        assertFalse(componentHeaderStacks(480.dp))
        assertFalse(componentHeaderStacks(COMPONENT_HEADER_STACK_WIDTH_DP.dp + 1.dp))
    }

    @Test
    fun measuredContentDecidesWhetherTheBadgeSharesTheRow() {
        // 393 dp screen leaves 321 dp of card content. A long version plus a badge and the
        // localized "Update Now" label no longer fit, so the badge moves to its own row.
        assertTrue(componentHeaderStacks(321.dp, 180.dp, 110.dp, 158.dp))
        // The same card without an action still fits the version and badge inline.
        assertFalse(componentHeaderStacks(321.dp, 180.dp, 110.dp, 0.dp))
        // A badge alone can force stacking when the version line is unusually long.
        assertTrue(componentHeaderStacks(321.dp, 260.dp, 110.dp, 0.dp))
        // Tablets keep the inline layout when everything fits.
        assertFalse(componentHeaderStacks(600.dp, 240.dp, 120.dp, 170.dp))
    }

    @Test
    fun updateAvailableShowsTheUpdateAction() {
        assertEquals(
            ComponentCardAction.UPDATE,
            componentCardAction(status(checkState = ComponentCheckState.UPDATE_AVAILABLE)),
        )
    }

    @Test
    fun versionTokenIsSplitFromItsLocalizedLabel() {
        assertEquals(
            "Update available:" to "v2026.10.26",
            splitVersionLabel("Update available: v2026.10.26"),
        )
        assertEquals(
            "Mise à jour disponible :" to "v2026.10.26",
            splitVersionLabel("Mise à jour disponible : v2026.10.26"),
        )
        assertEquals(
            "有可用更新：" to "v2026.10.26",
            splitVersionLabel("有可用更新：v2026.10.26"),
        )
        assertEquals(
            "تحديث متوفر:" to "v2026.10.26",
            splitVersionLabel("تحديث متوفر: v2026.10.26"),
        )
        assertEquals(null, splitVersionLabel("Not checked"))
    }

    @Test
    fun failedCheckOffersRetry() {
        assertEquals(
            ComponentCardAction.RETRY,
            componentCardAction(status(checkState = ComponentCheckState.CHECK_FAILED)),
        )
    }

    @Test
    fun failedUpdateOffersRetry() {
        assertEquals(
            ComponentCardAction.RETRY,
            componentCardAction(status(updateState = ComponentUpdateState.UPDATE_FAILED)),
        )
    }

    @Test
    fun settledStatesOfferNoAction() {
        assertEquals(
            ComponentCardAction.NONE,
            componentCardAction(status(checkState = ComponentCheckState.UP_TO_DATE)),
        )
        assertEquals(
            ComponentCardAction.NONE,
            componentCardAction(status(checkState = ComponentCheckState.CHECKING)),
        )
        assertEquals(
            ComponentCardAction.NONE,
            componentCardAction(status()),
        )
    }

    private fun status(
        checkState: ComponentCheckState = ComponentCheckState.CHECK_IDLE,
        updateState: ComponentUpdateState = ComponentUpdateState.UPDATE_IDLE,
    ) = ComponentStatus(
        id = ComponentUpdateManager.ID_YTDLP,
        name = "yt-dlp",
        installedVersion = "2026.08.19",
        checkState = checkState,
        updateState = updateState,
    )
}
