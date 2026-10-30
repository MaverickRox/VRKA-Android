package com.mvrk.vrka

import android.content.Context
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import com.mvrk.vrka.ui.backdrop.Backdrop
import com.mvrk.vrka.ui.backdrop.BackdropEffectScope
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
import androidx.compose.ui.unit.Constraints
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

internal const val NAV_PAGER_SYNC_EPSILON = 0.015f
internal const val NAV_PRESS_DURATION_MS = 240

/**
 * After a drag release the pager reports its old position for a frame or two before
 * the scroll animation starts. Such a sample is stale: it is still at the release
 * point while the pending target is elsewhere. Ignoring it keeps the pill from
 * snapping backwards; every later sample flows through untouched.
 */
internal fun navPagerSampleIsStale(
    position: Float?,
    waitingFrom: Float?,
    pendingTarget: Int,
): Boolean {
    if (position == null || waitingFrom == null || pendingTarget < 0) return false
    return kotlin.math.abs(position - waitingFrom) < NAV_PAGER_SYNC_EPSILON &&
        kotlin.math.abs(position - pendingTarget.toFloat()) >= NAV_PAGER_SYNC_EPSILON
}

/** Mornye contracts over the elapsed fraction of the hold, never a fixed duration. */
internal fun navPressReleaseDurationMs(progress: Float): Int =
    (NAV_PRESS_DURATION_MS * progress.coerceIn(0f, 1f)).roundToInt()
        .coerceIn(1, NAV_PRESS_DURATION_MS)

/** Mutable bookkeeping for the pager sync, deliberately outside snapshot state so
 *  writing it never recomposes the bar. Only the effect coroutine touches it. */
private class NavPendingPagerSync {
    var from: Float? = null
    var target: Int = -1

    fun clear() {
        from = null
        target = -1
    }
}


val NAV_ACTIVE_DARK = Color(0xFFF5F3FF)
val NAV_ACTIVE_LIGHT = Color(0xFF1E1B2E)
val NAV_UNSELECTED_DARK = Color(0xFF888496)
val NAV_UNSELECTED_LIGHT = Color(0xFF6B6778)

