package com.mvrk.vrka

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP4 regression: the About card must show the enlarged (72-84 dp) VRKA artwork, and the
 * artwork must stay on screen after the header text wraps on a narrow layout.
 */
@RunWith(AndroidJUnit4::class)
class AboutLogoTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    "com.mvrk.vrka.debug",
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    @Test
    fun aboutLogoIsVisibleAtTheDocumentedSize() {
        dismissComponentUpdateDialog()
        composeRule.onNodeWithContentDescription("Settings")
            .performTouchInput { click() }
        composeRule.waitForIdle()

        val logo = composeRule.onNodeWithContentDescription("VRKA Logo")
        logo.performScrollTo()
        logo.assertIsDisplayed()

        val bounds = logo.getUnclippedBoundsInRoot()
        val height = (bounds.bottom - bounds.top).value
        val width = (bounds.right - bounds.left).value
        assertTrue("About logo must be at least 72dp, was ${height}dp", height >= 71.9f)
        assertTrue("About logo must stay within 84dp, was ${height}dp", height <= 84.1f)
        assertTrue("About logo must keep a square box, was ${width}x${height}dp", width <= 84.1f)
    }

    private fun dismissComponentUpdateDialog() {
        val later = composeRule.onAllNodesWithText("Later")
        if (later.fetchSemanticsNodes().isNotEmpty()) {
            later[0].performClick()
            composeRule.waitForIdle()
        }
    }
}
