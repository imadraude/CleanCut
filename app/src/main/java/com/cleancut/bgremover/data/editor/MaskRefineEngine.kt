package com.cleancut.bgremover.data.editor

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Bounding box of modified pixels returned after stroke operations.
 */
data class StrokeBox(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
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

    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(canUndo, canRedo)
        }

    // Smart edge-aware mode configuration
    var isEdgeAware: Boolean = false
    var edgeTolerance: Int = 30 // 0..100
    var edgeBarrier: Int = 50 // 0..100
    private var strokeSeedColor: Int = 0

    // Active stroke tracking
    private var isStrokeActive = false
    private var strokeMinX = Int.MAX_VALUE
    private var strokeMinY = Int.MAX_VALUE
    private var strokeMaxX = Int.MIN_VALUE
    private var strokeMaxY = Int.MIN_VALUE
    private var strokeInitialPixels: IntArray? = null

    // For smooth linear interpolation between consecutive stroke points
    private var lastStrokeX: Int? = null
    private var lastStrokeY: Int? = null

    /**
     * Low-cost perceptual color distance based on CompuPhase metric.
     * Scale: 0 (identical) to ~585225 (pure white vs pure black).
     */
    fun colorDistanceSq(c1: Int, c2: Int): Int {
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF

        val r2 = (c2 ushr 16) and 0xFF
        val g2 = (c2 ushr 8) and 0xFF
        val b2 = c2 and 0xFF

        val rMean = (r1 + r2) shr 1
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2

        return (((512 + rMean) * dr * dr) shr 8) + (4 * dg * dg) + (((767 - rMean) * db * db) shr 8)
    }

    /**
     * Fast integer Scharr gradient magnitude on original photo pixels.
     * Normalized approximately to [0, 255].
     */
    fun computeEdgeMagnitude(x: Int, y: Int): Int {
        if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) return 0

        val p00 = originalPixels[(y - 1) * width + (x - 1)]
        val p01 = originalPixels[(y - 1) * width + x]
        val p02 = originalPixels[(y - 1) * width + (x + 1)]
        val p10 = originalPixels[y * width + (x - 1)]
        val p12 = originalPixels[y * width + (x + 1)]
        val p20 = originalPixels[(y + 1) * width + (x - 1)]
        val p21 = originalPixels[(y + 1) * width + x]
        val p22 = originalPixels[(y + 1) * width + (x + 1)]

        val l00 = ((p00 ushr 16 and 0xFF) * 77 + (p00 ushr 8 and 0xFF) * 150 + (p00 and 0xFF) * 29) shr 8
        val l01 = ((p01 ushr 16 and 0xFF) * 77 + (p01 ushr 8 and 0xFF) * 150 + (p01 and 0xFF) * 29) shr 8
        val l02 = ((p02 ushr 16 and 0xFF) * 77 + (p02 ushr 8 and 0xFF) * 150 + (p02 and 0xFF) * 29) shr 8
        val l10 = ((p10 ushr 16 and 0xFF) * 77 + (p10 ushr 8 and 0xFF) * 150 + (p10 and 0xFF) * 29) shr 8
        val l12 = ((p12 ushr 16 and 0xFF) * 77 + (p12 ushr 8 and 0xFF) * 150 + (p12 and 0xFF) * 29) shr 8
        val l20 = ((p20 ushr 16 and 0xFF) * 77 + (p20 ushr 8 and 0xFF) * 150 + (p20 and 0xFF) * 29) shr 8
        val l21 = ((p21 ushr 16 and 0xFF) * 77 + (p21 ushr 8 and 0xFF) * 150 + (p21 and 0xFF) * 29) shr 8
        val l22 = ((p22 ushr 16 and 0xFF) * 77 + (p22 ushr 8 and 0xFF) * 150 + (p22 and 0xFF) * 29) shr 8

        val gx = 3 * (l02 - l00) + 10 * (l12 - l10) + 3 * (l22 - l20)
        val gy = 3 * (l20 - l00) + 10 * (l21 - l01) + 3 * (l22 - l02)

        return (kotlin.math.abs(gx) + kotlin.math.abs(gy)) / 32
    }

    /**
     * Begins an interactive brush stroke.
     */
    fun startStroke(seedX: Int? = null, seedY: Int? = null) {
        if (isStrokeActive) return
        isStrokeActive = true
        strokeMinX = Int.MAX_VALUE
        strokeMinY = Int.MAX_VALUE
        strokeMaxX = Int.MIN_VALUE
        strokeMaxY = Int.MIN_VALUE
        strokeInitialPixels = workingPixels.clone()
        lastStrokeX = null
        lastStrokeY = null
        strokeSeedColor = if (seedX != null && seedY != null && seedX in 0 until width && seedY in 0 until height) {
            originalPixels[seedY * width + seedX]
        } else {
            0
        }
    }

    private fun applyCircleStamp(
        cx: Int,
        cy: Int,
        radius: Int,
        mode: BrushMode,
        box: IntArray
    ) {
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

        if (x0 < box[0]) box[0] = x0
        if (y0 < box[1]) box[1] = y0
        if (x1 > box[2]) box[2] = x1
        if (y1 > box[3]) box[3] = y1

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

    private fun applySmartEdgeAwareStamp(
        cx: Int,
        cy: Int,
        radius: Int,
        mode: BrushMode,
        box: IntArray
    ) {
        val r = max(1, radius)
        val r2 = r * r

        val x0 = max(0, cx - r)
        val x1 = min(width - 1, cx + r)
        val y0 = max(0, cy - r)
        val y1 = min(height - 1, cy + r)

        if (x0 > x1 || y0 > y1) return

        val localW = x1 - x0 + 1
        val localH = y1 - y0 + 1
        val localSize = localW * localH

        val tolSq = ((edgeTolerance / 100f) * (edgeTolerance / 100f) * 585225f).toInt()
        val barrierLimit = edgeBarrier * 2

        val queue = IntArray(localSize)
        val visited = java.util.BitSet(localSize)

        val localCx = cx - x0
        val localCy = cy - y0
        val centerIdx = localCy * localW + localCx

        visited.set(centerIdx)
        var qHead = 0
        var qTail = 0
        queue[qTail++] = (localCy shl 16) or localCx

        while (qHead < qTail) {
            val entry = queue[qHead++]
            val ly = entry ushr 16
            val lx = entry and 0xFFFF
            val gx = x0 + lx
            val gy = y0 + ly

            val dx = gx - cx
            val dy = gy - cy
            if (dx * dx + dy * dy > r2) continue

            if (gx < strokeMinX) strokeMinX = gx
            if (gx > strokeMaxX) strokeMaxX = gx
            if (gy < strokeMinY) strokeMinY = gy
            if (gy > strokeMaxY) strokeMaxY = gy

            if (gx < box[0]) box[0] = gx
            if (gy < box[1]) box[1] = gy
            if (gx > box[2]) box[2] = gx
            if (gy > box[3]) box[3] = gy

            val gIdx = gy * width + gx
            when (mode) {
                BrushMode.ERASE -> workingPixels[gIdx] = 0
                BrushMode.RESTORE -> {
                    val origPx = originalPixels[gIdx]
                    workingPixels[gIdx] = (-0x1000000) or (origPx and 0x00FFFFFF)
                }
                BrushMode.DEFRINGE -> {
                    val currPx = workingPixels[gIdx]
                    val alpha = (currPx ushr 24) and 0xFF
                    if (alpha > 0) {
                        var bestColor = -1
                        var maxAlpha = 200
                        for (ny in max(0, gy - 2)..min(height - 1, gy + 2)) {
                            val nRow = ny * width
                            for (nx in max(0, gx - 2)..min(width - 1, gx + 2)) {
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
                            workingPixels[gIdx] = (alpha shl 24) or (bestColor and 0x00FFFFFF)
                        }
                    }
                }
            }

            val curPx = originalPixels[gIdx]
            val nbrs = intArrayOf(lx - 1, ly, lx + 1, ly, lx, ly - 1, lx, ly + 1)
            var i = 0
            while (i < 8) {
                val nx = nbrs[i]
                val ny = nbrs[i + 1]
                i += 2

                if (nx in 0 until localW && ny in 0 until localH) {
                    val nIdx = ny * localW + nx
                    if (!visited.get(nIdx)) {
                        val ngx = x0 + nx
                        val ngy = y0 + ny
                        val ndx = ngx - cx
                        val ndy = ngy - cy
                        if (ndx * ndx + ndy * ndy <= r2) {
                            val nGIdx = ngy * width + ngx
                            val nextPx = originalPixels[nGIdx]

                            val distToSeed = colorDistanceSq(nextPx, strokeSeedColor)
                            val edgeDiff = colorDistanceSq(curPx, nextPx)

                            if (distToSeed <= tolSq && (barrierLimit == 0 || edgeDiff <= barrierLimit * barrierLimit * 16)) {
                                visited.set(nIdx)
                                queue[qTail++] = (ny shl 16) or nx
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Draws a stamp along the user's touch trajectory with continuous interpolation.
     * Returns the bounding box of modified pixels for efficient partial bitmap refresh.
     */
    fun continueStroke(cx: Int, cy: Int, radius: Int, mode: BrushMode): StrokeBox? {
        if (!isStrokeActive) {
            startStroke(cx, cy)
        }
        if (strokeSeedColor == 0 && cx in 0 until width && cy in 0 until height) {
            strokeSeedColor = originalPixels[cy * width + cx]
        }

        val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

        val applyStamp = { x: Int, y: Int ->
            if (isEdgeAware) {
                applySmartEdgeAwareStamp(x, y, radius, mode, box)
            } else {
                applyCircleStamp(x, y, radius, mode, box)
            }
        }

        val lx = lastStrokeX
        val ly = lastStrokeY

        if (lx != null && ly != null) {
            val dist = hypot((cx - lx).toDouble(), (cy - ly).toDouble()).toFloat()
            val step = max(1f, radius * 0.35f)
            val numSteps = (dist / step).toInt()
            if (numSteps > 1) {
                for (i in 1..numSteps) {
                    val t = i.toFloat() / numSteps
                    val ix = (lx + (cx - lx) * t).roundToInt()
                    val iy = (ly + (cy - ly) * t).roundToInt()
                    applyStamp(ix, iy)
                }
            } else {
                applyStamp(cx, cy)
            }
        } else {
            applyStamp(cx, cy)
        }

        lastStrokeX = cx
        lastStrokeY = cy

        return if (box[0] <= box[2] && box[1] <= box[3]) {
            StrokeBox(box[0], box[1], box[2], box[3])
        } else {
            null
        }
    }

    /**
     * Executes a smart Scanline boundary flood fill starting from (startX, startY).
     * Automatically stops at color discrepancies and high contrast edges.
     * Pushes a StrokePatch for Undo/Redo and updates bitmap if provided.
     */
    fun floodFill(
        startX: Int,
        startY: Int,
        mode: BrushMode,
        tolerance: Int = 30,
        edgeSensitivity: Int = 50,
        bitmap: Bitmap? = null
    ): StrokeBox? {
        if (startX !in 0 until width || startY !in 0 until height) return null

        val seedColor = originalPixels[startY * width + startX]
        val tolSq = ((tolerance / 100f) * (tolerance / 100f) * 585225f).toInt()
        val edgeLimit = (edgeSensitivity * 2.5f).toInt()

        val visited = java.util.BitSet(width * height)

        var stackCap = 4096
        var stack = IntArray(stackCap)
        var stackPtr = 0

        fun pushSpan(x1: Int, x2: Int, y: Int, dy: Int) {
            if (stackPtr + 4 > stackCap) {
                stackCap *= 2
                stack = stack.copyOf(stackCap)
            }
            stack[stackPtr++] = x1
            stack[stackPtr++] = x2
            stack[stackPtr++] = y
            stack[stackPtr++] = dy
        }

        fun matches(x: Int, y: Int): Boolean {
            val idx = y * width + x
            if (visited.get(idx)) return false

            val px = originalPixels[idx]
            if (colorDistanceSq(px, seedColor) > tolSq) return false

            if (edgeLimit > 0 && computeEdgeMagnitude(x, y) > edgeLimit) return false

            return true
        }

        if (!matches(startX, startY)) return null

        val initial = workingPixels.clone()

        pushSpan(startX, startX, startY, 1)
        pushSpan(startX, startX, startY - 1, -1)

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        while (stackPtr > 0) {
            val dy = stack[--stackPtr]
            val y = stack[--stackPtr]
            val x2 = stack[--stackPtr]
            val x1 = stack[--stackPtr]

            if (y !in 0 until height) continue

            var l = x1
            while (l > 0 && matches(l - 1, y)) {
                l--
            }

            var r = x1
            while (r < width - 1 && matches(r + 1, y)) {
                r++
            }

            minX = min(minX, l)
            maxX = max(maxX, r)
            minY = min(minY, y)
            maxY = max(maxY, y)

            val rowOffset = y * width
            for (x in l..r) {
                val idx = rowOffset + x
                visited.set(idx)
                when (mode) {
                    BrushMode.ERASE -> workingPixels[idx] = 0
                    BrushMode.RESTORE -> {
                        val orig = originalPixels[idx]
                        workingPixels[idx] = (-0x1000000) or (orig and 0x00FFFFFF)
                    }
                    BrushMode.DEFRINGE -> {
                        val currPx = workingPixels[idx]
                        val alpha = (currPx ushr 24) and 0xFF
                        if (alpha > 0) {
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

            fun checkRow(ny: Int, nextDy: Int) {
                if (ny !in 0 until height) return
                var cx = l
                while (cx <= r) {
                    if (matches(cx, ny)) {
                        val segStart = cx
                        while (cx <= r && matches(cx, ny)) {
                            cx++
                        }
                        pushSpan(segStart, cx - 1, ny, nextDy)
                    }
                    cx++
                }
            }

            checkRow(y + dy, dy)
            if (l < x1) pushSpan(l, x1 - 1, y - dy, -dy)
            if (r > x2) pushSpan(x2 + 1, r, y - dy, -dy)
        }

        val pWidth = maxX - minX + 1
        val pHeight = maxY - minY + 1
        if (pWidth <= 0 || pHeight <= 0) return null

        val oldPatchPixels = IntArray(pWidth * pHeight)
        var hasChanges = false

        for (y in 0 until pHeight) {
            val srcRowOffset = (minY + y) * width + minX
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
            undoStack.add(StrokePatch(minX, minY, pWidth, pHeight, oldPatchPixels))
            if (undoStack.size > maxHistorySteps) {
                undoStack.removeAt(0)
            }
            redoStack.clear()
            onHistoryChanged?.invoke(canUndo, canRedo)

            bitmap?.let {
                updateBitmapRegion(it, minX, minY, maxX, maxY)
            }

            return StrokeBox(minX, minY, maxX, maxY)
        }

        return null
    }

    /**
     * Finalizes the stroke and registers an undo patch.
     */
    fun endStroke() {
        if (!isStrokeActive) return
        isStrokeActive = false
        lastStrokeX = null
        lastStrokeY = null

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
            onHistoryChanged?.invoke(canUndo, canRedo)
        }
    }

    /**
     * Fast partial copy of modified pixels directly into a display Bitmap.
     */
    fun updateBitmapRegion(bitmap: Bitmap, minX: Int, minY: Int, maxX: Int, maxY: Int) {
        val x0 = minX.coerceIn(0, width - 1)
        val y0 = minY.coerceIn(0, height - 1)
        val x1 = maxX.coerceIn(0, width - 1)
        val y1 = maxY.coerceIn(0, height - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w > 0 && h > 0) {
            bitmap.setPixels(workingPixels, y0 * width + x0, width, x0, y0, w, h)
        }
    }

    /**
     * Reverts the most recent stroke.
     * If [bitmap] is provided, updates the modified patch area directly.
     */
    fun undo(bitmap: Bitmap? = null): Boolean {
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

        bitmap?.let {
            it.setPixels(workingPixels, patch.top * width + patch.left, width, patch.left, patch.top, patch.width, patch.height)
        }

        onHistoryChanged?.invoke(canUndo, canRedo)
        return true
    }

    /**
     * Re-applies the most recently reverted stroke.
     * If [bitmap] is provided, updates the modified patch area directly.
     */
    fun redo(bitmap: Bitmap? = null): Boolean {
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

        bitmap?.let {
            it.setPixels(workingPixels, patch.top * width + patch.left, width, patch.left, patch.top, patch.width, patch.height)
        }

        onHistoryChanged?.invoke(canUndo, canRedo)
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
