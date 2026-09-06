package com.cleancut.bgremover.data.ml

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Industrial-grade edge defringing and matte color decontamination.
 *
 * Implements:
 * 1. Adaptive Border Alpha Choke (morphological boundary erosion): cleans outer neural overshoot.
 * 2. Edge Color Dilation (Color Bleed): purges contaminated background color from semi-transparent
 *    edges by propagating genuine solid foreground color outward, matching Photoshop and Spine 2D standards.
 * 3. Analytical Matte Un-premultiplication: recovers true stroke color from anti-aliasing.
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

        val origPixels = pixels.clone()

        // 1. Fast background color sampling from definite background pixels (mask < 0.03)
        var bgRSum = 0L
        var bgGSum = 0L
        var bgBSum = 0L
        var bgSampleCount = 0

        val step = max(1, totalPixels / 1000)
        var sIdx = 0
        while (sIdx < totalPixels) {
            if (mask[sIdx] < 0.03f) {
                val px = origPixels[sIdx]
                bgRSum += (px shr 16) and 0xFF
                bgGSum += (px shr 8) and 0xFF
                bgBSum += px and 0xFF
                bgSampleCount++
            }
            sIdx += step
        }

        // Corner sampling fallback: if background wasn't sampled enough (e.g. edge-case masks),
        // check image corners which are virtually always background in matting tasks
        if (bgSampleCount < 10) {
            val corners = intArrayOf(
                0,
                width - 1,
                (height - 1) * width,
                height * width - 1
            )
            for (cIdx in corners) {
                if (cIdx in 0 until totalPixels && mask[cIdx] < 0.25f) {
                    val px = origPixels[cIdx]
                    bgRSum += (px shr 16) and 0xFF
                    bgGSum += (px shr 8) and 0xFF
                    bgBSum += px and 0xFF
                    bgSampleCount++
                }
            }
        }

        val hasDefiniteBg = bgSampleCount >= 4
        val avgBgR = if (hasDefiniteBg) (bgRSum / bgSampleCount).toFloat() else 255f
        val avgBgG = if (hasDefiniteBg) (bgGSum / bgSampleCount).toFloat() else 255f
        val avgBgB = if (hasDefiniteBg) (bgBSum / bgSampleCount).toFloat() else 255f

        val bgBrightness = 0.299f * avgBgR + 0.587f * avgBgG + 0.114f * avgBgB
        val isLightBg = hasDefiniteBg && bgBrightness > 185f

        // 2. Process each pixel with Choke and Edge Color Dilation
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val i = rowOffset + x
                val alpha = mask[i]

                // Definite background
                if (alpha < 0.02f) {
                    pixels[i] = 0
                    continue
                }

                val px = origPixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                // Definite solid foreground
                if (alpha > 0.98f) {
                    pixels[i] = (-0x1000000) or (r shl 16) or (g shl 8) or b
                    continue
                }

                // --- A. Adaptive Border Alpha Choke ---
                // Detect whether this boundary pixel touches the outer background
                var touchesBg = false
                if (x > 0 && mask[i - 1] < 0.05f) touchesBg = true
                else if (x < width - 1 && mask[i + 1] < 0.05f) touchesBg = true
                else if (y > 0 && mask[i - width] < 0.05f) touchesBg = true
                else if (y < height - 1 && mask[i + width] < 0.05f) touchesBg = true

                if (touchesBg && alpha <= 0.25f) {
                    pixels[i] = 0
                    continue
                }

                if (isLightBg) {
                    val diffR = abs(r - avgBgR)
                    val diffG = abs(g - avgBgG)
                    val diffB = abs(b - avgBgB)
                    val maxDiff = max(diffR, max(diffG, diffB))
                    if (maxDiff < 25f && alpha < 0.40f) {
                        pixels[i] = 0
                        continue
                    }
                }

                // --- B. Edge Color Dilation (Color Bleed) ---
                // Purge background color contamination by pulling pure color from solid foreground (mask >= 0.80)
                var bestNeighborAlpha = 0.80f
                var bestNeighborColor = -1

                // Search radius 1 (8 neighbors)
                val minY1 = max(0, y - 1)
                val maxY1 = min(height - 1, y + 1)
                val minX1 = max(0, x - 1)
                val maxX1 = min(width - 1, x + 1)

                for (ny in minY1..maxY1) {
                    val nRow = ny * width
                    for (nx in minX1..maxX1) {
                        if (nx == x && ny == y) continue
                        val ni = nRow + nx
                        val nAlpha = mask[ni]
                        if (nAlpha > bestNeighborAlpha) {
                            bestNeighborAlpha = nAlpha
                            bestNeighborColor = origPixels[ni]
                        }
                    }
                }

                // If not found in radius 1, expand search to radius 2
                if (bestNeighborColor == -1) {
                    val minY2 = max(0, y - 2)
                    val maxY2 = min(height - 1, y + 2)
                    val minX2 = max(0, x - 2)
                    val maxX2 = min(width - 1, x + 2)

                    for (ny in minY2..maxY2) {
                        val nRow = ny * width
                        for (nx in minX2..maxX2) {
                            if (abs(nx - x) <= 1 && abs(ny - y) <= 1) continue
                            val ni = nRow + nx
                            val nAlpha = mask[ni]
                            if (nAlpha > bestNeighborAlpha) {
                                bestNeighborAlpha = nAlpha
                                bestNeighborColor = origPixels[ni]
                            }
                        }
                    }
                }

                var finalR: Int
                var finalG: Int
                var finalB: Int

                if (bestNeighborColor != -1) {
                    finalR = (bestNeighborColor shr 16) and 0xFF
                    finalG = (bestNeighborColor shr 8) and 0xFF
                    finalB = bestNeighborColor and 0xFF
                } else if (isLightBg) {
                    val invAlpha = 1f - alpha
                    finalR = ((r - invAlpha * avgBgR) / alpha).toInt().coerceIn(0, 255)
                    finalG = ((g - invAlpha * avgBgG) / alpha).toInt().coerceIn(0, 255)
                    finalB = ((b - invAlpha * avgBgB) / alpha).toInt().coerceIn(0, 255)
                } else {
                    finalR = r
                    finalG = g
                    finalB = b
                }

                val alphaInt = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                if (alphaInt == 0) {
                    pixels[i] = 0
                } else {
                    pixels[i] = (alphaInt shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        return pixels
    }
}
