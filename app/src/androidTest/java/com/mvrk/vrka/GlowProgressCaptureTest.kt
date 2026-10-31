package com.mvrk.vrka

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the production [VrkaGlowProgressBar] at the strip (3dp) and queue (4dp) track
 * heights for the reviewed progress positions, then stores one PNG per position in the
 * shared QA folder so the glow direction can be inspected outside a unit test.
 */
@RunWith(AndroidJUnit4::class)
class GlowProgressCaptureTest {

    @get:Rule
    val rule = createComposeRule()

    private val percentages = listOf(1, 5, 12, 20, 27, 35, 43, 51, 58, 66, 80, 100)

    @Test
    fun captureStripPreviews() {
        captureHeight(label = "strip", trackHeight = 3.dp)
    }

    @Test
    fun captureQueuePreviews() {
        captureHeight(label = "queue", trackHeight = 4.dp)
    }

    private fun captureHeight(label: String, trackHeight: Dp) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VrkaTheme(themeMode = ThemeMode.DARK, amoled = true) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VrkaTokens.BackgroundAmoled)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    percentages.forEach { percentage ->
                        GlowPreviewRow(
                            percentage = percentage,
                            trackHeight = trackHeight,
                            tag = "${label}_$percentage",
                        )
                    }
                }
            }
        }
        // Settle the 260 ms progress tween deterministically.
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()

        val outDir = File(sharedQaDir(), "glow").apply { mkdirs() }
        val localDir = File(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir(null),
            "glow_qa/$label",
        ).apply { mkdirs() }

        percentages.forEach { percentage ->
            val bitmap = rule.onNodeWithTag("${label}_$percentage")
                .captureToImage()
                .asAndroidBitmap()
                .copy(Bitmap.Config.ARGB_8888, false)
            val name = String.format(java.util.Locale.US, "wp4_%s_%03d.png", label, percentage)
            FileOutputStream(File(localDir, name)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            copyToShared(File(localDir, name), outDir)
        }
    }

    @Composable
    private fun GlowPreviewRow(percentage: Int, trackHeight: Dp, tag: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .background(VrkaTokens.BackgroundAmoled)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Text(
                text = "$percentage%",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = VrkaTokens.TextSecondary,
            )
            Spacer(Modifier.height(2.dp))
            VrkaGlowProgressBar(
                progress = percentage / 100f,
                trackHeight = trackHeight,
            )
        }
    }

    private fun sharedQaDir(): File =
        File("/sdcard/Download/vrka_qa")

    private fun copyToShared(source: File, outDir: File) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            automation.executeShellCommand("mkdir -p ${outDir.absolutePath}").close()
            automation.executeShellCommand(
                "cp ${source.absolutePath} ${outDir.absolutePath}/${source.name}",
            ).close()
        }
    }
}
