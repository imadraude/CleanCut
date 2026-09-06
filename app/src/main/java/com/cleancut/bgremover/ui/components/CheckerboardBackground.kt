package com.cleancut.bgremover.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * GPU-accelerated Checkerboard grid pattern to visually represent transparency.
 * Uses a hardware-tiled BitmapShader (1 GPU draw call) with cached paint to eliminate per-frame allocations.
 */
@Composable
fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    squareSizePx: Float = 32f,
    lightColor: Color = Color(0xFFEEEEEE),
    darkColor: Color = Color(0xFFDDDDDD)
) {
    val checkerboardShader = remember(squareSizePx, lightColor, darkColor) {
        val size = (squareSizePx * 2).toInt().coerceAtLeast(2)
        val half = size / 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paintLight = android.graphics.Paint().apply { color = lightColor.toArgb() }
        val paintDark = android.graphics.Paint().apply { color = darkColor.toArgb() }

        canvas.drawRect(0f, 0f, half.toFloat(), half.toFloat(), paintLight)
        canvas.drawRect(half.toFloat(), 0f, size.toFloat(), half.toFloat(), paintDark)
        canvas.drawRect(0f, half.toFloat(), half.toFloat(), size.toFloat(), paintDark)
        canvas.drawRect(half.toFloat(), half.toFloat(), size.toFloat(), size.toFloat(), paintLight)

        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    val paint = remember(checkerboardShader) {
        Paint().apply {
            asFrameworkPaint().shader = checkerboardShader
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(
                0f, 0f, size.width, size.height,
                paint.asFrameworkPaint()
            )
        }
    }
}
