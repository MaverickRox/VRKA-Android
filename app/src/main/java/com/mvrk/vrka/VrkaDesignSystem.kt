package com.mvrk.vrka

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import com.mvrk.vrka.ui.backdrop.drawBackdrop
import com.mvrk.vrka.ui.backdrop.effects.blur
import com.mvrk.vrka.ui.backdrop.highlight.Highlight
import com.mvrk.vrka.ui.backdrop.isRenderEffectSupported
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

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
            softWrap = false,
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

// 64dp bar with 32dp radii, the pill inset 4dp all round to a 56dp capsule: BitChord's
// proportions, on the height this app's screens already leave room for.
val NAV_BAR_HEIGHT = 64.dp
val NAV_BAR_CORNER_RADIUS = 32.dp
val NAV_BAR_HORIZONTAL_MARGIN = 20.dp
val NAV_BAR_MAX_WIDTH = 600.dp

val NAV_TAB_INSET_HORIZONTAL = 4.dp
val NAV_TAB_INSET_VERTICAL = 4.dp
val NAV_INDICATOR_HEIGHT = NAV_BAR_HEIGHT - (NAV_TAB_INSET_VERTICAL * 2) // 56.dp
val NAV_INDICATOR_CORNER_RADIUS = NAV_INDICATOR_HEIGHT / 2 // 28.dp
// Keep the label inside the pill with real clearance, so label glyphs never touch its edge.
internal val NAV_ITEM_HORIZONTAL_CONTENT_PADDING = 8.dp
internal val NAV_LABEL_FONT_SIZE = 10.sp
internal val NAV_LABEL_LINE_HEIGHT = 12.sp

internal fun navSelectedIndex(position: Float, lastIndex: Int): Int =
    if (lastIndex < 0) 0 else position.roundToInt().coerceIn(0, lastIndex)

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

/** BitChord's tab spring: one soft pass beyond the mark and done. */
private val NavIconSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

/** How far the lifted pill's soft shadow reaches, and the rings it is drawn in. */
private val NAV_PILL_SHADOW_REACH = 12.dp
private const val NAV_PILL_SHADOW_RINGS = 6

