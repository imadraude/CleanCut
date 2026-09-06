package com.cleancut.bgremover.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatteDefringerTest {

    @Test
    fun testDefringeRecoversInkColorFromAntiAliasedWhiteEdge() {
        // Setup: 20 pixels of white background, 1 semi-transparent pixel, 1 solid foreground
        val width = 22
        val height = 1
        val pixels = IntArray(width)
        val mask = FloatArray(width)

        // Fill background with white (255, 255, 255)
        for (i in 0 until 20) {
            pixels[i] = -0x1 // 0xFFFFFFFF
            mask[i] = 0.0f
        }

        // Index 20: Anti-aliased black line art on white background:
        // True F = 0, B = 255, alpha = 0.5 => C = 0.5 * 0 + 0.5 * 255 = 128
        pixels[20] = (-0x1000000) or (128 shl 16) or (128 shl 8) or 128
        mask[20] = 0.5f

        // Index 21: Solid black line art
        pixels[21] = (-0x1000000) or 0x0
        mask[21] = 1.0f

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        // Background pixels should be clear transparent
        assertEquals(0, result[0])

        // Recovered edge pixel should have alpha ~ 128 (0x80) and RGB ~ 0 (pure black ink, white stripped!)
        val edgePx = result[20]
        val edgeAlpha = (edgePx shr 24) and 0xFF
        val edgeR = (edgePx shr 16) and 0xFF
        val edgeG = (edgePx shr 8) and 0xFF
        val edgeB = edgePx and 0xFF

        assertEquals(128, edgeAlpha)
        assertTrue("Recovered R ($edgeR) should be <= 2", edgeR <= 2)
        assertTrue("Recovered G ($edgeG) should be <= 2", edgeG <= 2)
        assertTrue("Recovered B ($edgeB) should be <= 2", edgeB <= 2)

        // Solid foreground pixel should remain intact
        assertEquals((-0x1000000).toInt(), result[21])
    }

    @Test
    fun testHaloSpillSuppressionOnLightBackground() {
        val width = 20
        val height = 1
        val pixels = IntArray(width)
        val mask = FloatArray(width)

        // Background: pure white
        for (i in 0 until 18) {
            pixels[i] = -0x1
            mask[i] = 0.0f
        }

        // Neural network overshot outline into white canvas by 1 pixel with alpha = 0.25:
        pixels[18] = -0x1 // 0xFFFFFFFF
        mask[18] = 0.25f

        // Solid foreground
        pixels[19] = (-0x1000000) or (200 shl 16) or (50 shl 8) or 50
        mask[19] = 1.0f

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        // Overshot white pixel must be completely suppressed to prevent white halo
        assertEquals(0, result[18])

        // Solid foreground preserved
        val fgAlpha = (result[19] shr 24) and 0xFF
        assertEquals(255, fgAlpha)
    }

    @Test
    fun testColorDilationPurgesGreenScreenSpillFromEdge() {
        val width = 3
        val height = 1
        val pixels = IntArray(width)
        val mask = FloatArray(width)

        // Pixel 0: Pure green screen background (0, 255, 0)
        pixels[0] = (-0x1000000) or (0 shl 16) or (255 shl 8) or 0
        mask[0] = 0.0f

        // Pixel 1: Edge with green color spill (alpha = 0.6, green-tinted RGB)
        pixels[1] = (-0x1000000) or (50 shl 16) or (200 shl 8) or 50
        mask[1] = 0.6f

        // Pixel 2: Solid brown hair foreground (139, 69, 19)
        pixels[2] = (-0x1000000) or (139 shl 16) or (69 shl 8) or 19
        mask[2] = 1.0f

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        val edgePx = result[1]
        val edgeR = (edgePx shr 16) and 0xFF
        val edgeG = (edgePx shr 8) and 0xFF
        val edgeB = edgePx and 0xFF

        // The edge pixel's RGB must be dilated from the solid brown hair (139, 69, 19), NOT green!
        assertEquals("Edge R must match solid hair color", 139, edgeR)
        assertEquals("Edge G must match solid hair color", 69, edgeG)
        assertEquals("Edge B must match solid hair color", 19, edgeB)
    }

    @Test
    fun testCornerSamplingFallbackIdentifiesWhiteBackground() {
        // 2x2 image where only corners can be sampled
        val width = 2
        val height = 2
        val pixels = IntArray(4)
        val mask = FloatArray(4)

        // All 4 pixels are white background corners
        for (i in 0 until 4) {
            pixels[i] = -0x1
            mask[i] = 0.05f
        }

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)
        // With touchesBg and alpha <= 0.25f, all outer border pixels must be suppressed to 0
        for (i in 0 until 4) {
            assertEquals("Background pixel must be zeroed out", 0, result[i])
        }
    }
}
