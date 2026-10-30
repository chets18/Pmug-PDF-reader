package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.domain.retention.RetentionEngine

@Composable
fun BionicText(
    text: String,
    modifier: Modifier = Modifier,
    isBionicEnabled: Boolean = false,
    fontSize: TextUnit = 18.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    if (!isBionicEnabled) {
        Text(
            text = text,
            modifier = modifier,
            fontSize = fontSize,
            color = color,
            lineHeight = lineHeight
        )
        return
    }

    val annotated = buildAnnotatedString {
        val words = text.split(" ")
        for ((idx, word) in words.withIndex()) {
            val (boldPart, restPart) = RetentionEngine.splitForBionicReading(word)
            withStyle(style = SpanStyle(fontWeight = FontWeight.ExtraBold, color = color)) {
                append(boldPart)
            }
            withStyle(style = SpanStyle(fontWeight = FontWeight.Normal, color = color.copy(alpha = 0.85f))) {
                append(restPart)
            }
            if (idx < words.size - 1) {
                append(" ")
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight
    )
}
