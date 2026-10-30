package com.mvrk.vrka

import android.content.Context
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import com.mvrk.vrka.ui.backdrop.Backdrop
import com.mvrk.vrka.ui.backdrop.backdrops.layerBackdrop
import com.mvrk.vrka.ui.backdrop.backdrops.rememberLayerBackdrop
import com.mvrk.vrka.ui.backdrop.drawBackdrop
import com.mvrk.vrka.ui.backdrop.isRuntimeShaderSupported
import com.mvrk.vrka.ui.backdrop.effects.blur
import com.mvrk.vrka.ui.backdrop.effects.lens
import com.mvrk.vrka.ui.backdrop.highlight.Highlight
import com.mvrk.vrka.ui.backdrop.highlight.HighlightStyle
import com.mvrk.vrka.ui.backdrop.isRenderEffectSupported
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VrkaTokens {
    // Surfaces
    val BackgroundAmoled: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.background
    val SurfaceCard: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.surfaceCard
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.surfaceElevated
    val SurfaceInset: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.surfaceInset
    val SurfaceFloatingNav: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.surfaceFloatingNav

    // Borders
    val BorderSubtle: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.borderSubtle
    val BorderNav: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.borderNav
    val BorderActive: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.borderActive

    // Accents
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.accent
    val AccentLight: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.accentLight
    val AccentContainer: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.accentContainer

    // Status
    val Success: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.success
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.warning
    val Error: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.error
    val Destructive: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.error

    // Text
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalVrkaColors.current.textTertiary

    // Radii
    val RadiusSmall = 8.dp
    val RadiusMedium = 14.dp
    val RadiusCard = 18.dp
    val RadiusPill = 28.dp

    val SettleSpring: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)
}

@Composable
fun VrkaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(VrkaTokens.RadiusCard),
        color = VrkaTokens.SurfaceCard,
        contentColor = VrkaTokens.TextPrimary,
        border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        modifier = modifier.fillMaxWidth(),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun VrkaInsetSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = VrkaTokens.RadiusMedium,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(VrkaTokens.SurfaceInset)
            .border(1.dp, VrkaTokens.BorderSubtle, RoundedCornerShape(cornerRadius)),
    ) {
        content()
    }
}

@Composable
fun VrkaSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = VrkaTokens.TextPrimary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        VrkaCard(content = content)
    }
}

@Composable
fun <T> VrkaSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    var rowSize by remember { mutableStateOf(IntSize.Zero) }

    val n = items.size
    val pillShape = RoundedCornerShape(VrkaTokens.RadiusMedium)

    val tabWidthPx = if (rowSize.width > 0 && n > 0) rowSize.width.toFloat() / n else 0f
    val puckInsetPx = with(density) { 6.dp.toPx() }
    val puckWidthPx = (tabWidthPx - puckInsetPx).coerceAtLeast(0f)
    val targetIndicatorOffsetPx = if (tabWidthPx > 0f) {
        selectedIndex * tabWidthPx + (tabWidthPx - puckWidthPx) / 2f
    } else 0f

    val animatedOffsetPx by animateFloatAsState(
        targetValue = targetIndicatorOffsetPx,
        animationSpec = VrkaTokens.SettleSpring,
        label = "segmentOffset",
    )

    Box(
        modifier = modifier
            .clip(pillShape)
            .background(VrkaTokens.SurfaceInset)
            .border(1.dp, VrkaTokens.BorderSubtle, pillShape)
            .padding(4.dp)
            .onSizeChanged { rowSize = it },
    ) {
        // Sliding indicator puck
        if (tabWidthPx > 0f && puckWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { puckWidthPx.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = animatedOffsetPx
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(VrkaTokens.AccentContainer)
                    .border(1.dp, VrkaTokens.BorderActive, RoundedCornerShape(10.dp)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                    animationSpec = tween(150),
                    label = "segmentTextColor",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onItemSelected(item)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(item),
                        style = if (isMonospace) {
                            MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily)
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun VrkaStatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun VrkaSettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    isSubtitleMono: Boolean = false,
    trailingContent: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (isSubtitleMono) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun VrkaDivider() {
    HorizontalDivider(
        color = VrkaTokens.BorderSubtle,
        thickness = 1.dp,
    )
}

@Composable
fun VrkaChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) VrkaTokens.AccentContainer else VrkaTokens.SurfaceInset,
        animationSpec = tween(150),
        label = "chipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle,
        animationSpec = tween(150),
        label = "chipBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
        animationSpec = tween(150),
        label = "chipText",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    ) {
        Text(
            text = label,
            style = if (isMonospace) {
                MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily)
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun VrkaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @androidx.annotation.DrawableRes iconRes: Int? = null,
    height: Dp = 50.dp,
) {
    val haptic = LocalHapticFeedback.current
    androidx.compose.material3.Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(VrkaTokens.RadiusMedium),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = VrkaTokens.Accent,
            contentColor = Color.White,
            disabledContainerColor = VrkaTokens.Accent.copy(alpha = 0.22f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        if (iconRes != null) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = VrkaMonoFamily,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
fun VrkaOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 38.dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    contentColor: Color = VrkaTokens.TextPrimary,
    borderColor: Color = VrkaTokens.BorderSubtle,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = VrkaTokens.TextTertiary,
        ),
        modifier = modifier.height(height),
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun VrkaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    enabled: Boolean = true,
) {
    val resolvedColor = if (color != Color.Unspecified) color else VrkaTokens.TextSecondary
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) resolvedColor else VrkaTokens.TextTertiary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

enum class VrkaDestination(
    val label: String,
    @param:androidx.annotation.DrawableRes val iconRes: Int,
    @param:androidx.annotation.StringRes val labelRes: Int,
) {
    DOWNLOAD("Download", R.drawable.ic_download, R.string.nav_download),
    QUEUE("Queue", R.drawable.ic_queue, R.string.nav_queue),
    HISTORY("History", R.drawable.ic_history, R.string.nav_history),
    SETTINGS("Settings", R.drawable.ic_settings, R.string.nav_settings),
}

@Composable
fun VrkaSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = VrkaMonoFamily,
                letterSpacing = 1.2.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = VrkaTokens.TextTertiary,
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
            )
        }
    }
}

