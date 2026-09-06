package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UseCase to download neural network weights on-demand with progress reporting.
 */
class DownloadModelUseCase(
    private val segmenter: SubjectSegmenter
) {
    suspend operator fun invoke(
        mode: SegmentationMode,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        segmenter.downloadModel(mode, onProgress)
    }
}
