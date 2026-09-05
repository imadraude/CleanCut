package com.cleancut.bgremover.domain.usecase

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase that coordinates background removal on the default background dispatcher.
 */
class SegmentImageUseCase(
    private val segmenter: SubjectSegmenter
) {
    suspend operator fun invoke(bitmap: Bitmap): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        segmenter.segment(bitmap)
    }
}
