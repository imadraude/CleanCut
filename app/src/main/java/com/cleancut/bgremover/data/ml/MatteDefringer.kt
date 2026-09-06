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

        val maxSearchRadius = 8

        // 2. Process each pixel with Choke and Deep Radial Color Dilation
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

                // Check 4-connected outer background touch
                var touchesBg = false
                if (x > 0 && mask[i - 1] < 0.05f) touchesBg = true
                else if (x < width - 1 && mask[i + 1] < 0.05f) touchesBg = true
                else if (y > 0 && mask[i - width] < 0.05f) touchesBg = true
                else if (y < height - 1 && mask[i + width] < 0.05f) touchesBg = true

                // --- A. Local Sampling & Multi-Ring Foreground Search ---
                var bestNeighborAlpha = 0.75f
                var bestNeighborColor = -1
                var bestNeighborDistSq = Int.MAX_VALUE

                var localBgRSum = 0
                var localBgGSum = 0
                var localBgBSum = 0
                var localBgCount = 0

                // Fallback foreground candidate if no pixel reaches 0.75f
                var fallbackNeighborAlpha = alpha + 0.05f
                var fallbackNeighborColor = -1

                var foundSolidFg = false
                for (radius in 1..maxSearchRadius) {
                    val minY = max(0, y - radius)
                    val maxY = min(height - 1, y + radius)
                    val minX = max(0, x - radius)
                    val maxX = min(width - 1, x + radius)

                    for (ny in minY..maxY) {
                        val isEdgeY = (ny == minY || ny == maxY)
                        val nRow = ny * width
                        for (nx in minX..maxX) {
                            val isEdgeX = (nx == minX || nx == maxX)
                            if (!isEdgeY && !isEdgeX) continue
                            if (nx == x && ny == y) continue

                            val ni = nRow + nx
                            val nAlpha = mask[ni]

                            // Sample local background
                            if (nAlpha <= 0.05f) {
                                val nPx = origPixels[ni]
                                localBgRSum += (nPx shr 16) and 0xFF
                                localBgGSum += (nPx shr 8) and 0xFF
                                localBgBSum += nPx and 0xFF
                                localBgCount++
                            }

                            // Solid foreground search (mask >= 0.75f)
                            if (nAlpha >= 0.75f) {
                                val dx = nx - x
                                val dy = ny - y
                                val distSq = dx * dx + dy * dy
                                val nPx = origPixels[ni]

                                if (!foundSolidFg || nAlpha > bestNeighborAlpha || (nAlpha == bestNeighborAlpha && distSq < bestNeighborDistSq)) {
                                    bestNeighborAlpha = nAlpha
                                    bestNeighborColor = nPx
                                    bestNeighborDistSq = distSq
                                    foundSolidFg = true
                                }
                            } else if (!foundSolidFg && nAlpha > fallbackNeighborAlpha) {
                                fallbackNeighborAlpha = nAlpha
                                fallbackNeighborColor = origPixels[ni]
                            }
                        }
                    }

                    // Once solid foreground is found in this ring, don't look further out for foreground
                    if (foundSolidFg) {
                        break
                    }
                }

                val finalNeighborColor = if (bestNeighborColor != -1) bestNeighborColor else fallbackNeighborColor

                // Determine effective background color for this pixel
                val effBgR = if (localBgCount > 0) localBgRSum.toFloat() / localBgCount else avgBgR
                val effBgG = if (localBgCount > 0) localBgGSum.toFloat() / localBgCount else avgBgG
                val effBgB = if (localBgCount > 0) localBgBSum.toFloat() / localBgCount else avgBgB

                // --- B. Universal Adaptive Border Alpha Choke ---
                // 1. Direct border boundary choke for low alpha
                if (touchesBg && alpha <= 0.25f) {
                    pixels[i] = 0
                    continue
                }

                // 2. Choke background overshoot across ANY background color if pixel resembles background
                val diffBgR = abs(r - effBgR)
                val diffBgG = abs(g - effBgG)
                val diffBgB = abs(b - effBgB)
                val maxBgDiff = max(diffBgR, max(diffBgG, diffBgB))
                if (maxBgDiff < 30f && alpha < 0.40f) {
                    pixels[i] = 0
                    continue
                }

                // --- C. True Color Recovery & Edge Decontamination ---
                var finalR: Int
                var finalG: Int
                var finalB: Int

                if (finalNeighborColor != -1) {
                    // Propagate genuine foreground color outward (Color Dilation / Despill)
                    finalR = (finalNeighborColor shr 16) and 0xFF
                    finalG = (finalNeighborColor shr 8) and 0xFF
                    finalB = finalNeighborColor and 0xFF
                } else {
                    // Analytical Matte Un-mixing using effective local background
                    val invAlpha = 1f - alpha
                    finalR = ((r - invAlpha * effBgR) / alpha).toInt().coerceIn(0, 255)
                    finalG = ((g - invAlpha * effBgG) / alpha).toInt().coerceIn(0, 255)
                    finalB = ((b - invAlpha * effBgB) / alpha).toInt().coerceIn(0, 255)
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