@Composable
fun VrkaGlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(VrkaTokens.RadiusCard),
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalVrkaColors.current
    val cardGlassTint = if (colors.isLight) Color(0xD9FFFFFF) else Color(0xD90F0F14)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .vrkaGlass(
                shape = shape,
                blurRadius = 16.dp,
                tintColor = cardGlassTint,
                highlightAlpha = if (colors.isLight) 0.35f else 0.20f,
                borderColor = VrkaTokens.BorderSubtle,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(contentPadding),
    ) {
        Column(content = content)
    }
}

@Composable
fun VrkaSectionContainer(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = shape,
        color = VrkaTokens.SurfaceCard,
        border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            content = content,
        )
    }
}

// Reference geometry from the v5.0.2 navigation bar (commit fd12ad5): 64dp bar,
// 54dp resting pill, 32dp radii. The pill radius equals half the resting height, so
// the pill is a true capsule and the pressed lens stays concentric instead of
// turning into a rounded square when it grows.
val NAV_BAR_HEIGHT = 64.dp
val NAV_BAR_CORNER_RADIUS = 32.dp
val NAV_BAR_HORIZONTAL_MARGIN = 20.dp
val NAV_BAR_MAX_WIDTH = 600.dp

// MornyeSelectionPill padding: 6dp horizontal, 5dp vertical inside the 64dp bar.
val NAV_TAB_INSET_HORIZONTAL = 6.dp
val NAV_TAB_INSET_VERTICAL = 5.dp
val NAV_INDICATOR_HEIGHT = NAV_BAR_HEIGHT - (NAV_TAB_INSET_VERTICAL * 2) // 54.dp
val NAV_INDICATOR_CORNER_RADIUS = NAV_BAR_CORNER_RADIUS // 32.dp
// Keep the label inside the pill with real clearance: the pill is inset 6dp per side,
// the content is padded 8dp, so label glyphs never touch the pill edge.
internal val NAV_ITEM_HORIZONTAL_CONTENT_PADDING = 8.dp
internal val NAV_LABEL_FONT_SIZE = 10.sp
internal val NAV_LABEL_LINE_HEIGHT = 12.sp

// MornyeSelectionPill defaults: _heldWidthGrowth / _heldHeightGrowth.
const val NAV_PRESS_WIDTH_GROWTH = 0.28f
const val NAV_PRESS_HEIGHT_GROWTH = 0.24f

internal fun navSelectedIndex(position: Float, lastIndex: Int): Int =
    if (lastIndex < 0) 0 else position.roundToInt().coerceIn(0, lastIndex)

internal fun navItemFocus(position: Float, index: Int): Float =
    (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)

// Mornye curves: Curves.easeOutCubic / Curves.easeInCubic.
internal val NAV_EASE_OUT_CUBIC = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
internal val NAV_EASE_IN_CUBIC = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

internal data class NavItemMeasuredBounds(
    val slotLeftPx: Float = 0f,
    val slotWidthPx: Float = 0f,
    val contentLeftInSlotPx: Float = 0f,
    val contentWidthPx: Float = 0f,
)

