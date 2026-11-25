package com.torrenthub.aio.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.torrenthub.aio.data.AppThemeMode

@Immutable
data class AppPalette(
    val mode: AppThemeMode,
    val background: Color,
    val barSurface: Color,
    val cardSurface: Color,
    val subCardSurface: Color,
    val consoleSurface: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentRed: Color,
    val accentAmber: Color,
    val accentPurple: Color,
    val chipBg: Color,
    val chipFg: Color
) {
    val isLight: Boolean get() = mode == AppThemeMode.LIGHT
    val isAmoled: Boolean get() = mode == AppThemeMode.AMOLED
    val cardBorder: BorderStroke?
        get() = when (mode) {
            AppThemeMode.LIGHT -> BorderStroke(1.dp, borderColor)
            AppThemeMode.AMOLED -> BorderStroke(1.dp, borderColor)
            AppThemeMode.DARK -> null
        }
}

private val LightAppPalette = AppPalette(
    mode = AppThemeMode.LIGHT,
    background = Color(0xFFF1F5F9),
    barSurface = Color(0xFFFFFFFF),
    cardSurface = Color(0xFFFFFFFF),
    subCardSurface = Color(0xFFF8FAFC),
    consoleSurface = Color(0xFFF1F5F9),
    borderColor = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF64748B),
    accentBlue = Color(0xFF0284C7),
    accentGreen = Color(0xFF15803D),
    accentRed = Color(0xFFDC2626),
    accentAmber = Color(0xFFB45309),
    accentPurple = Color(0xFF7C3AED),
    chipBg = Color(0xFFE2E8F0),
    chipFg = Color(0xFF1E293B)
)

private val DarkAppPalette = AppPalette(
    mode = AppThemeMode.DARK,
    background = Color(0xFF0B1120),
    barSurface = Color(0xFF0F172A),
    cardSurface = Color(0xFF1E293B),
    subCardSurface = Color(0xFF0F172A),
    consoleSurface = Color(0xFF020617),
    borderColor = Color(0xFF334155),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8),
    accentBlue = Color(0xFF38BDF8),
    accentGreen = Color(0xFF4ADE80),
    accentRed = Color(0xFFF87171),
    accentAmber = Color(0xFFFBBF24),
    accentPurple = Color(0xFFC084FC),
    chipBg = Color(0xFF334155),
    chipFg = Color(0xFFE2E8F0)
)

private val AmoledAppPalette = AppPalette(
    mode = AppThemeMode.AMOLED,
    background = Color(0xFF000000),
    barSurface = Color(0xFF000000),
    cardSurface = Color(0xFF0A0A0A),
    subCardSurface = Color(0xFF121212),
    consoleSurface = Color(0xFF050505),
    borderColor = Color(0xFF1F2937),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFD1D5DB),
    textMuted = Color(0xFF9CA3AF),
    accentBlue = Color(0xFF38BDF8),
    accentGreen = Color(0xFF4ADE80),
    accentRed = Color(0xFFF87171),
    accentAmber = Color(0xFFFBBF24),
    accentPurple = Color(0xFFC084FC),
    chipBg = Color(0xFF1A1A1A),
    chipFg = Color(0xFFE5E7EB)
)

private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    background = Color(0xFFF1F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    background = Color(0xFF0B1120),
    surface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155)
)

private val AmoledColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF121212),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF262626)
)

val LocalAppPalette = staticCompositionLocalOf { DarkAppPalette }

object AppTheme {
    val palette: AppPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAppPalette.current
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun TorrentHubTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val palette = when (themeMode) {
        AppThemeMode.LIGHT -> LightAppPalette
        AppThemeMode.DARK -> DarkAppPalette
        AppThemeMode.AMOLED -> AmoledAppPalette
    }
    val colorScheme = when (themeMode) {
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.DARK -> DarkColorScheme
        AppThemeMode.AMOLED -> AmoledColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.barSurface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = palette.barSurface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = palette.isLight
            controller.isAppearanceLightNavigationBars = palette.isLight
        }
    }

    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
