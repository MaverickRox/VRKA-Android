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
    fun updateAvailableShowsTheUpdateAction() {
        assertEquals(
            ComponentCardAction.UPDATE,
            componentCardAction(status(checkState = ComponentCheckState.UPDATE_AVAILABLE)),
        )
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