internal data class NavIndicatorBounds(val leftPx: Float, val widthPx: Float)

internal fun calculateNavIndicatorBounds(
    position: Float,
    items: List<NavItemMeasuredBounds>,
    slotInsetPx: Float = 4f,
): NavIndicatorBounds? {
    if (items.isEmpty() || items.any { it.slotWidthPx <= 0f }) return null
    val activePosition = position.coerceIn(0f, items.lastIndex.toFloat())
    val leftIndex = activePosition.toInt().coerceIn(0, items.lastIndex)
    val rightIndex = (leftIndex + 1).coerceAtMost(items.lastIndex)
    val fraction = (activePosition - leftIndex).coerceIn(0f, 1f)

    fun slotIndicator(index: Int): NavIndicatorBounds {
        val item = items[index]
        val slotInset = slotInsetPx.coerceAtLeast(0f)
        val width = (item.slotWidthPx - (slotInset * 2f)).coerceAtLeast(0f)
        val left = item.slotLeftPx + slotInset
        return NavIndicatorBounds(left, width)
    }

    val start = slotIndicator(leftIndex)
    val end = slotIndicator(rightIndex)
    return NavIndicatorBounds(
        leftPx = start.leftPx + (end.leftPx - start.leftPx) * fraction,
        widthPx = start.widthPx + (end.widthPx - start.widthPx) * fraction,
    )
}

internal data class NavPressLensBounds(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
)

/**
 * Mornye grows the held capsule by 28% horizontally and 24% vertically around
 * its slot while the finger is down. The bar clips the result, so the height is
 * bounded by the capsule while the horizontal center stays on the slot.
 */
internal fun calculateNavPressLensBounds(
    indicator: NavIndicatorBounds,
    pressProgress: Float,
    containerHeightPx: Float,
    restingHeightPx: Float,
): NavPressLensBounds {
    val progress = pressProgress.coerceIn(0f, 1f)
    val width = (indicator.widthPx * (1f + NAV_PRESS_WIDTH_GROWTH * progress)).coerceAtLeast(0f)
    val rawHeight = (restingHeightPx * (1f + NAV_PRESS_HEIGHT_GROWTH * progress)).coerceAtLeast(0f)
    val height = rawHeight.coerceAtMost(containerHeightPx.coerceAtLeast(0f))
    return NavPressLensBounds(
        leftPx = indicator.leftPx + (indicator.widthPx - width) / 2f,
        topPx = (containerHeightPx - height) / 2f,
        widthPx = width,
        heightPx = height,
    )
}

/**
 * The held lens requires a backdrop to sample, render-effect support, the lens
 * shader, and permission to animate; high-contrast and reduced-motion users get
 * the plain resting capsule instead.
 */
internal fun shouldEnableNavPressLens(
    hasBackdrop: Boolean,
    renderEffectSupported: Boolean,
    shaderSupported: Boolean,
    reduceMotion: Boolean,
    highContrast: Boolean,
): Boolean = hasBackdrop && renderEffectSupported && shaderSupported &&
    !reduceMotion && !highContrast

private fun isHighContrastEnabled(context: Context): Boolean = runCatching {
    android.provider.Settings.Secure.getInt(
        context.contentResolver,
        "high_text_contrast_enabled",
        0,
    ) != 0
}.getOrDefault(false)


val NAV_ACTIVE_DARK = Color(0xFFF5F3FF)
val NAV_ACTIVE_LIGHT = Color(0xFF1E1B2E)
val NAV_UNSELECTED_DARK = Color(0xFF888496)
val NAV_UNSELECTED_LIGHT = Color(0xFF6B6778)

