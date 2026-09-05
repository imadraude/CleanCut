package com.cleancut.bgremover.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil

/**
 * Checkerboard grid pattern to visually represent transparency.
 */
@Composable
fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    squareSizePx: Float = 32f,
    lightColor: Color = Color(0xFFEEEEEE),
    darkColor: Color = Color(0xFFDDDDDD)
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val cols = ceil(width / squareSizePx).toInt()
        val rows = ceil(height / squareSizePx).toInt()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val color = if ((row + col) % 2 == 0) lightColor else darkColor
                drawRect(
                    color = color,
                    topLeft = Offset(col * squareSizePx, row * squareSizePx),
                    size = Size(squareSizePx, squareSizePx)
                )
            }
        }
    }
}
