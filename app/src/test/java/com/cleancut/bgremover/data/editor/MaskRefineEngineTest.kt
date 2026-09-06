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
}
