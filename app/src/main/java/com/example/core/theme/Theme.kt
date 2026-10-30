package com.example.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PmugColorScheme = darkColorScheme(
    primary = PmugTokens.Accent,
    onPrimary = PmugTokens.TextOnAccent,
    primaryContainer = PmugTokens.Surface,
    onPrimaryContainer = PmugTokens.Accent,
    secondary = PmugTokens.Accent,
    onSecondary = PmugTokens.TextOnAccent,
    background = PmugTokens.Ground,
    onBackground = PmugTokens.Ink,
    surface = PmugTokens.Surface,
    onSurface = PmugTokens.Ink,
    surfaceVariant = PmugTokens.SurfaceSubtle,
    onSurfaceVariant = PmugTokens.MutedInk,
    outline = PmugTokens.SurfaceSubtle
)

@Composable
fun PmugTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PmugColorScheme,
        typography = PmugTypography,
        content = content
    )
}