@Composable
fun VrkaFloatingNavBar(
    selectedDestination: VrkaDestination,
    onDestinationSelected: (VrkaDestination) -> Unit,
    modifier: Modifier = Modifier,
    pagerPosition: Float? = null,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val colors = LocalVrkaColors.current
    val backdrop = LocalAppBackdrop.current
    val context = LocalContext.current
    val destinations = remember { VrkaDestination.entries }
    val selectedIndex = destinations.indexOf(selectedDestination).coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestPagerPosition by rememberUpdatedState(pagerPosition)
    val latestOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
    // Animator scale 0 is Android's reduced-motion switch. Like Mornye, the held lens
    // stays off entirely and the pill jumps between slots instead of animating.
    val reduceMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
    val highContrast = remember(context) { isHighContrastEnabled(context) }
    val pressLensEnabled = shouldEnableNavPressLens(
        hasBackdrop = backdrop != null,
        renderEffectSupported = isRenderEffectSupported(),
        shaderSupported = isRuntimeShaderSupported(),
        reduceMotion = reduceMotion,
        highContrast = highContrast,
    )
    // Records the composed bar (glass capsule + icons) once per draw budget so the
    // pressed lens can refract the icons as well as the page, like Mornye's
    // MornyeSelectionPill lens.
    val barBackdrop = rememberLayerBackdrop()

    val capsuleBorderBrush = remember(colors.isLight) {
        if (colors.isLight) {
            Brush.verticalGradient(
                listOf(
                    Color(0x28000000),
                    Color(0x0E000000),
                    Color(0x18000000),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color(0x33FFFFFF),
                    Color(0x12FFFFFF),
                    Color(0x0AFFFFFF),
                ),
            )
        }
    }

    val ambientShadowPaint = remember(colors.isLight, density) {
        val shadowAlpha = if (colors.isLight) 0x18 else 0x2A
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(shadowAlpha, 0x00, 0x00, 0x00)
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(
                with(density) { 10.dp.toPx() },
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    val indicatorPosition = remember { Animatable(selectedIndex.toFloat()) }
    var pendingPagerSyncFrom by remember { mutableStateOf<Float?>(null) }
    var pendingPagerSyncTarget by remember { mutableIntStateOf(-1) }
    var itemBounds by remember { mutableStateOf(List(destinations.size) { NavItemMeasuredBounds() }) }
    var pressedSlotIndex by remember { mutableIntStateOf(selectedIndex) }
    var isPressing by remember { mutableStateOf(false) }
    val pressProgress = remember { Animatable(0f) }

    LaunchedEffect(isPressing, pressLensEnabled) {
        if (!pressLensEnabled) {
            pressProgress.snapTo(0f)
            return@LaunchedEffect
        }
        if (isPressing) {
            pressProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240, easing = NAV_EASE_OUT_CUBIC),
            )
        } else {
            // Mornye reverses with easeInCubic over the remaining fraction of the
            // 240ms hold animation, so releasing early shortens the contraction.
            val remaining = (240 * pressProgress.value).roundToInt().coerceIn(1, 240)
            pressProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = remaining, easing = NAV_EASE_IN_CUBIC),
            )
        }
    }

    LaunchedEffect(pagerPosition, selectedIndex, pendingPagerSyncFrom, pendingPagerSyncTarget, isDragging) {
        if (!isDragging) {
            val target = (pagerPosition ?: selectedIndex.toFloat())
                .coerceIn(0f, destinations.lastIndex.toFloat())
            val waitingFrom = pendingPagerSyncFrom
            if (waitingFrom != null && pendingPagerSyncTarget >= 0 && pagerPosition != null &&
                kotlin.math.abs(pagerPosition - waitingFrom) < 0.015f &&
                kotlin.math.abs(pagerPosition - pendingPagerSyncTarget.toFloat()) >= 0.015f
            ) {
                return@LaunchedEffect
            }
            pendingPagerSyncFrom = null
            pendingPagerSyncTarget = -1
            if (pagerPosition != null) {
                indicatorPosition.snapTo(target)
            } else {
                indicatorPosition.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 220, easing = NAV_EASE_OUT_CUBIC),
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = NAV_BAR_MAX_WIDTH)
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .padding(horizontal = NAV_BAR_HORIZONTAL_MARGIN),
    ) {
        val capsuleWidthPx = constraints.maxWidth.toFloat()
        val capsuleHeightPx = constraints.maxHeight.toFloat()
        val measuredWidths = itemBounds.map { it.slotWidthPx }.filter { it > 0f }
        val slotWidthPx = measuredWidths.takeIf { it.size == destinations.size }
            ?.average()?.toFloat() ?: if (destinations.isNotEmpty()) capsuleWidthPx / destinations.size else 0f
        val insetPx = with(density) { NAV_TAB_INSET_HORIZONTAL.toPx() }
        val restingHeightPx = with(density) { NAV_INDICATOR_HEIGHT.toPx() }
        val pillCornerRadiusPx = with(density) { NAV_INDICATOR_CORNER_RADIUS.toPx() }
        val measuredBoundsReady = itemBounds.size == destinations.size && itemBounds.all {
            it.slotWidthPx > 0f
        }
        val barCornerRadiusPx = with(density) { NAV_BAR_CORNER_RADIUS.toPx() }
        val barShape = RoundedCornerShape(NAV_BAR_CORNER_RADIUS)
        // Continuous position is only read in draw and graphics-layer phases, so pager
        // animation and drag frames invalidate drawing instead of recomposing the bar.
        val positionProvider = remember {
            { if (isDragging) dragPosition else indicatorPosition.value }
        }
        val lensVisible by remember(pressLensEnabled) {
            derivedStateOf { pressLensEnabled && pressProgress.value > 0f }
        }
        val frozenRecording = remember(pressLensEnabled) { { !lensVisible } }
        val pillColor = MaterialTheme.colorScheme.onSurface
        val pillFillAlpha = if (colors.isLight) 0.08f else 0.12f

        // Outer soft ambient shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRoundRect(
                            0f,
                            with(density) { 2.dp.toPx() },
                            size.width,
                            size.height + with(density) { 2.dp.toPx() },
                            barCornerRadiusPx,
                            barCornerRadiusPx,
                            ambientShadowPaint,
                        )
                    }
                },
        )

        // The composed bar is recorded once per draw budget. The held lens below
        // samples this layer so it refracts the icons as well as the page.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Only record the bar while the held lens can actually sample it. When the
                // lens is hidden the tree draws live and no capture is produced, so pager
                // motion and pill animation cannot force a full-subtree record every frame.
                .then(
                    if (pressLensEnabled) {
                        Modifier.layerBackdrop(barBackdrop, frozen = frozenRecording)
                    } else {
                        Modifier
                    },
                )
                .pointerInput(slotWidthPx, destinations.size, pressLensEnabled) {
                    if (slotWidthPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var isDrag = false
                            val basePos = if (isDragging) dragPosition else indicatorPosition.value
                            var lastHaptic = basePos.roundToInt().coerceIn(0, destinations.lastIndex)

                            if (pressLensEnabled) {
                                val slot = itemBounds.indexOfFirst { bounds ->
                                    bounds.slotWidthPx > 0f &&
                                        down.position.x >= bounds.slotLeftPx &&
                                        down.position.x < bounds.slotLeftPx + bounds.slotWidthPx
                                }
                                pressedSlotIndex = if (slot >= 0) {
                                    slot
                                } else {
                                    (down.position.x / slotWidthPx).toInt()
                                        .coerceIn(0, destinations.lastIndex)
                                }
                                isPressing = true
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    isPressing = false
                                    if (isDrag) {
                                        change.consume()
                                        val shift = (change.position.x - down.position.x) / slotWidthPx
                                        val targetIndex = (basePos + shift).roundToInt().coerceIn(0, destinations.lastIndex)
                                        val targetDest = destinations[targetIndex]
                                        val releasePosition = dragPosition
                                        val releasedFrom = latestPagerPosition
                                        coroutineScope.launch {
                                            indicatorPosition.snapTo(releasePosition)
                                            isDragging = false
                                            if (targetIndex != latestSelectedIndex) {
                                                pendingPagerSyncFrom = releasedFrom
                                                pendingPagerSyncTarget = if (releasedFrom == null) -1 else targetIndex
                                                latestOnDestinationSelected(targetDest)
                                            } else {
                                                indicatorPosition.animateTo(
                                                    targetValue = targetIndex.toFloat(),
                                                    animationSpec = tween(
                                                        durationMillis = 220,
                                                        easing = NAV_EASE_OUT_CUBIC,
                                                    ),
                                                )
                                            }
                                        }
                                        if (targetIndex != lastHaptic) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    break
                                }

                                val dx = change.position.x - down.position.x
                                if (!isDrag && kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                                    isDrag = true
                                    dragPosition = basePos
                                    isDragging = true
                                }

                                if (isDrag) {
                                    change.consume()
                                    val newPosition = (basePos + (dx / slotWidthPx))
                                        .coerceIn(0f, destinations.lastIndex.toFloat())
                                    dragPosition = newPosition
                                    val currentTab = newPosition.roundToInt().coerceIn(0, destinations.lastIndex)
                                    pressedSlotIndex = currentTab
                                    if (currentTab != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastHaptic = currentTab
                                    }
                                }
                            }
                        } finally {
                            isPressing = false
                        }
                    }
                },
        ) {
            // Glass capsule. The press scale touches only the glass, never the icons.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = pressProgress.value
                        scaleX = 1f + 0.025f * p
                        scaleY = 1f + 0.06f * p
                    }
                    .then(
                        if (backdrop != null && isRenderEffectSupported()) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { barShape },
                                effects = {
                                    // Mornye's clear glass: clarity 0.75 -> sigma 6,
                                    // refractionDepth 0.45, dispersion 0.99.
                                    blur(with(density) { (6.dp * 0.75f).toPx() })
                                    if (isRuntimeShaderSupported()) {
                                        lens(
                                            refractionHeight = with(density) { (20.dp * 0.75f).toPx() },
                                            refractionAmount = with(density) {
                                                (20.dp * 0.45f * 0.75f).toPx()
                                            },
                                            magnification = 1.17f,
                                            depthEffect = true,
                                            chromaticAberration = true,
                                        )
                                    }
                                },
                                highlight = {
                                    Highlight(
                                        width = 0.9.dp,
                                        style = HighlightStyle.Default(
                                            color = Color.White.copy(alpha = if (colors.isLight) 0.12f else 0.25f),
                                            angle = 0f,
                                        ),
                                    )
                                },
                                shadow = null,
                                onDrawSurface = {
                                    // navigationOpacity 0.49 light / 0.36 dark, compounded
                                    // the same way as MornyeGlass.build.
                                    val veilColor = if (colors.isLight) {
                                        Color(0xFFF7F6FA).copy(alpha = 0.53f)
                                    } else {
                                        Color(0xFF13121A).copy(alpha = 0.54f)
                                    }
                                    drawRect(color = veilColor)
                                },
                                backdropScale = 0.75f,
                            )
                        } else if (colors.isLight) {
                            Modifier.background(Color(0xFFF7F6FA).copy(alpha = 0.70f), barShape)
                        } else {
                            Modifier.background(Color(0xFF13121A).copy(alpha = 0.68f), barShape)
                        }
                    )
                    .border(
                        width = 1.dp,
                        brush = capsuleBorderBrush,
                        shape = barShape,
                    ),
            )

            // Resting selection capsule: neutral translucent fill, hairline outline
            // and 1dp shadow, fading into the held lens on press. Drawn in the draw
            // phase so slot changes and pager motion never recompose the bar.
            if (measuredBoundsReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val bounds = calculateNavIndicatorBounds(
                                position = positionProvider(),
                                items = itemBounds,
                                slotInsetPx = insetPx,
                            ) ?: return@drawBehind
                            val restingOpacity = (1f - pressProgress.value).coerceIn(0f, 1f)
                            if (restingOpacity <= 0f) return@drawBehind
                            val pillTop = (size.height - restingHeightPx) / 2f
                            val pillSize = Size(bounds.widthPx, restingHeightPx)
                            val pillCorner = CornerRadius(pillCornerRadiusPx)
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.08f * restingOpacity),
                                topLeft = Offset(bounds.leftPx, pillTop + 0.5.dp.toPx()),
                                size = pillSize,
                                cornerRadius = pillCorner,
                            )
                            drawRoundRect(
                                color = pillColor.copy(alpha = pillFillAlpha * restingOpacity),
                                topLeft = Offset(bounds.leftPx, pillTop),
                                size = pillSize,
                                cornerRadius = pillCorner,
                            )
                            drawRoundRect(
                                color = pillColor.copy(alpha = 0.05f * restingOpacity),
                                topLeft = Offset(bounds.leftPx, pillTop),
                                size = pillSize,
                                cornerRadius = pillCorner,
                                style = Stroke(width = 0.5.dp.toPx()),
                            )
                        },
                )
            }


            // Tab items row
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEachIndexed { index, item ->
                    val onItemClick = remember(item, onDestinationSelected) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDestinationSelected(item)
                        }
                    }
                    VrkaNavItem(
                        item = item,
                        index = index,
                        lastIndex = destinations.lastIndex,
                        positionProvider = positionProvider,
                        isLight = colors.isLight,
                        reduceMotion = reduceMotion,
                        onClick = onItemClick,
                        onContentMeasured = { contentLeft, contentWidth ->
                            val measured = itemBounds[index].copy(
                                contentLeftInSlotPx = contentLeft,
                                contentWidthPx = contentWidth,
                            )
                            if (itemBounds[index] != measured) {
                                itemBounds = itemBounds.toMutableList().also { it[index] = measured }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                val measured = itemBounds[index].copy(
                                    slotLeftPx = coordinates.positionInParent().x,
                                    slotWidthPx = coordinates.size.width.toFloat(),
                                )
                                if (itemBounds[index] != measured) {
                                    itemBounds = itemBounds.toMutableList().also { it[index] = measured }
                                }
                            },
                    )
                }
            }
        }

        // Held lens (MornyeSelectionPill): a capsule grown 28% x 24% over 240ms
        // that samples the recorded bar and its icons. No blur, 0.18 refraction
        // depth across 18dp, white wash and a 0.75dp illuminated outline. It is
        // its own composable so press/drag frames recompose only the lens.
        if (pressLensEnabled && lensVisible && measuredBoundsReady) {
            NavPressLens(
                barBackdrop = barBackdrop,
                pressProgress = pressProgress,
                itemBounds = itemBounds,
                anchorProvider = remember {
                    { if (isDragging) dragPosition else pressedSlotIndex.toFloat() }
                },
                insetPx = insetPx,
                containerHeightPx = capsuleHeightPx,
                restingHeightPx = restingHeightPx,
                barShape = barShape,
                isLight = colors.isLight,
                density = density,
            )
        }
    }
}

