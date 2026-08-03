package com.gpiano.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val GpianoColors = lightColorScheme(
    primary = Sage,
    onPrimary = MistSurface,
    primaryContainer = PaleSage,
    onPrimaryContainer = Ink,
    background = MistCanvas,
    onBackground = Ink,
    surface = MistSurface,
    onSurface = Ink,
    surfaceVariant = PaleSage,
    onSurfaceVariant = MutedInk,
    outline = Outline,
)

@Composable
fun GpianoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GpianoColors, content = content)
}