@Composable
fun VrkaFloatingNavBar(
    selectedDestination: VrkaDestination,
    onDestinationSelected: (VrkaDestination) -> Unit,
    // Only read in effects and gestures, so a swipe invalidates draw and never
    // recomposes the bar or its backdrop element.
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val colors = LocalVrkaColors.current
    val backdrop = LocalAppBackdrop.current
    val destinations = remember { VrkaDestination.entries }
    val selectedIndex = destinations.indexOf(selectedDestination).coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Animator scale 0 is Android's reduced-motion switch: the pill jumps between
    // slots instead of lifting and travelling.
    val reduceMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
    val isLight = colors.isLight

    val motion = remember { NavPillMotion(coroutineScope, selectedIndex) }
    motion.reduceMotion = reduceMotion

    val ambientShadowPaint = remember(density) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(0x1A, 0x00, 0x00, 0x00)
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(
                with(density) { 24.dp.toPx() },
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    }

    var itemBounds by remember { mutableStateOf(List(destinations.size) { NavItemMeasuredBounds() }) }

    LaunchedEffect(pagerState) {
        launch {
            pagerState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) motion.pagerGrabbed()
            }
        }
        // collected in a coroutine so pager motion is never a composition read
        snapshotFlow {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction) to pagerState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { (position, scrolling) ->
                motion.followPager(
                    pagerPosition = position.coerceIn(0f, destinations.lastIndex.toFloat()),
                    pagerScrolling = scrolling,
                )
            }
    }

    Box(
        modifier = modifier
            .widthIn(max = NAV_BAR_MAX_WIDTH)
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .padding(horizontal = NAV_BAR_HORIZONTAL_MARGIN),
    ) {
        val slotWidthPx = itemBounds.firstOrNull()?.slotWidthPx ?: 0f
        motion.stepDp = slotWidthPx / density.density
        val measuredBoundsReady = itemBounds.size == destinations.size && itemBounds.all {
            it.slotWidthPx > 0f
        }
        val barCornerRadiusPx = with(density) { NAV_BAR_CORNER_RADIUS.toPx() }
        val barShape = RoundedCornerShape(NAV_BAR_CORNER_RADIUS)
        val positionProvider = remember(motion) { { motion.position } }

        // Everything below is remembered: the backdrop element compares its lambdas by
        // identity and treats a new one as a changed capture. A fresh lambda on every
        // recomposition (a tab switch, a theme toggle) made the glass re-sample and
        // re-run its blur even though nothing about it had changed.
        val ambientShadowDraw: DrawScope.() -> Unit = remember(ambientShadowPaint, barCornerRadiusPx) {
            {
                val outline = Path().apply {
                    addRoundRect(RoundRect(size.toRect(), CornerRadius(barCornerRadiusPx)))
                }
                // Outside the capsule only, so the shadow never greys the frost it sits under.
                clipPath(outline, clipOp = ClipOp.Difference) {
                    drawIntoCanvas { canvas ->
                        val lift = 4.dp.toPx()
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
        }
        val barSheen = remember(isLight) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (isLight) 0.22f else 0.07f),
                0.55f to Color.Transparent,
            )
        }
        val capsuleAppearance: Modifier = remember(backdrop, barShape, isLight, density, barSheen) {
            when {
                backdrop != null && isRenderEffectSupported() -> Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        // Frost only: a blur over the page, with no lens or refraction.
                        blur(with(density) { (6.dp * 0.75f).toPx() })
                    },
                    // Liquid glass's rim: a hairline lit from two opposite corners.
                    highlight = { Highlight.Default },
                    shadow = null,
                    onDrawSurface = {
                        val veilColor = if (isLight) {
                            Color(0xFFF7F6FA).copy(alpha = 0.53f)
                        } else {
                            Color(0xFF13121A).copy(alpha = 0.54f)
                        }
                        drawRect(color = veilColor)
                        drawRect(brush = barSheen)
                    },
                    backdropScale = 0.75f,
                )
                isLight -> Modifier.background(Color(0xFFF7F6FA).copy(alpha = 0.70f), barShape)
                else -> Modifier.background(Color(0xFF13121A).copy(alpha = 0.68f), barShape)
            }
        }
        val edgeColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.10f)

        // Outer soft ambient shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind(ambientShadowDraw),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slotWidthPx, destinations.size, isRtl) {
                    if (slotWidthPx <= 0f) return@pointerInput
                    // In tab order, whichever way the row runs.
                    val direction = if (isRtl) -1f else 1f
                    awaitEachGesture {
                        var holding = false
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var isDrag = false
                            val basePos = motion.position
                            var lastHaptic = basePos.roundToInt().coerceIn(0, destinations.lastIndex)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val shift = (change.position.x - down.position.x) * direction / slotWidthPx
                                if (!change.pressed) {
                                    if (isDrag) {
                                        change.consume()
                                        val targetIndex = (basePos + shift).roundToInt().coerceIn(0, destinations.lastIndex)
                                        holding = false
                                        motion.release(targetIndex)
                                        if (targetIndex != latestSelectedIndex) {
                                            latestOnDestinationSelected(destinations[targetIndex])
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
                                    holding = true
                                    motion.startDrag()
                                }

                                if (isDrag) {
                                    change.consume()
                                    val newPosition = (basePos + shift)
                                        .coerceIn(0f, destinations.lastIndex.toFloat())
                                    motion.dragTo(newPosition)
                                    val currentTab = newPosition.roundToInt().coerceIn(0, destinations.lastIndex)
                                    if (currentTab != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastHaptic = currentTab
                                    }
                                }
                            }
                        } finally {
                            if (holding) motion.release(latestSelectedIndex)
                        }
                    }
                },
        ) {
            // Frosted capsule.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(capsuleAppearance)
                    .border(
                        width = 0.5.dp,
                        color = edgeColor,
                        shape = barShape,
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = NAV_TAB_INSET_HORIZONTAL, vertical = NAV_TAB_INSET_VERTICAL),
            ) {
                // Selection pill, drawn under the tabs so its fill never greys the
                // glyphs. Unclipped: the lifted pill stands past the bar's edges.
                if (measuredBoundsReady) {
                    NavSelectionPill(
                        motion = motion,
                        itemBounds = itemBounds,
                        isLight = isLight,
                    )
                }

                // Tab items row
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    destinations.forEachIndexed { index, item ->
                        val onItemClick = remember(item) {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                motion.animateTo(index)
                                latestOnDestinationSelected(item)
                            }
                        }
                        VrkaNavItem(
                            item = item,
                            index = index,
                            lastIndex = destinations.lastIndex,
                            positionProvider = positionProvider,
                            isLight = isLight,
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
        }
    }
}

