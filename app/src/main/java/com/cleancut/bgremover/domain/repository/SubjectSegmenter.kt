package com.cleancut.bgremover.domain.repository

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import java.io.File

/**
 * Deep module interface for background removal and subject segmentation.
 * Supports both FAST (ML Kit + Guided Filter) and STUDIO (RMBG-1.4 ONNX) modes.
 */
interface SubjectSegmenter {
    /**
     * Executes segmentation using the selected quality mode.
     */
    suspend fun segment(bitmap: Bitmap, mode: SegmentationMode = SegmentationMode.FAST): Result<SegmentationResult>

    /**
     * Checks whether the high-precision studio model is present on the device.
     */
    fun isStudioModelReady(): Boolean

    /**
     * Downloads the studio model on-demand with progress reporting.
     */
    suspend fun downloadStudioModel(onProgress: (Int) -> Unit): Result<File>

    /**
     * Releases underlying ML hardware resources.
     */
    fun close()
}
