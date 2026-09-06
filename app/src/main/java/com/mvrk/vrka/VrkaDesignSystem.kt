package com.mvrk.vrka

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import com.mvrk.vrka.ui.backdrop.drawBackdrop
import com.mvrk.vrka.ui.backdrop.effects.blur
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
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = VrkaTokens.TextPrimary,
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
) {
    val resolvedColor = if (color != Color.Unspecified) color else VrkaTokens.TextSecondary
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = resolvedColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

enum class VrkaDestination(val label: String, @param:androidx.annotation.DrawableRes val iconRes: Int) {
    DOWNLOAD("Download", R.drawable.ic_download),
    QUEUE("Queue", R.drawable.ic_queue),
    HISTORY("History", R.drawable.ic_history),
    SETTINGS("Settings", R.drawable.ic_settings),
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

val NAV_BAR_HEIGHT = 66.dp
val NAV_BAR_CORNER_RADIUS = 33.dp
val NAV_BAR_HORIZONTAL_MARGIN = 8.dp

val NAV_CONCENTRIC_INSET = 4.5.dp
val NAV_INDICATOR_HEIGHT = NAV_BAR_HEIGHT - (NAV_CONCENTRIC_INSET * 2)
val NAV_INDICATOR_CORNER_RADIUS = NAV_INDICATOR_HEIGHT / 2

val NAV_ACTIVE_COLOR = VRKA_PURPLE
val NAV_UNSELECTED_DARK = Color(0xFFA5A1B2)
val NAV_UNSELECTED_LIGHT = Color(0xFF6B6678)

@Composable
fun VrkaFloatingNavBar(
    selectedDestination: VrkaDestination,
    onDestinationSelected: (VrkaDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val colors = LocalVrkaColors.current
    val backdrop = LocalAppBackdrop.current
    val destinations = remember { VrkaDestination.entries }
    val selectedIndex = destinations.indexOf(selectedDestination).coerceAtLeast(0)

    val textMeasurer = rememberTextMeasurer()
    val navLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = VrkaMonoFamily,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 13.sp,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
            includeFontPadding = false,
        ),
    )
    val downloadWidthPx = remember(textMeasurer, navLabelStyle) {
        textMeasurer.measure(text = "Download", style = navLabelStyle).size.width
    }
    val settingsWidthPx = remember(textMeasurer, navLabelStyle) {
        textMeasurer.measure(text = "Settings", style = navLabelStyle).size.width
    }
    val downloadWidthDp = with(density) { downloadWidthPx.toDp() }
    val settingsWidthDp = with(density) { settingsWidthPx.toDp() }

    LaunchedEffect(downloadWidthPx, settingsWidthPx) {
        android.util.Log.d(
            "VrkaNavPerf",
            "Measured Space Mono 11.5sp bounds - Download: ${downloadWidthPx}px (${downloadWidthDp}), Settings: ${settingsWidthPx}px (${settingsWidthDp})",
        )
    }

    val capsuleBorderBrush = remember(colors.isLight) {
        if (colors.isLight) {
            Brush.verticalGradient(
                listOf(
                    Color(0x42000000),
                    Color(0x1C000000),
                    Color(0x32000000),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color(0x45FFFFFF),
                    Color(0x12FFFFFF),
                    Color(0x24FFFFFF),
                ),
            )
        }
    }

    val puckBorderBrush = remember(colors.isLight) {
        if (colors.isLight) {
            Brush.verticalGradient(
                listOf(
                    Color(0x35000000),
                    Color(0x12000000),
                    Color.Transparent,
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color(0x15FFFFFF),
                    Color.Transparent,
                ),
            )
        }
    }

    val ambientHaloPaint = remember(colors.isLight, density) {
        val haloAlpha = if (colors.isLight) 0x22 else 0x28
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(haloAlpha, 0x6F, 0x30, 0xD5)
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = with(density) { 2.dp.toPx() }
            maskFilter = android.graphics.BlurMaskFilter(
                with(density) { 10.dp.toPx() },
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .padding(horizontal = NAV_BAR_HORIZONTAL_MARGIN),
    ) {
        val capsuleWidthPx = constraints.maxWidth.toFloat()
        val slotWidthPx = capsuleWidthPx / destinations.size
        val concentricInsetPx = with(density) { NAV_CONCENTRIC_INSET.toPx() }
        val indicatorWidthPx = (slotWidthPx - (concentricInsetPx * 2f)).coerceAtLeast(0f)
        val indicatorWidthDp = with(density) { indicatorWidthPx.toDp() }
        val barCornerRadiusPx = with(density) { NAV_BAR_CORNER_RADIUS.toPx() }

        val targetOffsetPx = (selectedIndex * slotWidthPx) + concentricInsetPx

        val indicatorOffset = remember { Animatable(targetOffsetPx) }

        LaunchedEffect(targetOffsetPx) {
            indicatorOffset.animateTo(
                targetValue = targetOffsetPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRoundRect(
                            0f,
                            0f,
                            size.width,
                            size.height,
                            barCornerRadiusPx,
                            barCornerRadiusPx,
                            ambientHaloPaint,
                        )
                    }
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null && isRenderEffectSupported()) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(NAV_BAR_CORNER_RADIUS) },
                            effects = { blur(with(density) { 20.dp.toPx() }) },
                            highlight = null,
                            shadow = null,
                            onDrawSurface = {
                                val veilColor = if (colors.isLight) {
                                    Color(0xFFFFFFFF).copy(alpha = 0.35f)
                                } else {
                                    Color(0xFF0F0F14).copy(alpha = 0.45f)
                                }
                                drawRect(color = veilColor)
                            },
                            backdropScale = 1f,
                        )
                    } else if (colors.isLight) {
                        Modifier.background(Color(0xFFFFFFFF).copy(alpha = 0.85f), RoundedCornerShape(NAV_BAR_CORNER_RADIUS))
                    } else {
                        Modifier.background(Color(0xFF0F0F14).copy(alpha = 0.85f), RoundedCornerShape(NAV_BAR_CORNER_RADIUS))
                    }
                )
                .border(
                    width = 1.dp,
                    brush = capsuleBorderBrush,
                    shape = RoundedCornerShape(NAV_BAR_CORNER_RADIUS),
                ),
        ) {
            if (indicatorWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .width(indicatorWidthDp)
                        .height(NAV_INDICATOR_HEIGHT)
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationX = indicatorOffset.value
                        }
                        .clip(RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS))
                        .then(
                            if (colors.isLight) {
                                Modifier.background(Color(0x18FFFFFF), RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS))
                            } else {
                                Modifier.background(Color(0x0CFFFFFF), RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS))
                            }
                        )
                        .border(1.dp, puckBorderBrush, RoundedCornerShape(NAV_INDICATOR_CORNER_RADIUS)),
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEachIndexed { index, item ->
                    val onItemClick = remember(item, onDestinationSelected) {
                        {
                            val tapStartNs = System.nanoTime()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDestinationSelected(item)
                            val mutationElapsedMs = (System.nanoTime() - tapStartNs) / 1_000_000.0
                            android.util.Log.d(
                                "VrkaNavPerf",
                                "Nav Tap -> State Mutation [${item.name}]: ${String.format("%.3f", mutationElapsedMs)} ms",
                            )
                            Unit
                        }
                    }
                    VrkaNavItem(
                        item = item,
                        isSelected = index == selectedIndex,
                        isLight = colors.isLight,
                        onClick = onItemClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VrkaNavItem(
    item: VrkaDestination,
    isSelected: Boolean,
    isLight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = NAV_ACTIVE_COLOR
    val inactiveColor = if (isLight) NAV_UNSELECTED_LIGHT else NAV_UNSELECTED_DARK
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else inactiveColor,
        animationSpec = tween(90),
        label = "navItemColor",
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
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(item.iconRes),
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = VrkaMonoFamily,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = 13.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false,
                    ),
                ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