/**
 * The pill in its slot at rest and, in motion, lifted past the bar with liquid
 * glass's rim, sheen and shadow around it — and no glass. It is a few canvas calls
 * in the draw phase: nothing is sampled, and nothing is drawn offscreen, so a frame
 * of motion costs a redraw of this one box.
 */
@Composable
private fun NavSelectionPill(
    motion: NavPillMotion,
    itemBounds: List<NavItemMeasuredBounds>,
    isLight: Boolean,
) {
    // Gray on the light veil, where a white pill would vanish.
    val fill = if (isLight) Color(0xFF808080).copy(alpha = 0.14f) else Color.White.copy(alpha = 0.14f)
    val shadowColor = Color.Black
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val bounds = calculateNavIndicatorBounds(
                    position = motion.position,
                    items = itemBounds,
                    slotInsetPx = 0f,
                ) ?: return@drawBehind
                val restHeight = size.height
                // Stands NAV_PILL_GROW_HEIGHT taller than the whole bar, keeping the
                // slot's proportions as it grows.
                val liftedHeight = NAV_BAR_HEIGHT.toPx() + NAV_PILL_GROW_HEIGHT.toPx()
                val pill = motion.liveSize(
                    rest = Size(bounds.widthPx, restHeight),
                    lifted = Size(liftedHeight * bounds.widthPx / restHeight, liftedHeight),
                )
                val topLeft = Offset(
                    x = bounds.leftPx + (bounds.widthPx - pill.width) / 2f,
                    y = (restHeight - pill.height) / 2f,
                )
                val corner = CornerRadius(pill.minDimension / 2f)
                val rim = motion.rim

                if (rim > 0f) {
                    // A soft shadow from stepped rings rather than a blur, which would
                    // rasterise a new mask on the CPU for every size the pill passes through.
                    val ringWidth = NAV_PILL_SHADOW_REACH.toPx() / NAV_PILL_SHADOW_RINGS
                    repeat(NAV_PILL_SHADOW_RINGS) { ring ->
                        val falloff = 1f - ring / NAV_PILL_SHADOW_RINGS.toFloat()
                        val outset = ringWidth * (ring + 0.5f)
                        drawRoundRect(
                            color = shadowColor.copy(alpha = 0.14f * rim * falloff * falloff),
                            topLeft = topLeft - Offset(outset, outset),
                            size = Size(pill.width + outset * 2f, pill.height + outset * 2f),
                            cornerRadius = CornerRadius(corner.x + outset),
                            style = Stroke(width = ringWidth),
                        )
                    }
                }

                drawRoundRect(color = fill, topLeft = topLeft, size = pill, cornerRadius = corner)

                if (rim > 0f) {
                    val bottomRight = topLeft + Offset(pill.width, pill.height)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.12f * rim),
                            0.55f to Color.Transparent,
                            startY = topLeft.y,
                            endY = bottomRight.y,
                        ),
                        topLeft = topLeft,
                        size = pill,
                        cornerRadius = corner,
                    )
                    val rimLight = Color.White.copy(alpha = 0.5f * rim)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            0f to rimLight,
                            0.5f to rimLight.copy(alpha = 0.06f * rim),
                            1f to rimLight,
                            start = topLeft,
                            end = bottomRight,
                        ),
                        topLeft = topLeft,
                        size = pill,
                        cornerRadius = corner,
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                }
            },
    )
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
    // position stays in the draw phase, so drag frames never recompose the item.
    val isSelected by remember(index, lastIndex) {
        derivedStateOf { navSelectedIndex(positionProvider(), lastIndex) == index }
    }
    val activeColor = if (isLight) Color.Black else Color.White
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else activeColor.copy(alpha = 0.65f),
        animationSpec = tween(200),
        label = "navItemColor",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) VRKA_PURPLE else activeColor.copy(alpha = 0.65f),
        animationSpec = tween(200),
        label = "navIconTint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = if (reduceMotion) snap() else NavIconSpring,
        label = "navIconScale",
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
                .padding(horizontal = NAV_ITEM_HORIZONTAL_CONTENT_PADDING),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(item.iconRes),
                contentDescription = item.label,
                tint = iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = NAV_LABEL_FONT_SIZE,
                    letterSpacing = 0.5.sp,
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

