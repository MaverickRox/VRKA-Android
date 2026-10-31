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
 * Renders the production [GlowProgressRenderer] into PNGs for the ColorOS 17 comparison, and
 * asserts the two contracts that are visible in those pixels: the wake sits behind the real
 * progress edge, and it disperses above and below the purple fill.
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

        render(File(output, "base_purple.png"), 0.51f, wakeVisible = false)
        render(File(output, "streak_without_haze.png"), 0.51f, hazeVisible = false)
        render(File(output, "complete_wake.png"), 0.51f)
        render(File(output, "low_012.png"), 0.12f)
        render(File(output, "medium_051.png"), 0.51f)
        render(File(output, "high_080.png"), 0.80f)
        render(File(output, "boundary_100.png"), 1f)

        val animationDir = File(output, "animated").apply { mkdirs() }
        val frames = 60
        for (frame in 0 until frames) {
            val t = frame / (frames - 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            render(File(animationDir, "frame_${"%03d".format(frame + 1)}.png"), eased)
        }
    }

    @Test
    fun glowNeverSpillsAheadOfTheProgressEdge() {
        val withWake = render(null, 0.43f)
        val withoutWake = render(null, 0.43f, wakeVisible = false)
        val headX = (WIDTH * 0.43f).toInt()

        for (x in (headX + 3) until WIDTH.toInt()) {
            for (y in 0 until HEIGHT.toInt()) {
                assertEquals(
                    "light leaked past the progress edge at ($x,$y)",
                    withoutWake.getPixel(x, y),
                    withWake.getPixel(x, y),
                )
            }
        }
    }

    @Test
    fun wakeDispersesBehindTheEdgeWithoutMovingTheFill() {
        val bitmap = render(null, 0.43f)
        val headX = (WIDTH * 0.43f).toInt()
        val centerY = (HEIGHT / 2f).toInt()
        val trackPixel = bitmap.getPixel((WIDTH - TRACK).toInt(), centerY)
        val trackLuma = luma(trackPixel)
        val backgroundLuma = luma(BACKGROUND)
        val centerLuma = luma(bitmap.getPixel(headX - 1, centerY))
        val aboveFill = luma(bitmap.getPixel((headX - TRACK / 2f).toInt(), (HEIGHT / 2f - TRACK * 0.8f).toInt()))
        val purple = bitmap.getPixel((headX - TRACK * 4f).toInt(), centerY)

        assertTrue("the wake must be brighter than the track", centerLuma > trackLuma + 40)
        assertTrue("the glow must rise above the purple fill", aboveFill > backgroundLuma + 8)
        assertTrue("the completed segment must stay purple", Color.blue(purple) > Color.red(purple) + 10)
    }

    @Test
    fun zeroProgressLeavesOnlyTheTrack() {
        val bitmap = render(null, 0f)
        val track = bitmap.getPixel((WIDTH / 2f).toInt(), (HEIGHT / 2f).toInt())
        val above = bitmap.getPixel((WIDTH / 2f).toInt(), (HEIGHT / 2f - TRACK).toInt())
        assertTrue("nothing may be filled at zero progress", Color.blue(track) < 60)
        assertEquals("no wake may exist at zero progress", BACKGROUND, above)
    }

    private fun render(
        file: File?,
        progress: Float,
        wakeVisible: Boolean = true,
        hazeVisible: Boolean = true,
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
            wakeVisible = wakeVisible,
            hazeVisible = hazeVisible,
        )
        if (file != null) {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun luma(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private companion object {
        const val WIDTH = 1080f
        const val TRACK = 24f
        const val HEIGHT = TRACK * GLOW_CANVAS_HEIGHT_RATIO
        const val BACKGROUND = 0xFF0D0B10.toInt()
        const val ACCENT = 0xFF8140DC.toInt()
    }
}
