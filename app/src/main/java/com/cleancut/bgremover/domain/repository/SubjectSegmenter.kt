package com.cleancut.bgremover.domain.repository

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import java.io.File

/**
 * Deep module interface for background removal and subject segmentation.
 * Supports FAST (ML Kit + Guided Filter), STUDIO (RMBG-1.4), and ULTRA (BiRefNet).
 */
interface SubjectSegmenter {
    /**
     * Executes segmentation using the selected quality mode.
     */
    suspend fun segment(bitmap: Bitmap, mode: SegmentationMode = SegmentationMode.FAST): Result<SegmentationResult>

    /**
     * Checks whether the required neural model for the mode is cached on device.
     */
    fun isModelReady(mode: SegmentationMode): Boolean

    /**
     * Downloads the required neural model on-demand with progress reporting.
     */
    suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File>

    /**
     * Releases underlying ML hardware resources.
     */
    fun close()
}
