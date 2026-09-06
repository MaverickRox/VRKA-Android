package com.mvrk.vrka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mvrk.vrka.ui.backdrop.Backdrop
import com.mvrk.vrka.ui.backdrop.BackdropEffectScope
import com.mvrk.vrka.ui.backdrop.drawBackdrop
import com.mvrk.vrka.ui.backdrop.effects.blur
import com.mvrk.vrka.ui.backdrop.highlight.Highlight
import com.mvrk.vrka.ui.backdrop.highlight.HighlightStyle
import com.mvrk.vrka.ui.backdrop.isRenderEffectSupported

/**
 * CompositionLocal holding the active screen [Backdrop] sampled by floating glass chrome.
 */
val LocalAppBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Applies backdrop-sampled blur, specular rim highlight,
 * and translucent surface tinting to the target component.
 *
 * Falls back to translucent tinted surface on devices below Android 12 (API 31)
 * or when no [LocalAppBackdrop] is present.
 */
@Composable
fun Modifier.vrkaGlass(
    shape: Shape = RoundedCornerShape(VrkaTokens.RadiusCard),
    blurRadius: Dp = 16.dp,
    tintColor: Color = Color(0xCC0F0F14),
    highlightAlpha: Float = 0.25f,
    borderWidth: Dp = 1.dp,
    borderColor: Color = Color(0x22FFFFFF),
    backdrop: Backdrop? = LocalAppBackdrop.current,
): Modifier {
    val density = LocalDensity.current
    val blurPx = with(density) { blurRadius.toPx() }

    if (backdrop == null || !isRenderEffectSupported()) {
        return this
            .clip(shape)
            .background(tintColor)
            .border(borderWidth, borderColor, shape)
    }

    val shapeBlock: () -> Shape = remember(shape) { { shape } }
    val effectsBlock: BackdropEffectScope.() -> Unit = remember(blurPx) {
        {
            if (blurPx > 0f) {
                blur(blurPx)
            }
        }
    }

    val highlightBlock: (() -> Highlight?)? = remember(highlightAlpha) {
        if (highlightAlpha > 0f) {
            {
                Highlight(
                    width = 0.8.dp,
                    style = HighlightStyle.Default(
                        color = Color.White.copy(alpha = highlightAlpha.coerceIn(0f, 1f)),
                        angle = 50f,
                    ),
                )
            }
        } else null
    }

    val surfaceBlock: DrawScope.() -> Unit = remember(tintColor) {
        {
            drawRect(color = tintColor, size = size)
        }
    }

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shapeBlock,
            effects = effectsBlock,
            highlight = highlightBlock,
            shadow = null,
            onDrawSurface = surfaceBlock,
            backdropScale = 1f,
        )
        .clip(shape)
        .border(borderWidth, borderColor, shape)
}
