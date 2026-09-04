package com.mvrk.vrka

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

val VrkaPurple = Color(0xFF8140DC)
val VrkaPurpleLight = Color(0xFF9D65F0)
val VrkaPurpleDark = Color(0xFF6428B8)
val VrkaSuccess = Color(0xFF2BCB77)
val VrkaWarning = Color(0xFFE7A93D)
val VrkaError = Color(0xFFEF5A67)

// Refined Glass & Surface Tokens
val VrkaGlassSurface = Color(0xEB101014)
val VrkaGlassBorder = Color(0x28FFFFFF)
val VrkaSurfaceCard = Color(0xFF0D0D10)
val VrkaCardBorder = Color(0x18FFFFFF)

private val AmoledColors = darkColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF26133C),
    onPrimaryContainer = Color(0xFFE9DAFA),
    background = Color.Black,
    onBackground = Color(0xFFF7F5F9),
    surface = Color(0xFF0C0C0E),
    onSurface = Color(0xFFF7F5F9),
    surfaceVariant = Color(0xFF141418),
    onSurfaceVariant = Color(0xFFB0ACB8),
    outline = Color(0xFF2C2C34),
    outlineVariant = Color(0xFF1E1E24),
    error = VrkaError,
)

private val StandardDarkColors = darkColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF34204D),
    onPrimaryContainer = Color(0xFFE8D9FA),
    background = Color(0xFF121216),
    onBackground = Color(0xFFF5F2F8),
    surface = Color(0xFF1B1A20),
    onSurface = Color(0xFFF5F2F8),
    surfaceVariant = Color(0xFF29272F),
    onSurfaceVariant = Color(0xFFCBC5D1),
    outline = Color(0xFF4A4650),
    outlineVariant = Color(0xFF302D36),
    error = VrkaError,
)

private val LightColors = lightColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1E8FC),
    onPrimaryContainer = Color(0xFF241236),
    background = Color.White,
    onBackground = Color(0xFF141216),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF141216),
    surfaceVariant = Color(0xFFE8E8EB),
    onSurfaceVariant = Color(0xFF4E4B54),
    outline = Color(0xFFC8C8CF),
    outlineVariant = Color(0xFFE0E0E6),
    error = Color(0xFFB3261E),
)

// Monospace font family for technical telemetry, branding, and VRKA identity
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)
val VrkaMonoFamily = SpaceMono
val VrkaSansFamily = FontFamily.Default

// Disciplined Typography: High-clarity System Sans for readable UI hierarchy;
// SpaceMono (VrkaMonoFamily) is preserved specifically for technical metrics, versions, bitrates, and telemetry.
private val VrkaTypography = Typography()

@Composable
fun VrkaTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isLight = themeMode == ThemeMode.LIGHT
    val colors = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> if (amoled) AmoledColors else StandardDarkColors
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
    MaterialTheme(
        colorScheme = colors,
        typography = VrkaTypography,
        content = content,
    )
}
