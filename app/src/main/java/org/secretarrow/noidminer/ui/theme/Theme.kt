package org.secretarrow.noidminer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * NoidMiner v2 — "Aurora Midnight" theme.
 *
 * - Default: pakai brand palette (Midnight dark / Day light) supaya identitas
 *   miner app konsisten di semua device.
 * - Opsi dynamicColor (Android 12+): jika user enable di Settings (default off).
 *   Miner app butuh brand identity kuat → dynamic bukan default.
 * - StatusBar / NavBar ikut scheme (edge-to-edge) — already enabled in Activity.
 */
private val AuroraDarkScheme = darkColorScheme(
    primary = AuroraViolet,
    onPrimary = TextPrimary,
    primaryContainer = AuroraVioletDim,
    onPrimaryContainer = TextPrimary,
    secondary = AuroraCyan,
    onSecondary = MidnightBase,
    secondaryContainer = MidnightSurfaceHigh,
    onSecondaryContainer = TextPrimary,
    tertiary = AuroraMint,
    onTertiary = MidnightBase,
    tertiaryContainer = MidnightSurfaceHigh,
    onTertiaryContainer = AuroraMint,
    background = MidnightBase,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceTint = AuroraViolet,
    outline = MidnightOutline,
    outlineVariant = MidnightOutline,
    error = AuroraRose,
    onError = TextPrimary,
    errorContainer = AuroraRose,
    onErrorContainer = TextPrimary,
    scrim = MidnightBase,
)

private val AuroraLightScheme = lightColorScheme(
    primary = AuroraVioletDim,
    onPrimary = DaySurface,
    primaryContainer = AuroraViolet,
    onPrimaryContainer = DaySurface,
    secondary = AuroraCyan,
    onSecondary = DaySurface,
    secondaryContainer = DaySurfaceHigh,
    onSecondaryContainer = DayTextPrimary,
    tertiary = AuroraMint,
    onTertiary = DaySurface,
    tertiaryContainer = DaySurfaceHigh,
    onTertiaryContainer = DayTextPrimary,
    background = DayBase,
    onBackground = DayTextPrimary,
    surface = DaySurface,
    onSurface = DayTextPrimary,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayTextSecondary,
    surfaceTint = AuroraVioletDim,
    outline = DayOutline,
    outlineVariant = DayOutline,
    error = AuroraRose,
    onError = DaySurface,
    errorContainer = AuroraRose,
    onErrorContainer = DaySurface,
    scrim = DayTextPrimary,
)

@Composable
fun NoidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> AuroraDarkScheme
        else -> AuroraLightScheme
    }

    // Make status-bar icons match the theme (edge-to-edge already on).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = NoidTypography,
        content = content,
    )
}
