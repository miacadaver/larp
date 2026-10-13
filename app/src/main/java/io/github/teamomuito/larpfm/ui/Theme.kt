package io.github.teamomuito.larpfm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always the Pictoclip look: no dark mode, and no dynamic (wallpaper-based) colors.
private val DsColorScheme = lightColorScheme(
    primary = DsColors.Cursor,
    onPrimary = Color.White,
    primaryContainer = DsColors.Pressed,
    onPrimaryContainer = DsColors.Ink,
    secondary = DsColors.Border,
    onSecondary = Color.White,
    background = DsColors.Background,
    onBackground = DsColors.Ink,
    surface = DsColors.Panel,
    onSurface = DsColors.Ink,
    surfaceVariant = DsColors.Background,
    onSurfaceVariant = DsColors.Subtle,
    outline = DsColors.Subtle,
    outlineVariant = DsColors.Shadow,
    error = DsColors.Error,
    onError = Color.White,
)

@Composable
fun LarpTheme(content: @Composable () -> Unit) {
    // Text goes through DsText, which sets DotGothic16 itself, so the Material typography is left alone.
    MaterialTheme(colorScheme = DsColorScheme, content = content)
}