@Composable
fun VrkaFloatingNavBar(
    selectedDestination: VrkaDestination,
    onDestinationSelected: (VrkaDestination) -> Unit,
    modifier: Modifier = Modifier,
    // Pull-based source of the live pager coordinate. A lambda rather than a Float keeps
    // the caller from reading pager state in composition, so a swipe only invalidates
    // draw/layout and never recomposes the bar or its backdrop element.
    pagerPositionProvider: (() -> Float)? = null,
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
    val latestPagerPositionProvider by rememberUpdatedState(pagerPositionProvider)
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
    val pendingPagerSync = remember { NavPendingPagerSync() }
    var itemBounds by remember { mutableStateOf(List(destinations.size) { NavItemMeasuredBounds() }) }
    var pressedSlotIndex by remember { mutableIntStateOf(selectedIndex) }
    val pressProgress = remember { Animatable(0f) }

    // Press in/out run on the composition scope straight from the gesture instead of
    // a LaunchedEffect keyed on a press flag: that flag was snapshot state read in
    // composition, so every touch recomposed the whole bar twice for nothing.
    val pressLensIn: () -> Unit = remember(coroutineScope) {
        {
            coroutineScope.launch {
                pressProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = NAV_PRESS_DURATION_MS, easing = NAV_EASE_OUT_CUBIC),
                )
            }
        }
    }
    val pressLensOut: () -> Unit = remember(coroutineScope) {
        {
            coroutineScope.launch {
                // Mornye reverses with easeInCubic over the remaining fraction of the
                // hold animation, so releasing early shortens the contraction.
                pressProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = navPressReleaseDurationMs(pressProgress.value),
                        easing = NAV_EASE_IN_CUBIC,
                    ),
                )
            }
        }
    }

    LaunchedEffect(pressLensEnabled) {
        if (!pressLensEnabled) pressProgress.snapTo(0f)
    }

    LaunchedEffect(pagerPositionProvider, selectedIndex) {
        val provider = pagerPositionProvider
        if (provider == null) {
            indicatorPosition.animateTo(
                targetValue = selectedIndex.toFloat().coerceIn(0f, destinations.lastIndex.toFloat()),
                animationSpec = tween(durationMillis = 220, easing = NAV_EASE_OUT_CUBIC),
            )
            return@LaunchedEffect
        }
        // collected in a coroutine so pager motion is never a composition read
        snapshotFlow { provider() to isDragging }
            .distinctUntilChanged()
            .collect { (rawPosition, dragging) ->
                if (dragging) return@collect
                val target = rawPosition.coerceIn(0f, destinations.lastIndex.toFloat())
                if (navPagerSampleIsStale(target, pendingPagerSync.from, pendingPagerSync.target)) {
                    return@collect
                }
                pendingPagerSync.clear()
                indicatorPosition.snapTo(target)
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
        val isLight = colors.isLight

        // Everything below is remembered: the backdrop element compares its lambdas by
        // identity and treats a new one as a changed capture. A fresh lambda on every
        // recomposition (a tab switch, a theme toggle) made the glass re-sample and
        // re-run blur + lens even though nothing about it had changed.
        val ambientShadowDraw: DrawScope.() -> Unit = remember(ambientShadowPaint, barCornerRadiusPx) {
            {
                drawIntoCanvas { canvas ->
                    val lift = 2.dp.toPx()
                    canvas.nativeCanvas.drawRoundRect(
                        0f,
                        lift,
                        size.width,
                        size.height + lift,
                        barCornerRadiusPx,
                        barCornerRadiusPx,
                        ambientShadowPaint,
                    )
                }
            }
        }
        val capsuleScaleLayer: GraphicsLayerScope.() -> Unit = remember {
            {
                val p = pressProgress.value
                scaleX = 1f + 0.025f * p
                scaleY = 1f + 0.06f * p
            }
        }
        val capsuleAppearance: Modifier = remember(backdrop, barShape, isLight, density) {
            when {
                backdrop != null && isRenderEffectSupported() -> Modifier.drawBackdrop(
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
                                color = Color.White.copy(alpha = if (isLight) 0.12f else 0.25f),
                                angle = 0f,
                            ),
                        )
                    },
                    shadow = null,
                    onDrawSurface = {
                        // navigationOpacity 0.49 light / 0.36 dark, compounded
                        // the same way as MornyeGlass.build.
                        val veilColor = if (isLight) {
                            Color(0xFFF7F6FA).copy(alpha = 0.53f)
                        } else {
                            Color(0xFF13121A).copy(alpha = 0.54f)
                        }
                        drawRect(color = veilColor)
                    },
                    backdropScale = 0.75f,
                )
                isLight -> Modifier.background(Color(0xFFF7F6FA).copy(alpha = 0.70f), barShape)
                else -> Modifier.background(Color(0xFF13121A).copy(alpha = 0.68f), barShape)
            }
        }

        // Outer soft ambient shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind(ambientShadowDraw),
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
                                pressLensIn()
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (pressLensEnabled) pressLensOut()
                                    if (isDrag) {
                                        change.consume()
                                        val shift = (change.position.x - down.position.x) / slotWidthPx
                                        val targetIndex = (basePos + shift).roundToInt().coerceIn(0, destinations.lastIndex)
                                        val targetDest = destinations[targetIndex]
                                        val releasePosition = dragPosition
                                        val releasedFrom = latestPagerPositionProvider?.invoke()
                                        if (targetIndex != latestSelectedIndex) {
                                            // Recorded before isDragging flips, so the pager
                                            // sync sees the pending target on its first sample.
                                            pendingPagerSync.from = releasedFrom
                                            pendingPagerSync.target = if (releasedFrom == null) -1 else targetIndex
                                        }
                                        coroutineScope.launch {
                                            indicatorPosition.snapTo(releasePosition)
                                            isDragging = false
                                            if (targetIndex != latestSelectedIndex) {
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
                            if (pressLensEnabled) pressLensOut()
                        }
                    }
                },
        ) {
            // Glass capsule. The press scale touches only the glass, never the icons.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(capsuleScaleLayer)
                    .then(capsuleAppearance)
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
        // depth across 18dp, white wash and a 0.75dp illuminated outline. It reads
        // the press animation in the layout/draw phases, so a press recomposes the
        // bar only when the lens enters or leaves composition (0 -> >0 -> 0).
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
    val lensShape = remember { RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS) }
    // Every lambda below is stable for the lifetime of the lens and reads the press
    // value at draw time. A press used to recompose this composable on every frame,
    // which handed drawBackdrop a fresh effects lambda each time and forced it to
    // re-apply the effect chain and mark its capture dirty on all ~15 frames.
    val lensEffects: BackdropEffectScope.() -> Unit = remember(density) {
        {
            if (isRuntimeShaderSupported()) {
                lens(
                    refractionHeight = with(density) { 18.dp.toPx() },
                    refractionAmount = with(density) {
                        (18.dp * 0.18f * pressProgress.value).toPx()
                    },
                    magnification = 1.018f,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        }
    }
    val lensHighlight: () -> Highlight = remember(isLight) {
        {
            Highlight(
                width = 1.2.dp,
                style = HighlightStyle.Default(
                    color = Color.White.copy(
                        alpha = (if (isLight) 0.12f else 0.25f) * pressProgress.value,
                    ),
                    angle = 0f,
                ),
            )
        }
    }
    val lensSurface: DrawScope.() -> Unit = remember(isLight) {
        {
            drawRect(
                color = Color.White.copy(
                    alpha = pressProgress.value * (if (isLight) 0.24f else 0.08f),
                ),
            )
        }
    }
    val lensBorderWidthPx = with(density) { 0.75.dp.toPx() }
    val lensCornerRadiusPx = with(density) { NAV_INDICATOR_CORNER_RADIUS.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(barShape),
    ) {
        Box(
            modifier = Modifier
                // Bounds and placement are resolved in the layout phase, so the growth
                // animation invalidates layout and draw only.
                .layout { measurable, _ ->
                    val lens = calculateNavIndicatorBounds(
                        position = anchorProvider(),
                        items = itemBounds,
                        slotInsetPx = insetPx,
                    )?.let { indicator ->
                        calculateNavPressLensBounds(
                            indicator = indicator,
                            pressProgress = pressProgress.value,
                            containerHeightPx = containerHeightPx,
                            restingHeightPx = restingHeightPx,
                        )
                    }
                    val width = lens?.widthPx?.roundToInt()?.coerceAtLeast(1) ?: 1
                    val height = lens?.heightPx?.roundToInt()?.coerceAtLeast(1) ?: 1
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) {
                        placeable.place(
                            lens?.leftPx?.roundToInt() ?: 0,
                            lens?.topPx?.roundToInt() ?: 0,
                        )
                    }
                }
                .drawBackdrop(
                    backdrop = barBackdrop,
                    shape = { lensShape },
                    effects = lensEffects,
                    highlight = lensHighlight,
                    shadow = null,
                    onDrawSurface = lensSurface,
                    backdropScale = 1f,
                )
                .clip(lensShape)
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.6f * pressProgress.value),
                        cornerRadius = CornerRadius(lensCornerRadiusPx),
                        style = Stroke(width = lensBorderWidthPx),
                    )
                },
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
    val headWidthPx: Float,
    val trailLengthPx: Float,
    val glowHalfWidthPx: Float,
    val bloomRadiusPx: Float,
    val headVisible: Boolean,
)

