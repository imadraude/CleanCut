package com.cleancut.bgremover.data.ml

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * High-performance edge defringing and matte color decontamination.
 *
 * Solves color bleeding and edge halos (particularly white fringes on 2D anime and illustrations)
 * by un-premultiplying background matte color bleed and suppressing phantom edge spills.
 */
object MatteDefringer {

    /**
     * Decontaminates semi-transparent edges and generates the final transparent Bitmap.
     *
     * @param original Original source Bitmap.
     * @param mask Probability mask array [0.0f .. 1.0f] of size (width * height).
     * @param width Width in pixels.
     * @param height Height in pixels.
     * @return ARGB_8888 Bitmap with clean, halo-free edges.
     */
    fun createCutout(
        original: Bitmap,
        mask: FloatArray,
        width: Int,
        height: Int
    ): Bitmap {
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        decontaminatePixels(pixels, mask, width, height)

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    /**
     * In-place mathematical edge defringing and matte color decontamination on raw ARGB pixels.
     * Pure Kotlin function with no Android Bitmap dependencies, ensuring full JVM testability.
     */
    fun decontaminatePixels(
        pixels: IntArray,
        mask: FloatArray,
        width: Int,
        height: Int
    ): IntArray {
        val totalPixels = width * height

        // 1. Fast background color sampling from definite background pixels (mask < 0.02)
        var bgRSum = 0L
        var bgGSum = 0L
        var bgBSum = 0L
        var bgSampleCount = 0

        val step = max(1, totalPixels / 1000)
        var idx = 0
        while (idx < totalPixels) {
            if (mask[idx] < 0.02f) {
                val px = pixels[idx]
                bgRSum += (px shr 16) and 0xFF
                bgGSum += (px shr 8) and 0xFF
                bgBSum += px and 0xFF
                bgSampleCount++
            }
            idx += step
        }

        val hasDefiniteBg = bgSampleCount >= 10
        val avgBgR = if (hasDefiniteBg) (bgRSum / bgSampleCount).toFloat() else 255f
        val avgBgG = if (hasDefiniteBg) (bgGSum / bgSampleCount).toFloat() else 255f
        val avgBgB = if (hasDefiniteBg) (bgBSum / bgSampleCount).toFloat() else 255f

        val bgBrightness = 0.299f * avgBgR + 0.587f * avgBgG + 0.114f * avgBgB
        val isLightBg = hasDefiniteBg && bgBrightness > 185f

        // 2. Process each pixel with decontaminator and halo suppression
        for (i in 0 until totalPixels) {
            val alpha = mask[i]

            // Clear definite background
            if (alpha < 0.02f) {
                pixels[i] = 0
                continue
            }

            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF

            // Pass through solid foreground
            if (alpha > 0.98f) {
                pixels[i] = (-0x1000000) or (r shl 16) or (g shl 8) or b
                continue
            }

            var finalAlpha = alpha
            var finalR = r
            var finalG = g
            var finalB = b

            if (isLightBg) {
                // Color proximity to background
                val diffR = abs(r - avgBgR)
                val diffG = abs(g - avgBgG)
                val diffB = abs(b - avgBgB)
                val maxDiff = max(diffR, max(diffG, diffB))

                // If pixel color is essentially identical to the light background and alpha is low-to-mid,
                // this is an outer halo spill overshooting the outline: suppress completely.
                if (maxDiff < 25f && alpha < 0.40f) {
                    pixels[i] = 0
                    continue
                }

                // Suppress outer boundary noise
                if (alpha < 0.06f) {
                    pixels[i] = 0
                    continue
                }

                // Un-premultiply the background matte color from the observed anti-aliased edge:
                // C = alpha * F + (1 - alpha) * B  =>  F = (C - (1 - alpha) * B) / alpha
                val invAlpha = 1f - finalAlpha
                finalR = ((r - invAlpha * avgBgR) / finalAlpha).toInt().coerceIn(0, 255)
                finalG = ((g - invAlpha * avgBgG) / finalAlpha).toInt().coerceIn(0, 255)
                finalB = ((b - invAlpha * avgBgB) / finalAlpha).toInt().coerceIn(0, 255)
            } else {
                if (finalAlpha < 0.03f) {
                    pixels[i] = 0
                    continue
                }
            }

            val alphaInt = (finalAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (alphaInt == 0) {
                pixels[i] = 0
            } else {
                pixels[i] = (alphaInt shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        return pixels
    }
}
