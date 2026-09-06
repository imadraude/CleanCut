package com.cleancut.bgremover.ui.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ZoomTransformCalculatorTest {

    @Test
    fun testZoomFromCentroidMaintainsFocalContentPosition() {
        val containerWidth = 1000f
        val containerHeight = 1000f
        val center = Offset(500f, 500f)

        // User pinches at (200, 200), expanding scale from 1.0 to 2.0 without panning
        val initialScale = 1.0f
        val initialOffset = Offset.Zero
        val touchCentroid = Offset(200f, 200f)

        // Point on content directly underneath touch before zoom:
        // contentPt = (touch - center - offset) / scale
        val contentPt = (touchCentroid - center - initialOffset) / initialScale // (-300, -300)

        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = initialScale,
            currentOffset = initialOffset,
            centroid = touchCentroid,
            pan = Offset.Zero,
            zoom = 2.0f,
            containerWidth = containerWidth,
            containerHeight = containerHeight
        )

        assertEquals(2.0f, result.scale, 1e-4f)

        // Content point position on screen after zoom:
        // screenPt = center + offset + scale * contentPt
        val screenPtAfter = center + result.offset + contentPt * result.scale

        // The screen position must match the touch centroid (0 drift)
        assertEquals(touchCentroid.x, screenPtAfter.x, 1e-3f)
        assertEquals(touchCentroid.y, screenPtAfter.y, 1e-3f)
    }

    @Test
    fun testContinuousMultiStepPinchWithMovingCentroid() {
        val containerWidth = 1000f
        val containerHeight = 1000f
        val center = Offset(500f, 500f)

        var scale = 1.0f
        var offset = Offset.Zero

        val initialTouch = Offset(300f, 400f)
        val trackedContentPt = (initialTouch - center - offset) / scale

        var currentTouch = initialTouch

        // Simulate 20 small pinch steps with simultaneous zoom and pan
        for (step in 0 until 20) {
            val stepZoom = 1.05f
            val stepPan = Offset(2.0f, 1.0f)
            val centroidBeforePan = currentTouch
            currentTouch += stepPan

            val result = ZoomTransformCalculator.calculateTransform(
                currentScale = scale,
                currentOffset = offset,
                centroid = centroidBeforePan,
                pan = stepPan,
                zoom = stepZoom,
                containerWidth = containerWidth,
                containerHeight = containerHeight
            )

            scale = result.scale
            offset = result.offset

            val screenPt = center + offset + trackedContentPt * scale
            val drift = hypot((screenPt.x - currentTouch.x).toDouble(), (screenPt.y - currentTouch.y).toDouble())
            assertTrue("Drift at step $step ($drift px) exceeded tolerance", drift < 1e-2)
        }

        assertTrue("Final scale should be > 2.0", scale > 2.0f)
    }

    @Test
    fun testZoomAtCenterProducesZeroOffset() {
        val containerWidth = 1000f
        val containerHeight = 1000f
        val center = Offset(500f, 500f)

        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = 1.0f,
            currentOffset = Offset.Zero,
            centroid = center,
            pan = Offset.Zero,
            zoom = 2.5f,
            containerWidth = containerWidth,
            containerHeight = containerHeight
        )

        assertEquals(2.5f, result.scale, 1e-4f)
        assertEquals(0f, result.offset.x, 1e-3f)
        assertEquals(0f, result.offset.y, 1e-3f)
    }

    @Test
    fun testZoomOutSnapsToIdentityWhenBelowOrEqual1() {
        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = 1.2f,
            currentOffset = Offset(50f, 50f),
            centroid = Offset(300f, 300f),
            pan = Offset.Zero,
            zoom = 0.5f, // 1.2 * 0.5 = 0.6 -> snaps to 1.0f
            containerWidth = 1000f,
            containerHeight = 1000f
        )

        assertEquals(1.0f, result.scale, 1e-4f)
        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun testSingleFingerPanWhenZoomedUpdatesOffset() {
        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = 2.0f,
            currentOffset = Offset(10f, 10f),
            centroid = Offset(500f, 500f),
            pan = Offset(25f, -15f),
            zoom = 1.0f, // No zoom change
            containerWidth = 1000f,
            containerHeight = 1000f
        )

        assertEquals(2.0f, result.scale, 1e-4f)
        assertEquals(35f, result.offset.x, 1e-3f)
        assertEquals(-5f, result.offset.y, 1e-3f)
    }

    @Test
    fun testSingleFingerPanWhenNotZoomedIgnoresPan() {
        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = 1.0f,
            currentOffset = Offset.Zero,
            centroid = Offset(500f, 500f),
            pan = Offset(50f, 50f),
            zoom = 1.0f,
            containerWidth = 1000f,
            containerHeight = 1000f
        )

        assertEquals(1.0f, result.scale, 1e-4f)
        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun testZeroContainerDimensionsDoesNotThrow() {
        val result = ZoomTransformCalculator.calculateTransform(
            currentScale = 1.5f,
            currentOffset = Offset(20f, 20f),
            centroid = Offset(100f, 100f),
            pan = Offset(10f, 10f),
            zoom = 1.2f,
            containerWidth = 0f,
            containerHeight = 0f
        )

        assertEquals(1.5f, result.scale, 1e-4f)
        assertEquals(Offset(20f, 20f), result.offset)
    }
}
