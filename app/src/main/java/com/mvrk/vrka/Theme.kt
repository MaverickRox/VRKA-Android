package com.mvrk.vrka

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

val VRKA_PURPLE = Color(0xFF6F30D5)
val VrkaPurple = VRKA_PURPLE
val VrkaPurpleLight = Color(0xFF8B4FE8)
val VrkaPurpleDark = Color(0xFF5522A8)
val VrkaSuccess = Color(0xFF2BCB77)
val VrkaWarning = Color(0xFFE7A93D)
val VrkaError = Color(0xFFEF5A67)

data class VrkaColors(
    val background: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val surfaceInset: Color,
    val surfaceFloatingNav: Color,
    val borderSubtle: Color,
    val borderNav: Color,
    val borderActive: Color,
    val accent: Color,
    val accentLight: Color,
    val accentContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val isLight: Boolean,
)

val AmoledVrkaColors = VrkaColors(
    background = Color(0xFF000000),
    surfaceCard = Color(0xFF000000),
    surfaceElevated = Color(0xFF08080C),
    surfaceInset = Color(0xFF000000),
    surfaceFloatingNav = Color(0xF2000000),
    borderSubtle = Color(0x1FFFFFFF),
    borderNav = Color(0x2EFFFFFF),
    borderActive = Color(0x558B4FE8),
    accent = VrkaPurple,
    accentLight = VrkaPurpleLight,
    accentContainer = Color(0xFF210E3B),
    success = VrkaSuccess,
    warning = VrkaWarning,
    error = VrkaError,
    textPrimary = Color(0xFFF7F5F9),
    textSecondary = Color(0xFF9894A0),
    textTertiary = Color(0xFF65626E),
    isLight = false,
)

val StandardDarkVrkaColors = VrkaColors(
    background = Color(0xFF121216),
    surfaceCard = Color(0xFF1B1A20),
    surfaceElevated = Color(0xFF222129),
    surfaceInset = Color(0xFF141418),
    surfaceFloatingNav = Color(0xEB16151C),
    borderSubtle = Color(0x22FFFFFF),
    borderNav = Color(0x33FFFFFF),
    borderActive = Color(0x558B4FE8),
    accent = VrkaPurple,
    accentLight = VrkaPurpleLight,
    accentContainer = Color(0xFF2E1452),
    success = VrkaSuccess,
    warning = VrkaWarning,
    error = VrkaError,
    textPrimary = Color(0xFFF5F2F8),
    textSecondary = Color(0xFFA5A0AD),
    textTertiary = Color(0xFF706B78),
    isLight = false,
)

val LightVrkaColors = VrkaColors(
    background = Color(0xFFF6F6F8),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceInset = Color(0xFFEDEDF2),
    surfaceFloatingNav = Color(0xF2FFFFFF),
    borderSubtle = Color(0x18000000),
    borderNav = Color(0x24000000),
    borderActive = Color(0x666F30D5),
    accent = VrkaPurple,
    accentLight = VrkaPurpleDark,
    accentContainer = Color(0xFFEFE8FB),
    success = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    error = Color(0xFFDC2626),
    textPrimary = Color(0xFF111015),
    textSecondary = Color(0xFF5E5A68),
    textTertiary = Color(0xFF8C8797),
    isLight = true,
)

val LocalVrkaColors = staticCompositionLocalOf { AmoledVrkaColors }

// Refined Glass & Surface Tokens
val VrkaGlassSurface = Color(0xEB000000)
val VrkaGlassBorder = Color(0x28FFFFFF)
val VrkaSurfaceCard = Color(0xFF000000)
val VrkaCardBorder = Color(0x1FFFFFFF)

private val AmoledColors = darkColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF210E3B),
    onPrimaryContainer = Color(0xFFE9DAFA),
    background = Color.Black,
    onBackground = Color(0xFFF7F5F9),
    surface = Color.Black,
    onSurface = Color(0xFFF7F5F9),
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFB0ACB8),
    outline = Color(0x2EFFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = VrkaError,
)

private val StandardDarkColors = darkColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E1452),
    onPrimaryContainer = Color(0xFFE8D9FA),
    background = Color(0xFF121216),
    onBackground = Color(0xFFF5F2F8),
    surface = Color(0xFF1B1A20),
    onSurface = Color(0xFFF5F2F8),
    surfaceVariant = Color(0xFF222129),
    onSurfaceVariant = Color(0xFFCBC5D1),
    outline = Color(0xFF4A4650),
    outlineVariant = Color(0xFF302D36),
    error = VrkaError,
)

private val LightColors = lightColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFE8FB),
    onPrimaryContainer = Color(0xFF241236),
    background = Color(0xFFF6F6F8),
    onBackground = Color(0xFF111015),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111015),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF5E5A68),
    outline = Color(0xFFC8C8CF),
    outlineVariant = Color(0xFFE0E0E6),
    error = Color(0xFFDC2626),
)

// Monospace font family for technical telemetry, branding, and VRKA identity
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)
val VrkaMonoFamily = SpaceMono
val VrkaSansFamily = SpaceMono // Strictly SpaceMono globally across VRKA

// 100% Monospace Typography: SpaceMono is the primary application typeface across all UI text.
private val defaultTypography = Typography()
val VrkaTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = VrkaMonoFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = VrkaMonoFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = VrkaMonoFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = VrkaMonoFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = VrkaMonoFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = VrkaMonoFamily),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = VrkaMonoFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = VrkaMonoFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = VrkaMonoFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = VrkaMonoFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = VrkaMonoFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = VrkaMonoFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = VrkaMonoFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = VrkaMonoFamily),
)

@Composable
fun VrkaTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isLight = themeMode == ThemeMode.LIGHT
    val amoledEffective = amoled && !isLight
    val vrkaColors = when {
        isLight -> LightVrkaColors
        amoledEffective -> AmoledVrkaColors
        else -> StandardDarkVrkaColors
    }
    val colors = when {
        isLight -> LightColors
        amoledEffective -> AmoledColors
        else -> StandardDarkColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(LocalVrkaColors provides vrkaColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = VrkaTypography,
            content = content,
        )
    }
}
