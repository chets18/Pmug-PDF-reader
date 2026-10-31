package com.example

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfViewerMathTest {

    @Test
    fun testZoomPanClampingMath() {
        val zoom = 2.0f
        val containerWidth = 1000f
        val containerHeight = 1000f

        val maxX = containerWidth * (zoom - 1f) / 2f
        val maxY = containerHeight * (zoom - 1f) / 2f

        assertEquals(500f, maxX, 0.01f)
        assertEquals(500f, maxY, 0.01f)

        val candidateX = 600f
        val clampedX = candidateX.coerceIn(-maxX, maxX)
        assertEquals(500f, clampedX, 0.01f)

        val candidateY = -200f
        val clampedY = candidateY.coerceIn(-maxY, maxY)
        assertEquals(-200f, clampedY, 0.01f)
    }
}