/** The bar is three times as tall as the track so the glow has room to bleed. */
internal const val GLOW_CANVAS_HEIGHT_RATIO = 3f
internal const val GLOW_HEAD_WIDTH_RATIO = 0.55f
internal const val GLOW_TRAIL_LENGTH_RATIO = 5f
internal const val GLOW_HALF_WIDTH_RATIO = 4f
internal const val GLOW_HALF_HEIGHT_RATIO = 1.15f
internal const val GLOW_BLOOM_RADIUS_RATIO = 1.25f
internal const val GLOW_HALO_ALPHA = 0.34f
internal const val GLOW_TRAIL_ALPHA = 0.72f
internal const val GLOW_BLOOM_ALPHA = 0.30f

/**
 * Pure geometry for the download progress bar. The fill and the light head are functions
 * of the real progress only: there is no pulse, no travelling icon and no idle animation.
 * Zero progress draws just the track, and small values clamp the head and trail to the
 * filled segment so no light escapes ahead of the actual progress.
 */
internal fun glowProgressGeometry(
    progress: Float,
    trackWidthPx: Float,
    trackHeightPx: Float,
): GlowProgressGeometry {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val safeWidth = if (trackWidthPx.isFinite()) trackWidthPx.coerceAtLeast(0f) else 0f
    val safeHeight = if (trackHeightPx.isFinite()) trackHeightPx.coerceAtLeast(0f) else 0f
    val fill = safeWidth * safeProgress
    return GlowProgressGeometry(
        fillWidthPx = fill,
        headCenterPx = fill,
        headWidthPx = (safeHeight * GLOW_HEAD_WIDTH_RATIO).coerceAtMost(fill),
        trailLengthPx = (safeHeight * GLOW_TRAIL_LENGTH_RATIO).coerceAtMost(fill),
        glowHalfWidthPx = safeHeight * GLOW_HALF_WIDTH_RATIO,
        bloomRadiusPx = safeHeight * GLOW_BLOOM_RADIUS_RATIO,
        headVisible = fill > 0f,
    )
}

