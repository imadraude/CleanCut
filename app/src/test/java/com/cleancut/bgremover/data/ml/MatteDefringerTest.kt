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

    @Test
    fun testColorDilationPurgesBlueSkySpillOnWideEdgeWithNonLightBackground() {
        // Width: 12 pixels. Non-light blue sky background (brightness ~123 < 185)
        // Foreground: dark brown coat (60, 40, 20)
        // Transition ribbon has width 4 pixels (indices 4..7).
        val width = 12
        val height = 1
        val pixels = IntArray(width)
        val mask = FloatArray(width)

        // Pixels 0..3: pure blue sky background (70, 130, 230)
        val skyColor = (-0x1000000) or (70 shl 16) or (130 shl 8) or 230
        for (i in 0..3) {
            pixels[i] = skyColor
            mask[i] = 0.0f
        }

        // Pixel 4: outer edge (alpha = 0.20, touches background -> choked)
        pixels[4] = skyColor
        mask[4] = 0.20f

        // Pixel 5: distance 3 away from solid foreground (index 8).
        // Heavily contaminated with blue sky color in raw image:
        pixels[5] = (-0x1000000) or (67 shl 16) or (108 shl 8) or 177
        mask[5] = 0.45f

        // Pixel 6: distance 2 away from solid foreground
        pixels[6] = (-0x1000000) or (65 shl 16) or (85 shl 8) or 125
        mask[6] = 0.60f

        // Pixel 7: distance 1 away from solid foreground
        pixels[7] = (-0x1000000) or (63 shl 16) or (62 shl 8) or 73
        mask[7] = 0.70f

        // Pixels 8..11: Solid dark brown jacket (60, 40, 20)
        val coatColor = (-0x1000000) or (60 shl 16) or (40 shl 8) or 20
        for (i in 8..11) {
            pixels[i] = coatColor
            mask[i] = 1.0f
        }

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        // Pixel 4 should be choked to 0
        assertEquals(0, result[4])

        // Pixel 5 (distance 3) must be purged of blue sky (B=177) and dilated from coat (B=20)
        val px5 = result[5]
        val r5 = (px5 shr 16) and 0xFF
        val g5 = (px5 shr 8) and 0xFF
        val b5 = px5 and 0xFF
        assertEquals("Pixel 5 R should match solid coat", 60, r5)
        assertEquals("Pixel 5 G should match solid coat", 40, g5)
        assertEquals("Pixel 5 B should match solid coat (not blue sky 177)", 20, b5)

        // Pixel 6 and 7 should also have solid coat colors
        val px6 = result[6]
        assertEquals(60, (px6 shr 16) and 0xFF)
        assertEquals(40, (px6 shr 8) and 0xFF)
        assertEquals(20, px6 and 0xFF)
    }

    @Test
    fun testAdaptiveBorderChokeSuppressesGreenScreenOvershootOnNonLightBackground() {
        val width = 8
        val height = 1
        val pixels = IntArray(width)
        val mask = FloatArray(width)

        // Pixels 0..3: pure green background (20, 220, 30)
        val greenBg = (-0x1000000) or (20 shl 16) or (220 shl 8) or 30
        for (i in 0..3) {
            pixels[i] = greenBg
            mask[i] = 0.0f
        }

        // Pixel 4: Neural network overshot into green screen: alpha = 0.30f, color = (22, 218, 32)
        pixels[4] = (-0x1000000) or (22 shl 16) or (218 shl 8) or 32
        mask[4] = 0.30f

        // Pixels 5..7: Solid object (200, 50, 50)
        val objColor = (-0x1000000) or (200 shl 16) or (50 shl 8) or 50
        for (i in 5..7) {
            pixels[i] = objColor
            mask[i] = 1.0f
        }

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        // Pixel 4 is an overshoot into green background and must be completely choked to 0
        assertEquals("Overshoot pixel into green background must be zeroed", 0, result[4])

        // Solid object must remain intact
        val px5Alpha = (result[5] shr 24) and 0xFF
        assertEquals(255, px5Alpha)
    }

    @Test
    fun testLocalBackgroundSamplingDecontaminatesMultiColoredEdges() {
        // 2x3 image:
        // Row 0: pure red background (255, 0, 0), edge, solid white
        // Row 1: pure blue background (0, 0, 255), edge, solid white
        val width = 3
        val height = 2
        val pixels = IntArray(6)
        val mask = FloatArray(6)

        // Row 0
        pixels[0] = (-0x1000000) or (255 shl 16) or (0 shl 8) or 0
        mask[0] = 0.0f

        // Edge on row 0: contaminated by red, alpha = 0.6
        pixels[1] = (-0x1000000) or (255 shl 16) or (150 shl 8) or 150
        mask[1] = 0.6f

        // Solid white on row 0
        pixels[2] = (-0x1000000) or (255 shl 16) or (255 shl 8) or 255
        mask[2] = 1.0f

        // Row 1
        pixels[3] = (-0x1000000) or (0 shl 16) or (0 shl 8) or 255
        mask[3] = 0.0f

        // Edge on row 1: contaminated by blue, alpha = 0.6
        pixels[4] = (-0x1000000) or (150 shl 16) or (150 shl 8) or 255
        mask[4] = 0.6f

        // Solid white on row 1
        pixels[5] = (-0x1000000) or (255 shl 16) or (255 shl 8) or 255
        mask[5] = 1.0f

        val result = MatteDefringer.decontaminatePixels(pixels, mask, width, height)

        // Edge pixels 1 and 4 should both be dilated to solid white (255, 255, 255)
        val px1 = result[1]
        assertEquals(255, (px1 shr 16) and 0xFF)
        assertEquals(255, (px1 shr 8) and 0xFF)
        assertEquals(255, px1 and 0xFF)

        val px4 = result[4]
        assertEquals(255, (px4 shr 16) and 0xFF)
        assertEquals(255, (px4 shr 8) and 0xFF)
        assertEquals(255, px4 and 0xFF)
    }
}
