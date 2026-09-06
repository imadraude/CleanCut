package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.repository.SubjectSegmenter

/**
 * UseCase to check whether required neural network weights for a given mode are cached on device.
 */
class CheckModelReadyUseCase(
    private val segmenter: SubjectSegmenter
) {
    operator fun invoke(mode: SegmentationMode): Boolean {
        return segmenter.isModelReady(mode)
    }
}