/**
 * Paints, shaders and the clip path are allocated once and only re-configured when the
 * track size or colours change. Per frame the work is a handful of draw calls plus a
 * matrix translation for the glow; nothing is blurred, layered or re-allocated, so the
 * effect stays cheap while following the real progress position.
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
    val glowHalo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val bloom = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val head = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = WHITE_HEAD
    }
    private val glowMatrix = android.graphics.Matrix()
    private val trailMatrix = android.graphics.Matrix()
    private var configuredWidth = -1f
    private var configuredHeight = -1f
    private var configuredTrack = 0
    private var configuredAccent = 0

    fun configure(width: Float, trackHeight: Float, trackColor: Color, accent: Color) {
        val trackArgb = trackColor.toArgb()
        val accentArgb = accent.toArgb()
        if (width == configuredWidth && trackHeight == configuredHeight &&
            trackArgb == configuredTrack && accentArgb == configuredAccent
        ) {
            return
        }
        configuredWidth = width
        configuredHeight = trackHeight
        configuredTrack = trackArgb
        configuredAccent = accentArgb
        track.color = trackArgb
        fill.color = accentArgb
        // Unit-circular gradients that the per-frame matrix stretches into an ellipse,
        // so the light falls off in both axes instead of ending in a hard-edged band.
        glowHalo.shader = android.graphics.RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(
                Color.White.copy(alpha = GLOW_HALO_ALPHA).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        bloom.shader = android.graphics.RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(
                Color.White.copy(alpha = GLOW_BLOOM_ALPHA).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        // Unit gradient scaled to the live trail length each frame, so the fade always
        // starts exactly at the trail's tail instead of being clipped mid-ramp.
        trail.shader = android.graphics.LinearGradient(
            0f,
            0f,
            1f,
            0f,
            intArrayOf(
                Color.White.copy(alpha = 0f).toArgb(),
                Color.White.copy(alpha = GLOW_TRAIL_ALPHA).toArgb(),
            ),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

    fun prepareHalo(headX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        glowMatrix.setScale(halfWidth.coerceAtLeast(1f), halfHeight.coerceAtLeast(1f))
        glowMatrix.postTranslate(headX, centerY)
        glowHalo.shader.setLocalMatrix(glowMatrix)
    }

    fun prepareBloom(headX: Float, centerY: Float, radius: Float) {
        glowMatrix.setScale(radius.coerceAtLeast(1f), radius.coerceAtLeast(1f))
        glowMatrix.postTranslate(headX, centerY)
        bloom.shader.setLocalMatrix(glowMatrix)
    }

    fun prepareTrail(headX: Float, trailLength: Float) {
        val safeLength = trailLength.coerceAtLeast(1f)
        trailMatrix.setScale(safeLength, 1f)
        trailMatrix.postTranslate(headX - safeLength, 0f)
        trail.shader.setLocalMatrix(trailMatrix)
    }

    private companion object {
        val WHITE_HEAD = Color.White.copy(alpha = 0.93f).toArgb()
    }
}

/**
 * The single in-app download progress bar: a continuous track, a solid VRKA-purple fill,
 * a near-white leading edge, a short luminous trail and a restrained horizontal glow.
 * Both the active-download strip and the queue card use this composable, so the position,
 * percentage and byte counters always come from the same job state. Updates are smoothed
 * toward the reported progress; when the job stops reporting, the bar is static.
 */