@Composable
private fun NavPressLens(
    barBackdrop: Backdrop,
    pressProgress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    itemBounds: List<NavItemMeasuredBounds>,
    anchorProvider: () -> Float,
    insetPx: Float,
    containerHeightPx: Float,
    restingHeightPx: Float,
    barShape: RoundedCornerShape,
    isLight: Boolean,
    density: Density,
) {
    val pressValue = pressProgress.value
    val indicator = calculateNavIndicatorBounds(
        position = anchorProvider(),
        items = itemBounds,
        slotInsetPx = insetPx,
    ) ?: return
    val pressLensBounds = calculateNavPressLensBounds(
        indicator = indicator,
        pressProgress = pressValue,
        containerHeightPx = containerHeightPx,
        restingHeightPx = restingHeightPx,
    )
    val lensShape = RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(barShape),
    ) {
        Box(
            modifier = Modifier
                .width(with(density) { pressLensBounds.widthPx.toDp() })
                .height(with(density) { pressLensBounds.heightPx.toDp() })
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset {
                    IntOffset(pressLensBounds.leftPx.roundToInt(), pressLensBounds.topPx.roundToInt())
                }
                .drawBackdrop(
                    backdrop = barBackdrop,
                    shape = { lensShape },
                    effects = {
                        if (isRuntimeShaderSupported()) {
                            lens(
                                refractionHeight = with(density) { 18.dp.toPx() },
                                refractionAmount = with(density) {
                                    (18.dp * 0.18f * pressValue).toPx()
                                },
                                magnification = 1.018f,
                                depthEffect = true,
                                chromaticAberration = true,
                            )
                        }
                    },
                    highlight = {
                        Highlight(
                            width = 1.2.dp,
                            style = HighlightStyle.Default(
                                color = Color.White.copy(
                                    alpha = (if (isLight) 0.12f else 0.25f) * pressValue,
                                ),
                                angle = 0f,
                            ),
                        )
                    },
                    shadow = null,
                    onDrawSurface = {
                        drawRect(
                            color = Color.White.copy(
                                alpha = pressValue * (if (isLight) 0.24f else 0.08f),
                            ),
                        )
                    },
                    backdropScale = 1f,
                )
                .clip(lensShape)
                .border(
                    width = 0.75.dp,
                    color = Color.White.copy(alpha = 0.6f * pressValue),
                    shape = lensShape,
                ),
        )
    }
}