/** The progress fill stays this purple in every theme, matching the ColorOS 17 reference. */
internal val GlowProgressPurple = Color(0xFF8140DC)

/**
 * Geometry of the trailing light. The streak is a long horizontal fade over the completed
 * fill; the bloom is a comet-tail cone that starts at the tip of the head and fans out as
 * it trails backwards. No light travels ahead of the head, so its rounded cap stays crisp.
 */
internal data class GlowProgressGeometry(
    val fillWidthPx: Float,
    /** Real progress edge. */
    val headCenterPx: Float,
    /** Full fade length of the in-fill streak, measured back from the edge. */
    val streakLengthPx: Float,
    /** Half-height of the cone's mask at full size. */
    val bloomHalfHeightPx: Float,
    val headVisible: Boolean,
)

/**
 * The bar keeps five track heights of layout, while its light needs fifteen to fade out, so
 * the cone is allowed to draw past the layout bounds and behind neighbouring text. The streak
 * spans eighteen track heights, as on the reference bar, and the cone twenty-eight.
 */
internal const val GLOW_LAYOUT_HEIGHT_RATIO = 5f
internal const val GLOW_CANVAS_HEIGHT_RATIO = 15f
internal const val GLOW_STREAK_LENGTH_RATIO = 18f
internal const val GLOW_BLOOM_LENGTH_RATIO = 28f
internal const val GLOW_BLOOM_HALF_HEIGHT_RATIO = 7.3f
internal const val GLOW_PROGRESS_SLIDE_MILLIS = 400
internal const val GLOW_PROGRESS_SETTLE_MILLIS = 500
internal const val GLOW_STREAK_ALPHA = 0.97f

