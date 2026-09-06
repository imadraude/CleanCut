package com.cleancut.bgremover.ui.util

import androidx.compose.ui.geometry.Offset

/**
 * Calculates pinch-to-zoom and pan transformations pinned around a focal centroid.
 *
 * Keeps the content point directly underneath the user's fingers stationary on screen during
 * scaling and panning operations instead of scaling naively from the canvas center.
 */
object ZoomTransformCalculator {

    data class TransformResult(
        val scale: Float,
        val offset: Offset
    )

    /**
     * Computes the new scale and offset for a pinch/pan gesture step.
     *
     * @param currentScale Current zoom scale factor (>= 1.0)
     * @param currentOffset Current translation offset
     * @param centroid Focal point of the gesture in container coordinates (before pan)
     * @param pan Translation delta of the gesture during this step
     * @param zoom Multiplicative zoom factor during this step
     * @param containerWidth Layout width of the touch container
     * @param containerHeight Layout height of the touch container
     * @param minScale Minimum allowable scale (default 1.0f)
     * @param maxScale Maximum allowable scale (default 5.0f)
     * @param overscrollFraction Extra margin beyond viewport bounds allowed for panning edges comfortably (default 0.2f)
     */
    fun calculateTransform(
        currentScale: Float,
        currentOffset: Offset,
        centroid: Offset,
        pan: Offset,
        zoom: Float,
        containerWidth: Float,
        containerHeight: Float,
        minScale: Float = 1f,
        maxScale: Float = 5f,
        overscrollFraction: Float = 0.2f
    ): TransformResult {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return TransformResult(currentScale, currentOffset)
        }

        val oldScale = currentScale
        val newScale = (currentScale * zoom).coerceIn(minScale, maxScale)

        // Reset to exact center when zoomed out completely
        if (newScale <= 1f) {
            return TransformResult(scale = 1f, offset = Offset.Zero)
        }

        val effectiveScaleRatio = newScale / oldScale
        val center = Offset(containerWidth / 2f, containerHeight / 2f)
        val cRel = centroid - center

        // Maintain the invariant: the content point under the centroid before zoom
        // must remain under (centroid + pan) after zoom.
        // O' = O + (O - cRel) * (newScale / oldScale - 1) + pan
        val rawOffset = currentOffset + (currentOffset - cRel) * (effectiveScaleRatio - 1f) + pan

        // Dynamic bounding box preventing content from disappearing into the void
        // while allowing comfortable inspection of corners and edges
        val maxPanX = ((containerWidth * (newScale - 1f)) / 2f) + (containerWidth * overscrollFraction)
        val maxPanY = ((containerHeight * (newScale - 1f)) / 2f) + (containerHeight * overscrollFraction)

        val clampedOffset = Offset(
            rawOffset.x.coerceIn(-maxPanX, maxPanX),
            rawOffset.y.coerceIn(-maxPanY, maxPanY)
        )

        return TransformResult(
            scale = newScale,
            offset = clampedOffset
        )
    }
}
