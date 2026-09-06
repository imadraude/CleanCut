package com.cleancut.bgremover.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskRefineEngineTest {

    @Test
    fun testEraseModeClearsAlpha() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 } // Red
        val cutoutPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 } // Red solid

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        assertFalse(engine.canUndo)
        assertFalse(engine.canRedo)

        // Erase center pixel (5, 5) with radius 1
        engine.startStroke()
        engine.continueStroke(5, 5, radius = 1, mode = BrushMode.ERASE)
        engine.endStroke()

        assertTrue(engine.canUndo)
        assertEquals(0, engine.workingPixels[5 * width + 5]) // Should be transparent (0)

        // Non-touched pixel (0, 0) should remain unchanged
        assertEquals((-0x1000000) or 0xFF0000, engine.workingPixels[0])
    }

    @Test
    fun testRestoreModeRestoresOriginalPixel() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height) { (-0x1000000) or 0x00FF00 } // Green solid
        val cutoutPixels = IntArray(width * height) { 0 } // All transparent

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        // Restore at center (5, 5)
        engine.startStroke()
        engine.continueStroke(5, 5, radius = 2, mode = BrushMode.RESTORE)
        engine.endStroke()

        val centerPx = engine.workingPixels[5 * width + 5]
        assertEquals((-0x1000000) or 0x00FF00, centerPx)

        // Untouched should remain 0
        assertEquals(0, engine.workingPixels[0])
    }

    @Test
    fun testUndoRedoLifecycle() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height) { (-0x1000000) or 0x0000FF } // Blue
        val cutoutPixels = IntArray(width * height) { (-0x1000000) or 0x0000FF }

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        // Stroke 1: Erase (2, 2)
        engine.startStroke()
        engine.continueStroke(2, 2, radius = 1, mode = BrushMode.ERASE)
        engine.endStroke()

        assertEquals(0, engine.workingPixels[2 * width + 2])
        assertTrue(engine.canUndo)
        assertFalse(engine.canRedo)

        // Perform Undo
        assertTrue(engine.undo())
        assertEquals((-0x1000000) or 0x0000FF, engine.workingPixels[2 * width + 2]) // Blue restored!
        assertFalse(engine.canUndo)
        assertTrue(engine.canRedo)

        // Perform Redo
        assertTrue(engine.redo())
        assertEquals(0, engine.workingPixels[2 * width + 2]) // Erased again!
        assertTrue(engine.canUndo)
        assertFalse(engine.canRedo)
    }

    @Test
    fun testStrokeInterpolationFillsGaps() {
        val width = 20
        val height = 20
        val originalPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 }
        val cutoutPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 }

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        engine.startStroke()
        // Point 1 at (2, 5)
        val box1 = engine.continueStroke(2, 5, radius = 1, mode = BrushMode.ERASE)
        assertTrue(box1 != null)

        // Point 2 jumped to (8, 5) without lifting finger
        val box2 = engine.continueStroke(8, 5, radius = 1, mode = BrushMode.ERASE)
        assertTrue(box2 != null)
        engine.endStroke()

        // With interpolation, intermediate points between 2 and 8 along row 5 must be erased
        assertEquals(0, engine.workingPixels[5 * width + 2])
        assertEquals(0, engine.workingPixels[5 * width + 4]) // Intermediate interpolated point
        assertEquals(0, engine.workingPixels[5 * width + 6]) // Intermediate interpolated point
        assertEquals(0, engine.workingPixels[5 * width + 8])
    }

    @Test
    fun testDefringeCleansBorderSpill() {
        val width = 10
        val height = 10
        val solidBlue = (-0x1000000) or 0x0000FF
        val originalPixels = IntArray(width * height) { solidBlue }

        // Construct a cutout where (5,5) has solid foreground color (Blue, alpha 255)
        // and neighbor (5,6) has semi-transparent green background spill (Green, alpha 100)
        val cutoutPixels = IntArray(width * height) { 0 }
        cutoutPixels[5 * width + 5] = solidBlue
        cutoutPixels[6 * width + 5] = (100 shl 24) or 0x00FF00 // Green halo spill

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        engine.startStroke()
        engine.continueStroke(5, 6, radius = 2, mode = BrushMode.DEFRINGE)
        engine.endStroke()

        val defringedPixel = engine.workingPixels[6 * width + 5]
        val alpha = (defringedPixel ushr 24) and 0xFF
        val rgb = defringedPixel and 0x00FFFFFF

        // Alpha should remain preserved (~100), but RGB should borrow solid Blue (0x0000FF) from foreground
        assertEquals(100, alpha)
        assertEquals(0x0000FF, rgb)
    }

    @Test
    fun testDefringeCleansBorderSpillOnWideEdge() {
        val width = 10
        val height = 10
        val solidRed = (-0x1000000) or 0xFF0000
        val originalPixels = IntArray(width * height) { solidRed }

        val cutoutPixels = IntArray(width * height) { 0 }
        cutoutPixels[5 * width + 1] = solidRed // Solid red foreground at x=1, y=5
        // Edge pixel at x=4, y=5 (distance 3 away from solid foreground)
        cutoutPixels[5 * width + 4] = (120 shl 24) or 0x00FF00 // Green halo spill at distance 3

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        engine.startStroke()
        engine.continueStroke(4, 5, radius = 8, mode = BrushMode.DEFRINGE)
        engine.endStroke()

        val defringedPixel = engine.workingPixels[5 * width + 4]
        val alpha = (defringedPixel ushr 24) and 0xFF
        val rgb = defringedPixel and 0x00FFFFFF

        assertEquals(120, alpha)
        assertEquals(0xFF0000, rgb)
    }

    @Test
    fun testDefringeCleansBorderSpillOnWhiteForeground() {
        val width = 10
        val height = 10
        val solidWhite = -1 // 0xFFFFFFFF
        val originalPixels = IntArray(width * height) { solidWhite }

        val cutoutPixels = IntArray(width * height) { 0 }
        cutoutPixels[5 * width + 5] = solidWhite // Solid white foreground
        cutoutPixels[6 * width + 5] = (100 shl 24) or 0x00FF00 // Green halo spill

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        engine.startStroke()
        engine.continueStroke(5, 6, radius = 2, mode = BrushMode.DEFRINGE)
        engine.endStroke()

        val defringedPixel = engine.workingPixels[6 * width + 5]
        val alpha = (defringedPixel ushr 24) and 0xFF
        val rgb = defringedPixel and 0x00FFFFFF

        assertEquals(100, alpha)
        assertEquals(0xFFFFFF, rgb)
    }

    @Test
    fun testHistoryChangedCallbackOnStrokeUndoRedo() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 }
        val cutoutPixels = IntArray(width * height) { (-0x1000000) or 0xFF0000 }

        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        val historyEvents = mutableListOf<Pair<Boolean, Boolean>>()
        engine.onHistoryChanged = { canUndo, canRedo ->
            historyEvents.add(Pair(canUndo, canRedo))
        }

        // Initial assignment fires immediately
        assertEquals(1, historyEvents.size)
        assertEquals(Pair(false, false), historyEvents.last())

        // 1. Draw a stroke that modifies pixels
        engine.startStroke()
        engine.continueStroke(5, 5, radius = 1, mode = BrushMode.ERASE)
        engine.endStroke()

        assertEquals(2, historyEvents.size)
        assertEquals(Pair(true, false), historyEvents.last())

        // 2. Undo
        assertTrue(engine.undo())
        assertEquals(3, historyEvents.size)
        assertEquals(Pair(false, true), historyEvents.last())

        // 3. Redo
        assertTrue(engine.redo())
        assertEquals(4, historyEvents.size)
        assertEquals(Pair(true, false), historyEvents.last())

        // 4. Stroke with no changes should NOT fire history event
        engine.startStroke()
        engine.continueStroke(5, 5, radius = 1, mode = BrushMode.ERASE) // (5, 5) is already erased
        engine.endStroke()

        assertEquals(4, historyEvents.size) // Unchanged
    }

    @Test
    fun testComputeEdgeMagnitudeDetectsContrastDifference() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height)
        // Fill columns 0..4 with White, columns 5..9 with Black
        for (y in 0 until height) {
            for (x in 0 until width) {
                originalPixels[y * width + x] = if (x < 5) -0x1 else -0x1000000
            }
        }
        val cutoutPixels = originalPixels.clone()
        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        // Inside uniform white region (x = 2, y = 5), edge magnitude should be near 0
        val uniformMagnitude = engine.computeEdgeMagnitude(2, 5)
        assertEquals(0, uniformMagnitude)

        // At the boundary (x = 4 or x = 5, y = 5), edge magnitude should be high
        val boundaryMagnitude = engine.computeEdgeMagnitude(4, 5)
        assertTrue(boundaryMagnitude > 50)
    }

    @Test
    fun testSmartFloodFillErasesUniformRegionUpToEdge() {
        val width = 10
        val height = 10
        val originalPixels = IntArray(width * height)
        // Background is Green (0x00FF00), center 4x4 square (rows 3..6, cols 3..6) is White (0xFFFFFF)
        val green = (-0x1000000) or 0x00FF00
        val white = -0x1
        for (y in 0 until height) {
            for (x in 0 until width) {
                originalPixels[y * width + x] = if (x in 3..6 && y in 3..6) white else green
            }
        }
        val cutoutPixels = originalPixels.clone()
        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        assertFalse(engine.canUndo)

        // Flood fill from (4, 4) in ERASE mode with tolerance 30
        val box = engine.floodFill(4, 4, mode = BrushMode.ERASE, tolerance = 30)
        assertTrue(box != null)
        assertTrue(engine.canUndo)

        // Center square should now have alpha = 0 (erased)
        for (y in 3..6) {
            for (x in 3..6) {
                assertEquals(0, engine.workingPixels[y * width + x])
            }
        }

        // Outside background (e.g. 0, 0 or 2, 2) should remain intact (alpha 255 green)
        assertEquals(green, engine.workingPixels[0])
        assertEquals(green, engine.workingPixels[2 * width + 2])

        // Verify Undo restores the filled square
        assertTrue(engine.undo())
        for (y in 3..6) {
            for (x in 3..6) {
                assertEquals(white, engine.workingPixels[y * width + x])
            }
        }
    }

    @Test
    fun testSmartFloodFillRestoreMode() {
        val width = 8
        val height = 8
        val blue = (-0x1000000) or 0x0000FF
        val red = (-0x1000000) or 0xFF0000
        val originalPixels = IntArray(width * height) { idx ->
            val x = idx % width
            if (x < 4) blue else red
        }
        // Entire cutout is transparent
        val cutoutPixels = IntArray(width * height) { 0 }
        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        // Flood fill restore on blue half starting at (1, 1)
        val box = engine.floodFill(1, 1, mode = BrushMode.RESTORE, tolerance = 20)
        assertTrue(box != null)

        // Left half should be restored to Blue
        for (y in 0 until height) {
            for (x in 0 until 4) {
                assertEquals(blue, engine.workingPixels[y * width + x])
            }
        }
        // Right half should remain transparent 0
        for (y in 0 until height) {
            for (x in 4 until width) {
                assertEquals(0, engine.workingPixels[y * width + x])
            }
        }
    }

    @Test
    fun testSmartEdgeAwareBrushStopsAtHighContrastBoundary() {
        val width = 12
        val height = 12
        val white = -0x1
        val black = -0x1000000
        val originalPixels = IntArray(width * height) { idx ->
            val x = idx % width
            if (x < 6) white else black
        }
        val cutoutPixels = originalPixels.clone()
        val engine = MaskRefineEngine(width, height, originalPixels, cutoutPixels)

        // Enable edge-aware mode
        engine.isEdgeAware = true
        engine.edgeTolerance = 25
        engine.edgeBarrier = 40

        // Start stroke at (4, 6) in White area, with radius 3
        // Radius 3 centered at x = 4 covers x in 1..7 (overlapping into Black area at x = 6, 7)
        engine.startStroke()
        engine.continueStroke(4, 6, radius = 3, mode = BrushMode.ERASE)
        engine.endStroke()

        // White pixels in range (e.g. x=4, y=6 and x=5, y=6) should be erased
        assertEquals(0, engine.workingPixels[6 * width + 4])
        assertEquals(0, engine.workingPixels[6 * width + 5])

        // Black pixels across the boundary (x=6, y=6 and x=7, y=6) must NOT be erased!
        assertEquals(black, engine.workingPixels[6 * width + 6])
        assertEquals(black, engine.workingPixels[6 * width + 7])
    }
}