@Composable
private fun VrkaNavItem(
    item: VrkaDestination,
    index: Int,
    lastIndex: Int,
    positionProvider: () -> Float,
    isLight: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    onContentMeasured: (leftInSlotPx: Float, widthPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the selected/unselected flip is observed in composition; the continuous
    // position feeds a graphics layer, so drag frames never recompose the item.
    val isSelected by remember(index, lastIndex) {
        derivedStateOf { navSelectedIndex(positionProvider(), lastIndex) == index }
    }
    val activeColor = if (isLight) NAV_ACTIVE_LIGHT else NAV_ACTIVE_DARK
    val inactiveColor = if (isLight) NAV_UNSELECTED_LIGHT else NAV_UNSELECTED_DARK
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else inactiveColor,
        animationSpec = tween(120),
        label = "navItemColor",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) VRKA_PURPLE else inactiveColor,
        animationSpec = tween(120),
        label = "navIconTint",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    onContentMeasured(coordinates.positionInParent().x, coordinates.size.width.toFloat())
                }
                // Depth cue only: the active item lifts a hair as the pill arrives.
                // Scaling is deliberately avoided so the icon artwork never changes
                // size during selection or the held-lens animation.
                .graphicsLayer {
                    translationY = if (reduceMotion) {
                        0f
                    } else {
                        -navItemFocus(positionProvider(), index) * 1.5.dp.toPx()
                    }
                }
                .padding(horizontal = NAV_ITEM_HORIZONTAL_CONTENT_PADDING),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(item.iconRes),
                contentDescription = item.label,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = VrkaMonoFamily,
                    fontSize = NAV_LABEL_FONT_SIZE,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = NAV_LABEL_LINE_HEIGHT,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false,
                    ),
                ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal data class GlowProgressGeometry(
    val fillWidthPx: Float,
    val headCenterPx: Float,
    val glowAlpha: Float,
    val headVisible: Boolean,
)

