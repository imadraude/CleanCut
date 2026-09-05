package com.cleancut.bgremover.domain.model

import android.graphics.Bitmap

/**
 * Result of on-device ML subject segmentation.
 *
 * @param originalBitmap The source image bitmap with normalized orientation.
 * @param foregroundCutout The isolated subject bitmap with alpha transparency.
 * @param processingTimeMs Execution time on device in milliseconds.
 */
data class SegmentationResult(
    val originalBitmap: Bitmap,
    val foregroundCutout: Bitmap,
    val processingTimeMs: Long
)