@Composable
internal fun VrkaGlowProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
) {
    val reduceMotion = remember { !android.animation.ValueAnimator.areAnimatorsEnabled() }
    val colors = LocalVrkaColors.current
    val trackColor = if (colors.isLight) {
        Color.Black.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    val accent = VrkaTokens.Accent
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        },
        label = "glow progress",
    )
    val paints = remember { GlowProgressPaints() }
    val fillPath = remember { android.graphics.Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight * GLOW_CANVAS_HEIGHT_RATIO)
            // Correct clipping: the track stays a capsule, while the glow is allowed to
            // bleed vertically inside this component's own bounds and nowhere else.
            .clipToBounds(),
    ) {
        val trackWidth = size.width
        val track = size.height / GLOW_CANVAS_HEIGHT_RATIO
        if (trackWidth <= 0f || track <= 0f) return@Canvas
        val centerY = size.height / 2f
        val top = centerY - track / 2f
        val bottom = centerY + track / 2f
        val radius = track / 2f

        paints.configure(trackWidth, track, trackColor, accent)
        val geometry = glowProgressGeometry(animatedProgress, trackWidth, track)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawRoundRect(0f, top, trackWidth, bottom, radius, radius, paints.track)
            if (!geometry.headVisible) return@drawIntoCanvas

            val headX = geometry.headCenterPx
            native.drawRoundRect(0f, top, headX, bottom, radius, radius, paints.fill)

            // Trail: clipped to the filled capsule so it can never spill onto the track.
            fillPath.rewind()
            fillPath.addRoundRect(0f, top, headX, bottom, radius, radius, android.graphics.Path.Direction.CW)
            native.save()
            native.clipPath(fillPath)
            paints.prepareTrail(headX, geometry.trailLengthPx)
            native.drawRect(headX - geometry.trailLengthPx, top, headX, bottom, paints.trail)
            native.restore()

            // Soft horizontal halo stretched around the head, then a smaller round bloom.
            // Both are gradient shaders with a reused matrix, not blur filters, so they
            // cost one draw call each and blur nothing.
            val haloHalf = geometry.glowHalfWidthPx
            val haloHeight = track * GLOW_HALF_HEIGHT_RATIO
            paints.prepareHalo(headX, centerY, haloHalf, haloHeight)
            native.drawRect(headX - haloHalf, centerY - haloHeight, headX + haloHalf, centerY + haloHeight, paints.glowHalo)
            paints.prepareBloom(headX, centerY, geometry.bloomRadiusPx)
            native.drawRect(
                headX - geometry.bloomRadiusPx,
                centerY - geometry.bloomRadiusPx,
                headX + geometry.bloomRadiusPx,
                centerY + geometry.bloomRadiusPx,
                paints.bloom,
            )

            val headLeft = (headX - geometry.headWidthPx).coerceAtLeast(0f)
            native.drawRoundRect(headLeft, top, headX, bottom, radius, radius, paints.head)
        }
    }
}