/**
 * Pure geometry for the download progress bar. All light is a function of the real progress
 * only: there is no pulse, no travelling icon and no idle animation. Lengths are clamped to
 * the fill so a short bar cannot emit light over the unfilled track.
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
    val visible = fill > 0f
    fun length(ratio: Float): Float =
        if (visible) (safeHeight * ratio).coerceAtMost(fill) else 0f
    fun halfHeight(ratio: Float): Float =
        if (visible) safeHeight * ratio else 0f
    return GlowProgressGeometry(
        fillWidthPx = fill,
        headCenterPx = fill,
        streakLengthPx = length(GLOW_STREAK_LENGTH_RATIO),
        bloomHalfHeightPx = halfHeight(GLOW_BLOOM_HALF_HEIGHT_RATIO),
        headVisible = visible,
    )
}

/**
 * Analytic mask for the rocket-trail cone: the head is at the right edge and the tail at the
 * left. The curves are fitted to brightness measured around the head of the reference bar, in
 * track heights: the cone's edge grows with the square root of the distance back, brightness
 * fades along that distance, and the falloff across the cone softens from a tight core next
 * to the head to a nearly linear diffusion in the tail. Light is zero at the cone's edge and
 * tail, so scaling the mask never exposes a contour. Colour is white with a little VRKA purple
 * in it, deepening towards the tail; alpha divides out each colour's brightness, so the tint
 * does not change how bright the light is. It is generated once, never per frame, and drawn
 * through a cached [android.graphics.BitmapShader].
 */
private const val BLOOM_MASK_WIDTH = 512
private const val BLOOM_MASK_HEIGHT = 128
private const val BLOOM_MASK_CENTER_Y = 64f
// How much the cone's height is left by the time a finished bar's glow has faded out.
private const val BLOOM_SETTLED_HEIGHT = 0.4f
private const val BLOOM_MASK_PX_PER_TRACK = BLOOM_MASK_CENTER_Y / GLOW_BLOOM_HALF_HEIGHT_RATIO
private const val BLOOM_PEAK_BRIGHTNESS = 300f / 255f
// The cone reaches full size once the fill covers this share of its length. Its faint tail
// may pass the bar's start, as on the reference, but has faded out before the card's edge.
private const val BLOOM_FULL_SIZE_FILL = 0.7f
private const val STREAK_LIGHT_SURFACE_ALPHA = 0.6f
private val BloomHeadColor = Color(0xFFEAD9FF)
private val BloomTailColor = Color(0xFFB084F2)

