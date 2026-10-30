package com.mvrk.vrka

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Narrow-screen regression for the Components card. German labels are the longest shipped
 * strings; at 288 dp of card content ("Update verfügbar" next to "Jetzt aktualisieren") the
 * inline row clipped, so the card must move its badge and action below the version text.
 */
@RunWith(AndroidJUnit4::class)
class ComponentCardNarrowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun germanContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.GERMANY)
        }
        return base.createConfigurationContext(config)
    }

    private fun component(
        checkState: ComponentCheckState = ComponentCheckState.UPDATE_AVAILABLE,
    ) = ComponentStatus(
        id = ComponentUpdateManager.ID_YTDLP,
        name = "yt-dlp",
        installedVersion = "2026.08.19",
        latestVersion = "2026.09.01",
        checkState = checkState,
    )

    private fun show(availableWidthDp: Int, comp: ComponentStatus = component()) {
        val context = germanContext()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalResources provides context.resources,
            ) {
                VrkaTheme {
                    Box(modifier = Modifier.width(availableWidthDp.dp)) {
                        ComponentStatusRow(comp = comp, onCheck = {}, onUpdate = {})
                    }
                }
            }
        }
    }

    @Test
    fun germanUpdateActionWrapsBelowTheVersionTextOnANarrowCard() {
        show(availableWidthDp = 288)

        composeRule.onNodeWithText("Installiert: v2026.08.19").assertIsDisplayed()
        composeRule.onNodeWithText("v2026.09.01").assertIsDisplayed()
        composeRule.onNodeWithText("Jetzt aktualisieren").assertIsDisplayed()

        val installed = composeRule.onNodeWithText("Installiert: v2026.08.19")
            .getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithText("Jetzt aktualisieren")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "The action must render below the version text instead of being squeezed beside it " +
                "(installed bottom ${installed.bottom}, action top ${action.top})",
            action.top >= installed.bottom,
        )
        assertTrue(
            "The action must stay inside the 288 dp card (right ${action.right})",
            action.right <= 288.dp,
        )
    }
}
