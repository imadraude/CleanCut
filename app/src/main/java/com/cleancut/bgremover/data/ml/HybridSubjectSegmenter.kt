package com.cleancut.bgremover.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import java.io.File

/**
 * Hybrid implementation giving users the best of both worlds:
 * - FAST mode: Google ML Kit with Guided Filter edge refinement (0 MB, 50ms).
 * - STUDIO mode: Bria AI RMBG-1.4 via ONNX Runtime Mobile for pixel-perfect hair matting.
 */
class HybridSubjectSegmenter(
    context: Context
) : SubjectSegmenter {

    private val fastSegmenter = MlKitSubjectSegmenter()
    private val studioSegmenter = OnnxRmbgSegmenter(context)

    override suspend fun segment(bitmap: Bitmap, mode: SegmentationMode): Result<SegmentationResult> {
        return when (mode) {
            SegmentationMode.FAST -> fastSegmenter.segment(bitmap)
            SegmentationMode.STUDIO -> {
                if (!studioSegmenter.isModelDownloaded()) {
                    // Fallback to fast mode if model is not yet downloaded
                    fastSegmenter.segment(bitmap)
                } else {
                    studioSegmenter.segment(bitmap)
                }
            }
        }
    }

    override fun isStudioModelReady(): Boolean = studioSegmenter.isModelDownloaded()

    override suspend fun downloadStudioModel(onProgress: (Int) -> Unit): Result<File> {
        return studioSegmenter.downloadModel(onProgress)
    }

    override fun close() {
        fastSegmenter.close()
        studioSegmenter.close()
    }
}
