package com.mvrk.vrka

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Canvas progress bar must expose a numeric range, current progress, a progress role and
 * one label. It must not add a state description that would make TalkBack announce the
 * percentage twice.
 */
@RunWith(AndroidJUnit4::class)
class ProgressAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private var progress by mutableFloatStateOf(0f)

    private fun setBar() {
        rule.setContent {
            VrkaTheme(themeMode = ThemeMode.DARK, amoled = true) {
                VrkaGlowProgressBar(
                    progress = progress,
                    contentDescription = "Download progress",
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
    }

    @Test
    fun zeroPercentExposesAnEmptyDeterminateRange() {
        setBar()
        val node = rule.onNodeWithTag(TAG)

        node.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f, 0))
    }

    @Test
    fun intermediateAndCompleteValuesExposeTheReportedProgress() {
        setBar()

        rule.runOnIdle { progress = 0.42f }
        rule.onNodeWithTag(TAG).assertRangeInfoEquals(ProgressBarRangeInfo(0.42f, 0f..1f, 0))

        rule.runOnIdle { progress = 1f }
        rule.onNodeWithTag(TAG).assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f, 0))
    }

    @Test
    fun labelIsPresentOnceAndNoDuplicateStateDescriptionIsAdded() {
        setBar()
        val node = rule.onNodeWithContentDescription("Download progress")

        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        node.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f, 0))
    }

    private companion object {
        const val TAG = "download-progress"
    }
}
