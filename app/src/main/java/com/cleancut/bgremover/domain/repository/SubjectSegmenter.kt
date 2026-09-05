package com.cleancut.bgremover.domain.repository

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationResult

/**
 * Deep module interface for background removal and subject segmentation.
 * Callers do not need to know whether the implementation uses ML Kit, ONNX, or TFLite.
 */
interface SubjectSegmenter {
    /**
     * Executes segmentation on the input bitmap and extracts foreground subjects.
     */
    suspend fun segment(bitmap: Bitmap): Result<SegmentationResult>

    /**
     * Releases underlying ML hardware resources and delegates.
     */
    fun close()
}
