package com.cleancut.bgremover.domain.usecase

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SegmentImageUseCase(
    private val segmenter: SubjectSegmenter
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: SegmentationMode = SegmentationMode.FAST
    ): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        segmenter.segment(bitmap, mode)
    }

    fun isModelReady(mode: SegmentationMode): Boolean = segmenter.isModelReady(mode)

    suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        segmenter.downloadModel(mode, onProgress)
    }
}
