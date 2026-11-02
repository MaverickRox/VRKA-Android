package com.mvrk.vrka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the production [GlowProgressRenderer] at the reference bar's proportions and
 * asserts the visible contracts: a long backwards streak, independent dispersion, a purple
 * fill behind the light, and a crisp rounded head with no light ahead of it.
 */
@RunWith(AndroidJUnit4::class)
class ProgressPreviewCaptureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = GlowProgressRenderer()

    @Test
    fun captureProgressPreviewsAcrossTheRange() {
        val output = File(context.getExternalFilesDir(null), "progress-previews").apply { mkdirs() }
        val percentages = listOf(0, 1, 5, 12, 20, 27, 35, 43, 51, 58, 66, 80, 100)
        percentages.forEach { percent ->
            render(File(output, "progress_${"%03d".format(percent)}.png"), percent / 100f)
        }

        render(File(output, "progress_base.png"), 0.51f, showStreak = false, showDispersion = false)
        render(File(output, "progress_streak_only.png"), 0.51f, showDispersion = false)
        render(File(output, "progress_dispersion_only.png"), 0.51f, showStreak = false)
        render(File(output, "progress_combined.png"), 0.51f)
        render(File(output, "progress_low.png"), 0.12f)
        render(File(output, "progress_medium.png"), 0.51f)
        render(File(output, "progress_high.png"), 0.80f)
        render(File(output, "boundary_100.png"), 1f)
        render(File(output, "comparison_app_051.png"), 0.51f)
        // The reference recording's stable frame measures a 55.7% fill; the host comparison
        // pairs this render with that frame so both sides show the same progress.
        render(File(output, "comparison_app_0557.png"), 0.557f)

        render(File(output, "settle_000.png"), 1f)
        render(File(output, "settle_050.png"), 1f, settled = 0.5f)
        render(File(output, "settle_100.png"), 1f, settled = 1f)

        val animationDir = File(output, "animated").apply { mkdirs() }
        val frames = 60
        for (frame in 0 until frames) {
            val t = frame / (frames - 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            render(File(animationDir, "frame_${"%03d".format(frame + 1)}.png"), eased)
        }
    }

    @Test
    fun streakIsLongAndFadesBackwardsWithoutDispersion() {
        val headX = (WIDTH * 0.43f).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val base = render(null, 0.43f, showStreak = false, showDispersion = false)
        val streak = render(null, 0.43f, showDispersion = false)

        val head = luma(streak.getPixel(headX - 1, centerY))
        val near = luma(streak.getPixel(headX - (TRACK * 2f).toInt(), centerY))
        val far = luma(streak.getPixel(headX - (TRACK * 8f).toInt(), centerY))
        val baseFar = luma(base.getPixel(headX - (TRACK * 8f).toInt(), centerY))
        val ahead = luma(streak.getPixel(headX + TRACK.toInt(), centerY))
        val baseAhead = luma(base.getPixel(headX + TRACK.toInt(), centerY))
        val fartherAhead = luma(streak.getPixel(headX + (TRACK * 3f).toInt(), centerY))
        val baseFartherAhead = luma(base.getPixel(headX + (TRACK * 3f).toInt(), centerY))

        assertTrue("the head must be bright", head > 220)
        assertTrue("the streak must extend far behind the edge", far > baseFar + 8)
        assertTrue("brightness must fall towards the tail", head >= near - 2)
        assertTrue("the wake must fade progressively", near > far + 10)
        assertEquals("the streak layer must stay inside the fill", baseAhead, ahead)
        assertTrue("the head must remain the brightest point", head > ahead + 40)
        assertEquals(
            "no light may travel more than about three track heights ahead",
            baseFartherAhead,
            fartherAhead,
        )
    }

    @Test
    fun dispersionExtendsBeyondTheTrackAndFadesInsideTheCanvas() {
        val headX = (WIDTH * 0.43f).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val sampleX = headX - (TRACK * 8f).toInt()
        val aboveY = centerY - TRACK.toInt()
        val base = render(null, 0.43f, showStreak = false, showDispersion = false)
        val dispersion = render(null, 0.43f, showStreak = false)

        assertTrue(
            "dispersion must rise above the bar",
            luma(dispersion.getPixel(sampleX, aboveY)) >
                luma(base.getPixel(sampleX, aboveY)) + 8,
        )
        assertTrue(
            "dispersion must fall below the bar",
            luma(dispersion.getPixel(sampleX, centerY + TRACK.toInt())) >
                luma(base.getPixel(sampleX, centerY + TRACK.toInt())) + 8,
        )
        assertEquals(
            "the haze must reach zero before the canvas edge",
            BACKGROUND,
            dispersion.getPixel(sampleX, 0),
        )
        assertEquals(
            "the haze must reach zero before the canvas edge",
            BACKGROUND,
            dispersion.getPixel(sampleX, HEIGHT.toInt() - 1),
        )
    }

    @Test
    fun coneStartsAtTheHeadAndWidensBackwards() {
        val headX = (WIDTH * 0.43f).toInt()
        val offsetY = (HEIGHT / 2f).toInt() - (TRACK * 2.5f).toInt()
        val base = render(null, 0.43f, showStreak = false, showDispersion = false)
        val dispersion = render(null, 0.43f, showStreak = false)
        val nearX = headX - (TRACK * 2f).toInt()
        val farX = headX - (TRACK * 13f).toInt()
        // Just outside the track, whose half-height is half a track.
        val edgeY = (HEIGHT / 2f).toInt() - (TRACK * 0.75f).toInt()

        assertTrue(
            "the glow must begin right behind the head, not further back",
            luma(dispersion.getPixel(headX - TRACK.toInt(), edgeY)) >
                luma(base.getPixel(headX - TRACK.toInt(), edgeY)) + 8,
        )
        assertEquals(
            "the cone must stay narrow next to the head",
            base.getPixel(nearX, offsetY),
            dispersion.getPixel(nearX, offsetY),
        )
        assertTrue(
            "the cone must be wider further behind the head",
            luma(dispersion.getPixel(farX, offsetY)) > luma(base.getPixel(farX, offsetY)),
        )
    }

    @Test
    fun coneFadesTowardsPurpleAtTheTail() {
        val headX = (WIDTH * 0.43f).toInt()
        val aboveY = (HEIGHT / 2f).toInt() - TRACK.toInt()
        val pixel = render(null, 0.43f).getPixel(headX - (TRACK * 13f).toInt(), aboveY)

        assertTrue(
            "the tail of the glow must read as purple",
            Color.blue(pixel) > Color.green(pixel) + 8,
        )
    }

    @Test
    fun combinedRenderKeepsPurpleBehindARoundedHead() {
        val headX = (WIDTH * 0.43f).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val base = render(null, 0.43f, showStreak = false, showDispersion = false)
        val streakOnly = render(null, 0.43f, showDispersion = false)
        val combined = render(null, 0.43f)

        val purpleX = headX - (TRACK * 12f).toInt()
        val purple = combined.getPixel(purpleX, centerY)
        assertTrue(
            "the completed fill must remain purple behind the wake",
            Color.blue(purple) > Color.green(purple) + 40,
        )
        assertTrue(
            "the head must be near-white",
            minChannel(combined.getPixel(headX - 1, centerY)) > 220,
        )
        assertEquals(
            "nothing may light the track ahead of the head",
            base.getPixel(headX + 2, centerY),
            combined.getPixel(headX + 2, centerY),
        )
        assertTrue(
            "the head lens must be brighter than the spill",
            luma(streakOnly.getPixel(headX - 1, centerY)) >
                luma(streakOnly.getPixel(headX + TRACK.toInt(), centerY)) + 40,
        )
    }

    @Test
    fun opticalDispersionIsIndependentFromTheStreak() {
        val headX = (WIDTH * 0.51f).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val farX = headX - (TRACK * 8f).toInt()
        val aboveX = headX - (TRACK * 8f).toInt()
        val aboveY = centerY - TRACK.toInt()
        val base = render(null, 0.51f, showStreak = false, showDispersion = false)
        val streakOnly = render(null, 0.51f, showDispersion = false)
        val dispersionOnly = render(null, 0.51f, showStreak = false)
        val combined = render(null, 0.51f)

        assertTrue(
            "without dispersion the head core must still be near-white",
            minChannel(streakOnly.getPixel(headX - 1, centerY)) > 220,
        )
        assertTrue(
            "the streak must light the fill far behind the head",
            luma(streakOnly.getPixel(farX, centerY)) > luma(base.getPixel(farX, centerY)) + 8,
        )
        assertTrue(
            "the teardrop must light the space above the bar",
            luma(dispersionOnly.getPixel(aboveX, aboveY)) > luma(base.getPixel(aboveX, aboveY)) + 8,
        )
        assertEquals(
            "the streak layer must not light above the bar",
            base.getPixel(aboveX, aboveY),
            streakOnly.getPixel(aboveX, aboveY),
        )
        assertEquals(
            "the cone must sit under the bar, leaving the fill's colour untouched",
            streakOnly.getPixel(farX, centerY),
            combined.getPixel(farX, centerY),
        )
        assertTrue(
            "the purple fill remains visible in the streak-only render",
            Color.blue(streakOnly.getPixel(headX - (TRACK * 10f).toInt(), centerY)) >
                Color.green(streakOnly.getPixel(headX - (TRACK * 10f).toInt(), centerY)) + 40,
        )
    }

    @Test
    fun lightSurfaceKeepsThePurpleGlow() {
        val bitmap = Bitmap.createBitmap(WIDTH.toInt(), HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        renderer.draw(
            canvas = canvas,
            width = WIDTH,
            height = HEIGHT,
            trackHeight = TRACK,
            progress = 0.43f,
            trackColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
            accent = androidx.compose.ui.graphics.Color(ACCENT),
            lightSurface = true,
        )
        val output = File(context.getExternalFilesDir(null), "progress-previews").apply { mkdirs() }
        FileOutputStream(File(output, "progress_light.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixel = bitmap.getPixel(
            (WIDTH * 0.43f).toInt() - (TRACK * 13f).toInt(),
            (HEIGHT / 2f).toInt() - TRACK.toInt(),
        )
        assertTrue("the glow must tint a light surface", Color.green(pixel) < 245)
    }

    @Test
    fun finishedBarSettlesIntoSolidPurpleWithNoGlow() {
        val centerY = (HEIGHT / 2f).toInt()
        val headX = WIDTH.toInt() - (TRACK * 2f).toInt()
        val glowX = WIDTH.toInt() - (TRACK * 8f).toInt()
        val glowY = centerY - TRACK.toInt()
        val running = render(null, 1f)
        val settling = render(null, 1f, settled = 0.5f)
        val settled = render(null, 1f, settled = 1f)

        assertTrue(
            "a running head is white",
            minChannel(running.getPixel(headX, centerY)) > 200,
        )
        assertEquals(
            "a settled head is the fill's purple",
            ACCENT,
            settled.getPixel(headX, centerY),
        )
        assertEquals(
            "a settled bar is one solid purple",
            settled.getPixel((TRACK * 2f).toInt(), centerY),
            settled.getPixel(headX, centerY),
        )
        assertEquals(
            "a settled bar has no glow",
            BACKGROUND,
            settled.getPixel(glowX, glowY),
        )
        val base = luma(BACKGROUND)
        val runningGlow = luma(running.getPixel(glowX, glowY)) - base
        val settlingGlow = luma(settling.getPixel(glowX, glowY)) - base
        assertTrue(
            "the glow fades part-way through, was $runningGlow then $settlingGlow",
            settlingGlow in 1 until runningGlow,
        )
    }

    @Test
    fun zeroProgressLeavesOnlyTheTrack() {
        val bitmap = render(null, 0f)
        val centerY = (HEIGHT / 2f).toInt()
        val track = bitmap.getPixel((WIDTH / 2f).toInt(), centerY)
        val above = bitmap.getPixel((WIDTH / 2f).toInt(), centerY - (TRACK * 2f).toInt())
        assertTrue("nothing may be filled at zero progress", Color.blue(track) < 60)
        assertEquals("no wake may exist at zero progress", BACKGROUND, above)
    }

    @Test
    fun noPixelAheadOfTheHeadExceedsTheHeadBrightness() {
        val progress = 0.43f
        val bitmap = render(null, progress)
        val headX = (WIDTH * progress).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val head = luma(bitmap.getPixel(headX - 1, centerY))
        var maximumAhead = 0
        for (x in (headX + 2) until WIDTH.toInt()) {
            maximumAhead = maxOf(maximumAhead, luma(bitmap.getPixel(x, centerY)))
        }
        assertTrue("light must not cross the head's brightness", maximumAhead < head)
        assertTrue(
            "forward spill must be subtle, not a second bar",
            luma(bitmap.getPixel(headX + (TRACK * 3f).toInt(), centerY)) < head - 30,
        )
    }

    private fun render(
        file: File?,
        progress: Float,
        settled: Float = 0f,
        showStreak: Boolean = true,
        showDispersion: Boolean = true,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH.toInt(), HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        renderer.draw(
            canvas = canvas,
            width = WIDTH,
            height = HEIGHT,
            trackHeight = TRACK,
            progress = progress,
            trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
            accent = androidx.compose.ui.graphics.Color(ACCENT),
            settled = settled,
            showStreak = showStreak,
            showDispersion = showDispersion,
        )
        if (file != null) {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun luma(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun minChannel(color: Int): Int =
        minOf(Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val WIDTH = 1080f
        // 14 px on a 1080 px bar matches the reference's 14 px on 1158 px.
        const val TRACK = 14f
        const val HEIGHT = TRACK * GLOW_CANVAS_HEIGHT_RATIO
        const val BACKGROUND = 0xFF0D0B10.toInt()
        const val ACCENT = 0xFF8140DC.toInt()
    }
}
