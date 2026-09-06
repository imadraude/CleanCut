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
}