/**
 * Pure geometry for the download glow bar. Zero progress shows only the track, and the
 * completed state keeps the head flush with the end of the fill. Nothing here pulses or
 * travels: the glow is bound to the real progress position.
 */
internal fun glowProgressGeometry(
    progress: Float,
    trackWidthPx: Float,
    reduceMotion: Boolean,
): GlowProgressGeometry {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val safeWidth = if (trackWidthPx.isFinite()) trackWidthPx.coerceAtLeast(0f) else 0f
    val fill = safeWidth * safeProgress
    return GlowProgressGeometry(
        fillWidthPx = fill,
        headCenterPx = fill,
        glowAlpha = if (fill > 0f) 0.55f else 0f,
        headVisible = fill > 0f,
    )
}

/**
 * Paints are created once and only re-shaded when the canvas size changes, so progress
 * frames allocate nothing. The leading-edge bloom and trailing glow are static functions
 * of the current progress, matching the reference recording: a continuous track, a solid
 * purple fill, a near-white transfer head, and a restrained horizontal glow behind it.
 */
private class GlowProgressPaints {
    val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val trail = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val headGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val headCore = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private var configuredWidth = -1f
    private var configuredHeight = -1f

    fun configure(
        width: Float,
        height: Float,
        trackColor: Color,
        accent: Color,
        accentLight: Color,
    ) {
        if (width == configuredWidth && height == configuredHeight) return
        configuredWidth = width
        configuredHeight = height
        track.color = trackColor.toArgb()
        fill.shader = android.graphics.LinearGradient(
            0f,
            0f,
            width,
            0f,
            accent.toArgb(),
            accentLight.toArgb(),
            android.graphics.Shader.TileMode.CLAMP,
        )
        // Trailing glow fades in over the last few track heights before the head.
        val trailStart = (width * 0.55f).coerceAtLeast(0f)
        trail.shader = android.graphics.LinearGradient(
            trailStart,
            0f,
            width,
            0f,
            intArrayOf(
                accentLight.copy(alpha = 0f).toArgb(),
                accentLight.copy(alpha = 0.42f).toArgb(),
            ),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        headGlow.shader = android.graphics.RadialGradient(
            0f,
            0f,
            height * 2.4f,
            intArrayOf(
                accentLight.copy(alpha = 0.7f).toArgb(),
                accentLight.copy(alpha = 0f).toArgb(),
            ),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        headCore.color = Color.White.copy(alpha = 0.95f).toArgb()
    }
}

/**
 * Purple download progress with a solid fill, a bright leading edge, and a soft glow that
 * hugs the fill. Reduced-motion users get the same shape with transitions snapped; the
 * effect never animates on its own.
 */
@Composable
internal fun VrkaGlowProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val reduceMotion = remember { !android.animation.ValueAnimator.areAnimatorsEnabled() }
    val trackColor = VrkaTokens.SurfaceInset
    val accent = VrkaTokens.Accent
    val accentLight = VrkaTokens.AccentLight
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 220),
        label = "glow progress",
    )
    val paints = remember(trackColor, accent, accentLight) { GlowProgressPaints() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        val trackHeight = size.height
        val trackWidth = size.width
        if (trackHeight <= 0f || trackWidth <= 0f) return@Canvas
        paints.configure(trackWidth, trackHeight, trackColor, accent, accentLight)
        val radius = trackHeight / 2f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawRoundRect(0f, 0f, trackWidth, trackHeight, radius, radius, paints.track)

            val geometry = glowProgressGeometry(animatedProgress, trackWidth, reduceMotion)
            if (geometry.fillWidthPx <= 0f) return@drawIntoCanvas
            val fillWidth = geometry.fillWidthPx

            native.save()
            // The trailing glow only exists inside the filled segment.
            native.clipRect(0f, 0f, fillWidth, trackHeight)
            native.drawRect(0f, 0f, trackWidth, trackHeight, paints.trail)
            native.restore()

            native.drawRoundRect(0f, 0f, fillWidth, trackHeight, radius, radius, paints.fill)

            val headCenterX = geometry.headCenterPx
            val midY = trackHeight / 2f
            // The radial shader is centered on the origin, so move the canvas to the head
            // instead of rebuilding the shader every frame.
            native.save()
            native.translate(headCenterX, midY)
            native.drawCircle(0f, 0f, trackHeight * 2.4f, paints.headGlow)
            native.restore()
            native.drawCircle(headCenterX, midY, trackHeight * 0.42f, paints.headCore)
        }
    }
}
