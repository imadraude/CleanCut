package com.cleancut.bgremover.domain.usecase

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-responsibility UseCase for on-device background removal and subject segmentation.
 */
class SegmentImageUseCase(
    private val segmenter: SubjectSegmenter
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: SegmentationMode = SegmentationMode.FAST
    ): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        segmenter.segment(bitmap, mode)
    }
}
