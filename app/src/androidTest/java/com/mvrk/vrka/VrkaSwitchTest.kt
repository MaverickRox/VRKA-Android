package com.mvrk.vrka

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VrkaSwitchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "vrka-switch"

    @Test
    fun clickTogglesAndReportsTheNewState() {
        var changes = listOf<Boolean>()
        composeRule.setContent {
            VrkaTheme {
                var checked by remember { mutableStateOf(false) }
                VrkaSwitch(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        changes = changes + it
                    },
                    modifier = Modifier.testTag(tag),
                )
            }
        }

        composeRule.onNodeWithTag(tag).assertIsOff()
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.onNodeWithTag(tag).assertIsOn()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun horizontalSwipeTogglesInBothDirections() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            VrkaTheme {
                VrkaSwitch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag(tag),
                )
            }
        }

        composeRule.onNodeWithTag(tag).performTouchInput { swipeRight() }
        composeRule.onNodeWithTag(tag).assertIsOn()

        composeRule.onNodeWithTag(tag).performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag(tag).assertIsOff()
    }

    @Test
    fun disabledSwitchIgnoresClicksButKeepsItsState() {
        var changes = 0
        composeRule.setContent {
            VrkaTheme {
                VrkaSwitch(
                    checked = true,
                    onCheckedChange = { changes++ },
                    enabled = false,
                    modifier = Modifier.testTag(tag),
                )
            }
        }

        composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        composeRule.onNodeWithTag(tag).assertIsOn()
        composeRule.onNodeWithTag(tag).performClick()
        assertEquals(0, changes)
    }
}
