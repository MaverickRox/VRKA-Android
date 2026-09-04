package com.mvrk.vrka

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================================
// VRKA DESIGN TOKENS
// =========================================================================

object VrkaTokens {
    // Surface colors
    val BackgroundAmoled = Color(0xFF000000)
    val SurfaceCard = Color(0xFF101015)
    val SurfaceElevated = Color(0xFF16161E)
    val SurfaceInset = Color(0xFF0B0B0F)
    val SurfaceNav = Color(0xEE121218)

    // Borders
    val BorderSubtle = Color(0x1AFFFFFF)
    val BorderNav = Color(0x2EFFFFFF)
    val BorderActive = Color(0x559D65F0)

    // Accents
    val Accent = Color(0xFF8B47EF)
    val AccentLight = Color(0xFFA86DF5)
    val AccentContainer = Color(0xFF25143D)

    // Status
    val Success = Color(0xFF20D87E)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFFF4E5B)

    // Text
    val TextPrimary = Color(0xFFF7F5F9)
    val TextSecondary = Color(0xFF9894A0)
    val TextTertiary = Color(0xFF65626E)

    // Radii
    val RadiusSmall = 8.dp
    val RadiusMedium = 14.dp
    val RadiusCard = 18.dp
    val RadiusPill = 28.dp

    // Spring physics (tuned for 120Hz LTPO display: fast settle, zero overshoot wobble)
    val SettleSpring: AnimationSpec<Float> = spring(dampingRatio = 0.76f, stiffness = 340f)
}

// =========================================================================
// REUSABLE SURFACE PRIMITIVES
// =========================================================================

@Composable
fun VrkaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
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
            modifier = Modifier.padding(16.dp),
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

// =========================================================================
// SECTION CONTAINER (Inspired by CupertinoSection & BitChord grouping)
// =========================================================================

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

// =========================================================================
// SLIDING SEGMENTED CONTROL (Equal-width slots, sliding indicator puck)
// =========================================================================

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
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    var rowSize by remember { mutableStateOf(IntSize.Zero) }

    val n = items.size
    val pillShape = RoundedCornerShape(VrkaTokens.RadiusMedium)

    val tabWidthPx = if (rowSize.width > 0 && n > 0) rowSize.width.toFloat() / n else 0f
    val targetIndicatorOffsetPx = selectedIndex * tabWidthPx

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
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { (tabWidthPx - with(density) { 8.dp.toPx() }).toDp() })
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
                            onItemSelected(item)
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

// =========================================================================
// STATUS BADGE (Aligned dot + crisp metadata label)
// =========================================================================

@Composable
fun VrkaStatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(VrkaTokens.RadiusSmall),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = if (isMonospace) {
                    MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily)
                } else {
                    MaterialTheme.typography.labelSmall
                },
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
            )
        }
    }
}
