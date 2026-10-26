package org.secretarrow.noidminer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = AuroraViolet,
    onPrimary = TextPrimary,
    secondary = AuroraCyan,
    onSecondary = SpaceBase,
    tertiary = AuroraMint,
    background = SpaceBase,
    onBackground = TextPrimary,
    surface = SpaceSurface,
    onSurface = TextPrimary,
    surfaceVariant = SpaceSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = SpaceOutline,
    error = AuroraRose,
)

private val LightScheme = lightColorScheme(
    primary = AuroraVioletDim,
    onPrimary = LightBase,
    secondary = AuroraCyan,
    onSecondary = LightBase,
    tertiary = AuroraMint,
    background = LightBase,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    error = AuroraRose,
)

@Composable
fun NoidTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
