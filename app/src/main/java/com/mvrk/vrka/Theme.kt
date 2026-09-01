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
val VrkaSuccess = Color(0xFF2BCB77)
val VrkaWarning = Color(0xFFE7A93D)
val VrkaError = Color(0xFFEF5A67)

private val AmoledColors = darkColorScheme(
    primary = VrkaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF241236),
    onPrimaryContainer = Color(0xFFE8D9FA),
    background = Color.Black,
    onBackground = Color(0xFFFAF9FC),
    surface = Color(0xFF090909),
    onSurface = Color(0xFFFAF9FC),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFC8C4CF),
    outline = Color(0xFF363636),
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
    error = Color(0xFFB3261E),
)

private val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

private val MaterialTypography = Typography()
private val VrkaTypography = Typography(
    displayLarge = MaterialTypography.displayLarge.copy(fontFamily = SpaceMono),
    displayMedium = MaterialTypography.displayMedium.copy(fontFamily = SpaceMono),
    displaySmall = MaterialTypography.displaySmall.copy(fontFamily = SpaceMono),
    headlineLarge = MaterialTypography.headlineLarge.copy(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = MaterialTypography.headlineMedium.copy(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
    ),
    headlineSmall = MaterialTypography.headlineSmall.copy(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = MaterialTypography.titleLarge.copy(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    titleMedium = MaterialTypography.titleMedium.copy(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    titleSmall = MaterialTypography.titleSmall.copy(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    bodyLarge = MaterialTypography.bodyLarge.copy(fontFamily = SpaceMono),
    bodyMedium = MaterialTypography.bodyMedium.copy(fontFamily = SpaceMono),
    bodySmall = MaterialTypography.bodySmall.copy(fontFamily = SpaceMono),
    labelLarge = MaterialTypography.labelLarge.copy(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    labelMedium = MaterialTypography.labelMedium.copy(fontFamily = SpaceMono),
    labelSmall = MaterialTypography.labelSmall.copy(fontFamily = SpaceMono),
)

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
