package com.mvrk.vrka

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val SwitchTrackWidth = 63.dp
private val SwitchTrackHeight = 28.dp
private val SwitchTrackRadius = 14.dp
private val SwitchThumbWidth = 37.dp
private val SwitchThumbHeight = 24.dp
private val SwitchThumbRadius = 12.dp
private val SwitchInset = 2.dp
private val SwitchTouchWidth = 71.dp
private val SwitchTouchHeight = 44.dp
private const val SwitchTravelDp = 22f
private const val SwitchDurationMs = 180
private val SwitchEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/**
 * Wide-oval iOS-style switch ported from Mornye 5.2.6 (`AppSwitch` / `_MornyeSwitch`,
 * LGPL-3.0). Geometry, 180 ms ease-out-cubic travel, drag-to-toggle and the 44 dp touch
 * target are preserved; the active track uses the VRKA purple instead of system green.
 */
@Composable
internal fun VrkaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = onCheckedChange != null,
) {
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val colors = LocalVrkaColors.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val travelPx = with(LocalDensity.current) { SwitchTravelDp.dp.toPx() }
    val haptics = LocalHapticFeedback.current

    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val settledPosition by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(SwitchDurationMs, easing = SwitchEasing),
        label = "switch thumb",
    )
    val position = dragPosition ?: settledPosition

    val inactive = if (colors.isLight) Color(0xFF8E8E93) else Color(0xFF39393D)
    val trackColor by animateColorAsState(
        targetValue = if (position >= 0.5f) VrkaTokens.Accent else inactive,
        animationSpec = if (reduceMotion) snap() else tween(SwitchDurationMs, easing = SwitchEasing),
        label = "switch track",
    )

    fun commit(target: Boolean) {
        if (!enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onCheckedChange?.invoke(target)
    }

    Box(
        modifier = modifier
            .size(width = SwitchTouchWidth, height = SwitchTouchHeight)
            .clip(RoundedCornerShape(SwitchTouchHeight / 2))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = ::commit,
            )
            .pointerInput(enabled, checked, direction, travelPx) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val target = (dragPosition ?: if (checked) 1f else 0f) >= 0.5f
                        dragPosition = null
                        if (target != checked) commit(target)
                    },
                    onDragCancel = { dragPosition = null },
                ) { change, dragAmount ->
                    change.consume()
                    val start = dragPosition ?: if (checked) 1f else 0f
                    dragPosition = (start + dragAmount * direction / travelPx).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = SwitchTrackWidth, height = SwitchTrackHeight)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(RoundedCornerShape(SwitchTrackRadius))
                .background(trackColor)
                .padding(SwitchInset),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((travelPx * position * direction).roundToInt(), 0) }
                    .size(width = SwitchThumbWidth, height = SwitchThumbHeight)
                    .clip(RoundedCornerShape(SwitchThumbRadius))
                    .background(Color.White),
            )
        }
    }
}