private fun buildBloomMask(): android.graphics.Bitmap {
    val pixels = IntArray(BLOOM_MASK_WIDTH * BLOOM_MASK_HEIGHT)
    for (x in 0 until BLOOM_MASK_WIDTH) {
        val along = 1f - x / (BLOOM_MASK_WIDTH - 1f)
        val back = along * GLOW_BLOOM_LENGTH_RATIO
        val spread = 1.25f * back.pow(0.52f)
        val brightness = BLOOM_PEAK_BRIGHTNESS * (1f - along).pow(2.4f)
        val falloff = (2.2f - 0.11f * back).coerceAtLeast(1f)
        if (spread <= 0f || brightness <= 0f) continue
        val color = androidx.compose.ui.graphics.lerp(BloomHeadColor, BloomTailColor, along)
        val colorBrightness = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        val rgb = color.toArgb() and 0x00FFFFFF
        for (y in 0 until BLOOM_MASK_HEIGHT) {
            val up = kotlin.math.abs(y - BLOOM_MASK_CENTER_Y) / BLOOM_MASK_PX_PER_TRACK
            val v = up / spread
            if (v >= 1f) continue
            val light = brightness * (1f - v).pow(falloff) / colorBrightness
            val alpha = (light * 255f).toInt().coerceIn(0, 255)
            pixels[y * BLOOM_MASK_WIDTH + x] = (alpha shl 24) or rgb
        }
    }
    return android.graphics.Bitmap.createBitmap(
        pixels,
        BLOOM_MASK_WIDTH,
        BLOOM_MASK_HEIGHT,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
}

private object GlowBloomMask {
    val bitmap: android.graphics.Bitmap by lazy { buildBloomMask() }
    val shader: android.graphics.BitmapShader by lazy {
        android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
}

/**
 * Paints, matrices and the bloom mask are allocated once per bar. A frame is the track, the
 * fill, one cone draw and one rounded streak. There is no clip, blur,
 * offscreen layer or per-frame bitmap; only the cached shader matrices follow the edge.
 */
private class GlowProgressPaints {
    val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val streak = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val bloom = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        shader = GlowBloomMask.shader
        isFilterBitmap = true
        isDither = true
    }
    private val additive = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    private val matrix = android.graphics.Matrix()
    private var configuredTrack = 0
    private var configuredAccent = 0
    private var configuredLightSurface = false

    fun configure(trackColor: Color, accent: Color, lightSurface: Boolean) {
        // Additive light would saturate to white on a light surface and lose the purple.
        bloom.xfermode = if (lightSurface) null else additive
        val trackArgb = trackColor.toArgb()
        val accentArgb = accent.toArgb()
        if (
            trackArgb == configuredTrack &&
            accentArgb == configuredAccent &&
            lightSurface == configuredLightSurface
        ) {
            return
        }
        configuredTrack = trackArgb
        configuredAccent = accentArgb
        configuredLightSurface = lightSurface
        track.color = trackArgb
        fill.color = accentArgb
        // A long white ramp over the purple, fitted to the reference bar's near-even blend:
        // transparent at the tail, solid white at the head. The rounded rect supplies the same cap as the fill, so no clip is needed.
        // A white head would vanish on a light surface, so it stays lavender there.
        val peak = if (lightSurface) STREAK_LIGHT_SURFACE_ALPHA else GLOW_STREAK_ALPHA
        streak.shader = android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(
                Color.White.copy(alpha = 0f).toArgb(),
                Color.White.copy(alpha = peak * 0.11f).toArgb(),
                Color.White.copy(alpha = peak * 0.41f).toArgb(),
                Color.White.copy(alpha = peak * 0.74f).toArgb(),
                Color.White.copy(alpha = peak * 0.88f).toArgb(),
                Color.White.copy(alpha = peak * 0.96f).toArgb(),
                Color.White.copy(alpha = peak).toArgb(),
            ),
            floatArrayOf(0f, 0.22f, 0.44f, 0.67f, 0.78f, 0.89f, 0.95f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

    fun drawBloom(
        canvas: android.graphics.Canvas,
        headX: Float,
        centerY: Float,
        trackHeight: Float,
        settled: Float,
    ) {
        val fullLength = trackHeight * GLOW_BLOOM_LENGTH_RATIO
        if (headX <= 0f || fullLength <= 0f) return
        // A short fill shrinks the whole comet rather than squeezing it into a spike.
        val scale = (headX / (fullLength * BLOOM_FULL_SIZE_FILL)).coerceAtMost(1f)
        val length = fullLength * scale
        val narrowing = 1f - (1f - BLOOM_SETTLED_HEIGHT) * settled
        val halfHeight = trackHeight * GLOW_BLOOM_HALF_HEIGHT_RATIO * scale * narrowing
        val tailX = headX - length
        matrix.setScale(
            length / BLOOM_MASK_WIDTH,
            (halfHeight * 2f) / BLOOM_MASK_HEIGHT,
        )
        matrix.postTranslate(tailX, centerY - halfHeight)
        GlowBloomMask.shader.setLocalMatrix(matrix)
        bloom.alpha = ((1f - settled) * 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(
            tailX,
            centerY - halfHeight,
            headX,
            centerY + halfHeight,
            bloom,
        )
    }

    fun drawStreak(
        canvas: android.graphics.Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        radius: Float,
        settled: Float,
    ) {
        val length = right - left
        if (length <= 0f) return
        matrix.setScale(length, 1f)
        matrix.postTranslate(left, 0f)
        streak.shader.setLocalMatrix(matrix)
        streak.alpha = ((1f - settled) * 255f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, streak)
    }
}

/**
 * Draws the track, fill, streak and comet-tail cone into any canvas. The Compose composable
 * and the preview harness share this renderer, so the previews exercise exactly the same
 * geometry and shaders as the app. [showStreak] and [showDispersion] expose the independent
 * layers for QA.
 */
internal class GlowProgressRenderer {
    private val paints = GlowProgressPaints()

    fun draw(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        trackHeight: Float,
        progress: Float,
        trackColor: Color,
        accent: Color,
        lightSurface: Boolean = false,
        // 0 while running; 1 once a finished bar has settled into solid purple with no glow.
        settled: Float = 0f,
        showStreak: Boolean = true,
        showDispersion: Boolean = true,
    ) {
        if (width <= 0f || height <= 0f || trackHeight <= 0f) return
        paints.configure(trackColor, accent, lightSurface)
        val centerY = height / 2f
        val top = centerY - trackHeight / 2f
        val bottom = centerY + trackHeight / 2f
        val radius = trackHeight / 2f
        val geometry = glowProgressGeometry(progress, width, trackHeight)
        val headX = geometry.headCenterPx
        val settledAmount = settled.coerceIn(0f, 1f)

        // The cone sits under the bar: added over the fill it would push the purple to pink.
        if (showDispersion && geometry.headVisible && settledAmount < 1f) {
            paints.drawBloom(canvas, headX, centerY, trackHeight, settledAmount)
        }

        canvas.drawRoundRect(0f, top, width, bottom, radius, radius, paints.track)
        if (!geometry.headVisible) return
        canvas.drawRoundRect(0f, top, headX, bottom, radius, radius, paints.fill)

        if (!showStreak || settledAmount >= 1f) return
        paints.drawStreak(
            canvas = canvas,
            left = (headX - geometry.streakLengthPx).coerceAtLeast(0f),
            right = headX,
            top = top,
            bottom = bottom,
            radius = radius,
            settled = settledAmount,
        )
    }
}

/**
 * The single in-app download progress bar: a continuous rounded track, a solid VRKA-purple
 * fill, a long near-white streak that fades back from the real progress edge, and a
 * comet-tail cone of purple-tinted white light that fans out behind the head. Nothing
 * lights ahead of the head, so the streak's rounded cap is the brightest point and stays
 * crisp. The active-download strip and the queue card
 * both use this composable, so the position, percentage and byte counters always come from
 * the same job state. Updates are smoothed toward the reported progress; when the job stops
 * reporting, the bar is static. On reaching 100% the glow fades and narrows away and the
 * white streak fades out, leaving a solid purple bar; both return if progress restarts.
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
        Color.White.copy(alpha = 0.2f)
    }
    val accent = GlowProgressPurple
    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    // Starts from zero so a bar that first appears at, say, 60% still slides there. The easing
    // starts at full speed, so frequent updates restarting it do not make the bar stutter.
    val animatedProgress = remember { Animatable(0f) }
    val settled = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val finished = target >= 1f
        if (reduceMotion) {
            animatedProgress.snapTo(target)
            settled.snapTo(if (finished) 1f else 0f)
            return@LaunchedEffect
        }
        if (!finished) {
            launch { settled.animateTo(0f, tween(GLOW_PROGRESS_SLIDE_MILLIS)) }
        }
        animatedProgress.animateTo(
            target,
            tween(durationMillis = GLOW_PROGRESS_SLIDE_MILLIS, easing = LinearOutSlowInEasing),
        )
        if (finished) {
            settled.animateTo(1f, tween(GLOW_PROGRESS_SETTLE_MILLIS))
        }
    }
    val renderer = remember { GlowProgressRenderer() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight * GLOW_LAYOUT_HEIGHT_RATIO)
            // Not clipped: the cone fades out past these bounds, behind neighbouring text.
            .drawWithCache {
                val track = size.height / GLOW_LAYOUT_HEIGHT_RATIO
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        renderer.draw(
                            canvas = canvas.nativeCanvas,
                            width = size.width,
                            height = size.height,
                            trackHeight = track,
                            progress = animatedProgress.value,
                            trackColor = trackColor,
                            accent = accent,
                            lightSurface = colors.isLight,
                            settled = settled.value,
                        )
                    }
                }
            },
    )
}
