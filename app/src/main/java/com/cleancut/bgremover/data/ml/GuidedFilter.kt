package com.cleancut.bgremover.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance Guided Filter implementation for edge-preserving mask refinement.
 * Refines a low-resolution or soft segmentation mask using the high-resolution RGB image as guidance.
 * Snaps blurry boundaries to actual visual edges (hair, clothing contours, silhouette).
 */
object GuidedFilter {

    /**
     * Refines an alpha mask using the original image as guidance.
     *
     * @param original Guidance image (full resolution RGB).
     * @param inputMask Soft alpha mask values [0.0f .. 1.0f] of size width x height.
     * @param radius Box filter radius (default: 6).
     * @param eps Regularization parameter (default: 0.001f).
     * @return Refined alpha mask [0.0f .. 1.0f].
     */
    fun filter(
        original: Bitmap,
        inputMask: FloatArray,
        radius: Int = 6,
        eps: Float = 1e-3f
    ): FloatArray {
        val width = original.width
        val height = original.height
        val size = width * height

        // 1. Extract normalized luminance [0.0 .. 1.0] from guidance bitmap
        val I = FloatArray(size)
        val pixels = IntArray(size)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in 0 until size) {
            val c = pixels[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            I[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 2. Guided Filter computation:
        // mean_I = boxfilter(I)
        val meanI = boxFilter(I, width, height, radius)
        // mean_P = boxfilter(P)
        val meanP = boxFilter(inputMask, width, height, radius)

        // II = I .* I; corr_I = boxfilter(II)
        val II = FloatArray(size) { i -> I[i] * I[i] }
        val corrI = boxFilter(II, width, height, radius)

        // IP = I .* P; corr_IP = boxfilter(IP)
        val IP = FloatArray(size) { i -> I[i] * inputMask[i] }
        val corrIP = boxFilter(IP, width, height, radius)

        // var_I = corr_I - mean_I .* mean_I
        // cov_IP = corr_IP - mean_I .* mean_P
        val a = FloatArray(size)
        val b = FloatArray(size)

        for (i in 0 until size) {
            val varI = max(0f, corrI[i] - meanI[i] * meanI[i])
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            val aVal = covIP / (varI + eps)
            a[i] = aVal
            b[i] = meanP[i] - aVal * meanI[i]
        }

        // mean_a = boxfilter(a)
        val meanA = boxFilter(a, width, height, radius)
        // mean_b = boxfilter(b)
        val meanB = boxFilter(b, width, height, radius)

        // output Q = mean_a .* I + mean_b
        val output = FloatArray(size)
        for (i in 0 until size) {
            var q = meanA[i] * I[i] + meanB[i]
            // Contrast curve: clean up near-zero noise and solidify high confidence
            q = when {
                q < 0.15f -> 0f
                q > 0.85f -> 1f
                else -> smoothstep(0.15f, 0.85f, q)
            }
            output[i] = q
        }

        return output
    }

    /**
     * Fast separable 2D box filter in O(N) using sliding accumulation.
     */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val temp = FloatArray(w * h)
        val dest = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var sum = 0f

            for (x in 0 until r) {
                sum += src[rowOffset + min(w - 1, x)]
            }
            for (x in 0 until w) {
                val left = max(0, x - r - 1)
                val right = min(w - 1, x + r)
                if (x + r < w) sum += src[rowOffset + right]
                if (x - r - 1 >= 0) sum -= src[rowOffset + left]
                val count = min(w - 1, x + r) - max(0, x - r) + 1
                temp[rowOffset + x] = sum / count
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sum = 0f
            for (y in 0 until r) {
                sum += temp[min(h - 1, y) * w + x]
            }
            for (y in 0 until h) {
                val top = max(0, y - r - 1)
                val bottom = min(h - 1, y + r)
                if (y + r < h) sum += temp[bottom * w + x]
                if (y - r - 1 >= 0) sum -= temp[top * w + x]
                val count = min(h - 1, y + r) - max(0, y - r) + 1
                dest[y * w + x] = sum / count
            }
        }

        return dest
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
