package com.mvrk.vrka

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar must slide to every new value over [GLOW_PROGRESS_SLIDE_MILLIS] rather than jump,
 * including when it first appears part-way through a download.
 */
@RunWith(AndroidJUnit4::class)
class GlowProgressAnimationTest {
    @get:Rule
    val rule = createComposeRule()

    private val progress = mutableFloatStateOf(0.8f)

    private fun setBar() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VrkaTheme(themeMode = ThemeMode.DARK, amoled = true) {
                VrkaGlowProgressBar(
                    progress = progress.floatValue,
                    modifier = Modifier
                        .testTag(TAG)
                        .background(androidx.compose.ui.graphics.Color.Black),
                )
            }
        }
    }

    @Test
    fun firstAppearanceSlidesUpFromZero() {
        setBar()
        rule.mainClock.advanceTimeByFrame()
        val start = headFraction()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS / 4L)
        val middle = headFraction()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS.toLong())

        assertTrue("the bar must not appear at its target, was $start", start < 0.3f)
        assertTrue("the bar must be sliding, was $middle", middle > start && middle < 0.75f)
        assertEquals(0.8f, headFraction(), 0.02f)
    }

    @Test
    fun laterChangesSlideInBothDirections() {
        setBar()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS * 2L)

        progress.floatValue = 0.2f
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS / 8L)
        val middle = headFraction()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS.toLong())

        assertTrue("the bar must slide backwards, was $middle", middle > 0.25f && middle < 0.78f)
        assertEquals(0.2f, headFraction(), 0.02f)
    }

    @Test
    fun reachingTheEndFadesTheGlowAndLeavesSolidPurple() {
        progress.floatValue = 1f
        setBar()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS + 50L)
        val running = capture()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SETTLE_MILLIS / 2L)
        val settling = capture()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SETTLE_MILLIS.toLong())
        val settled = capture()

        val centerY = running.height / 2
        val headX = running.width - running.height / 2
        val glowX = running.width - running.height * 2
        val glowY = centerY - running.height / 4
        assertTrue(
            "the head starts white",
            Color.green(running.getPixel(headX, centerY)) > 200,
        )
        assertTrue(
            "the head ends purple",
            Color.green(settled.getPixel(headX, centerY)) < 100,
        )
        val glow = Color.blue(running.getPixel(glowX, glowY))
        val glowSettling = Color.blue(settling.getPixel(glowX, glowY))
        assertTrue("the glow must fade gradually, was $glow then $glowSettling", glowSettling in 1 until glow)
        assertEquals(android.graphics.Color.BLACK, settled.getPixel(glowX, glowY))

        // Progress restarting, such as the audio stream after the video, brings the light back.
        progress.floatValue = 0.5f
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(GLOW_PROGRESS_SLIDE_MILLIS + 50L)
        assertTrue(
            "the glow must return when progress restarts",
            Color.blue(capture().getPixel(running.width / 2 - running.height, glowY)) > 0,
        )
    }

    /** The bright head is the last pixel on the track's centre row brighter than the track. */
    private fun headFraction(): Float {
        val bitmap = capture()
        val y = bitmap.height / 2
        var last = 0
        for (x in 0 until bitmap.width) {
            if (Color.blue(bitmap.getPixel(x, y)) > 150) last = x
        }
        return last / (bitmap.width - 1f)
    }

    private fun capture(): Bitmap {
        rule.waitForIdle()
        return rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
    }

    private companion object {
        const val TAG = "glow_bar"
    }
}
