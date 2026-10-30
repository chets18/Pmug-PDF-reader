package com.example.core.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pmug House Style Design Tokens:
 * Ground: #0E2B26 (deep bottle green)
 * Raised surface: #153730
 * Ink: #F2EDE3
 * Muted ink: #B9B7AA
 * Accent: #D6F25C (highlighter)
 * Text on accent: #0E2B26
 */
object PmugTokens {
    val Ground = Color(0xFF0E2B26)
    val Surface = Color(0xFF153730)
    val SurfaceSubtle = Color(0xFF1B443C)
    val Ink = Color(0xFFF2EDE3)
    val MutedInk = Color(0xFFB9B7AA)
    val Accent = Color(0xFFD6F25C)
    val TextOnAccent = Color(0xFF0E2B26)

    // Secondary subtle tones
    val Danger = Color(0xFFE57373)
    val DimmedText = Color(0x99F2EDE3) // 60% ink for read text

    // Shapes: one radius system (20dp for cards, fully round for buttons)
    val CardRadius = 20.dp
    val CardShape = RoundedCornerShape(20.dp)
    val ButtonShape = CircleShape
    val PillShape = CircleShape
}
