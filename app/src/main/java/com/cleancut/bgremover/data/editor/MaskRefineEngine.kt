package com.cleancut.bgremover.data.editor

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Brush modes for interactive mask refinement.
 */
enum class BrushMode {
    /**
     * Erases unwanted background leftovers or anomalous objects (sets alpha to 0).
     */
    ERASE,

    /**
     * Restores accidentally clipped details from the original photo (sets alpha to 255 with original RGB).
     */
    RESTORE,

    /**
     * Decontaminates colored or white halos around hair, fur, and intricate boundaries.
     */
    DEFRINGE
}

/**
 * Lightweight bounding box patch for memory-efficient Undo/Redo operations.
 * Storing only modified bounding sub-rectangles rather than full Bitmaps avoids large allocations.
 */
data class StrokePatch(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

/**
 * Industrial-grade interactive mask and cutout refinement engine.
 * Pure JVM/Android class optimized for 60/120 FPS touch rendering.
 */
class MaskRefineEngine(
    val width: Int,
    val height: Int,
    val originalPixels: IntArray,
    currentCutoutPixels: IntArray
) {
    val workingPixels: IntArray = currentCutoutPixels.clone()

    private val undoStack = mutableListOf<StrokePatch>()
    private val redoStack = mutableListOf<StrokePatch>()
    private val maxHistorySteps = 25

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // Active stroke tracking
    private var isStrokeActive = false
    private var strokeMinX = Int.MAX_VALUE
    private var strokeMinY = Int.MAX_VALUE
    private var strokeMaxX = Int.MIN_VALUE
    private var strokeMaxY = Int.MIN_VALUE
    private var strokeInitialPixels: IntArray? = null

    /**
     * Begins an interactive brush stroke.
     */
    fun startStroke() {
        if (isStrokeActive) return
        isStrokeActive = true
        strokeMinX = Int.MAX_VALUE
        strokeMinY = Int.MAX_VALUE
        strokeMaxX = Int.MIN_VALUE
        strokeMaxY = Int.MIN_VALUE
        strokeInitialPixels = workingPixels.clone()
    }

    /**
     * Draws a circular stamp along the user's touch trajectory.
     */
    fun continueStroke(cx: Int, cy: Int, radius: Int, mode: BrushMode) {
        if (!isStrokeActive) {
            startStroke()
        }

        val r = max(1, radius)
        val r2 = r * r

        val x0 = max(0, cx - r)
        val x1 = min(width - 1, cx + r)
        val y0 = max(0, cy - r)
        val y1 = min(height - 1, cy + r)

        if (x0 > x1 || y0 > y1) return

        if (x0 < strokeMinX) strokeMinX = x0
        if (x1 > strokeMaxX) strokeMaxX = x1
        if (y0 < strokeMinY) strokeMinY = y0
        if (y1 > strokeMaxY) strokeMaxY = y1

        for (y in y0..y1) {
            val dy = y - cy
            val dy2 = dy * dy
            val rowOffset = y * width

            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy2 <= r2) {
                    val idx = rowOffset + x
                    when (mode) {
                        BrushMode.ERASE -> {
                            workingPixels[idx] = 0
                        }
                        BrushMode.RESTORE -> {
                            // Pull true color and solid alpha from original photo
                            val origPx = originalPixels[idx]
                            workingPixels[idx] = (-0x1000000) or (origPx and 0x00FFFFFF)
                        }
                        BrushMode.DEFRINGE -> {
                            // If pixel is semi-transparent or visible, purge background spill
                            val currPx = workingPixels[idx]
                            val alpha = (currPx ushr 24) and 0xFF
                            if (alpha > 0) {
                                // Search 8-neighborhood for solid foreground pixel to borrow clean color
                                var bestColor = -1
                                var maxAlpha = 200
                                for (ny in max(0, y - 2)..min(height - 1, y + 2)) {
                                    val nRow = ny * width
                                    for (nx in max(0, x - 2)..min(width - 1, x + 2)) {
                                        val nIdx = nRow + nx
                                        val nPx = workingPixels[nIdx]
                                        val nA = (nPx ushr 24) and 0xFF
                                        if (nA > maxAlpha) {
                                            maxAlpha = nA
                                            bestColor = nPx
                                        }
                                    }
                                }

                                if (bestColor != -1) {
                                    workingPixels[idx] = (alpha shl 24) or (bestColor and 0x00FFFFFF)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finalizes the stroke and registers an undo patch.
     */
    fun endStroke() {
        if (!isStrokeActive) return
        isStrokeActive = false

        val initial = strokeInitialPixels
        strokeInitialPixels = null

        if (initial == null || strokeMinX > strokeMaxX || strokeMinY > strokeMaxY) {
            return
        }

        val pWidth = strokeMaxX - strokeMinX + 1
        val pHeight = strokeMaxY - strokeMinY + 1
        val oldPatchPixels = IntArray(pWidth * pHeight)

        var hasChanges = false
        for (y in 0 until pHeight) {
            val srcRowOffset = (strokeMinY + y) * width + strokeMinX
            val dstRowOffset = y * pWidth
            for (x in 0 until pWidth) {
                val oldVal = initial[srcRowOffset + x]
                val newVal = workingPixels[srcRowOffset + x]
                if (oldVal != newVal) {
                    hasChanges = true
                }
                oldPatchPixels[dstRowOffset + x] = oldVal
            }
        }

        if (hasChanges) {
            undoStack.add(StrokePatch(strokeMinX, strokeMinY, pWidth, pHeight, oldPatchPixels))
            if (undoStack.size > maxHistorySteps) {
                undoStack.removeAt(0)
            }
            redoStack.clear()
        }
    }

    /**
     * Reverts the most recent stroke.
     */
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false

        val patch = undoStack.removeAt(undoStack.size - 1)

        // Capture current state in patch area for Redo
        val currentPatchPixels = IntArray(patch.width * patch.height)
        for (y in 0 until patch.height) {
            val rowOffset = (patch.top + y) * width + patch.left
            val pRowOffset = y * patch.width
            for (x in 0 until patch.width) {
                currentPatchPixels[pRowOffset + x] = workingPixels[rowOffset + x]
                workingPixels[rowOffset + x] = patch.pixels[pRowOffset + x]
            }
        }

        redoStack.add(StrokePatch(patch.left, patch.top, patch.width, patch.height, currentPatchPixels))
        if (redoStack.size > maxHistorySteps) {
            redoStack.removeAt(0)
        }

        return true
    }

    /**
     * Re-applies the most recently reverted stroke.
     */
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false

        val patch = redoStack.removeAt(redoStack.size - 1)

        // Capture current state in patch area for Undo
        val currentPatchPixels = IntArray(patch.width * patch.height)
        for (y in 0 until patch.height) {
            val rowOffset = (patch.top + y) * width + patch.left
            val pRowOffset = y * patch.width
            for (x in 0 until patch.width) {
                currentPatchPixels[pRowOffset + x] = workingPixels[rowOffset + x]
                workingPixels[rowOffset + x] = patch.pixels[pRowOffset + x]
            }
        }

        undoStack.add(StrokePatch(patch.left, patch.top, patch.width, patch.height, currentPatchPixels))
        if (undoStack.size > maxHistorySteps) {
            undoStack.removeAt(0)
        }

        return true
    }

    /**
     * Creates an ARGB_8888 Bitmap from current working pixels.
     */
    fun createCutoutBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(workingPixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
