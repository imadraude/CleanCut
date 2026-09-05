package com.cleancut.bgremover.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import java.io.File

/**
 * Tri-tier hybrid implementation:
 * - FAST mode: Google ML Kit with Guided Filter edge refinement (0 MB, 50ms).
 * - STUDIO mode: Bria AI RMBG-1.4 via ONNX Runtime Mobile (~42 MB).
 * - ULTRA mode: BiRefNet-Lite via ONNX Runtime Mobile for flagship precision (~213 MB).
 */
class HybridSubjectSegmenter(
    context: Context
) : SubjectSegmenter {

    private val fastSegmenter = MlKitSubjectSegmenter()
    private val studioSegmenter = OnnxRmbgSegmenter(context)
    private val ultraSegmenter = OnnxBiRefNetSegmenter(context)

    override suspend fun segment(bitmap: Bitmap, mode: SegmentationMode): Result<SegmentationResult> {
        return when (mode) {
            SegmentationMode.FAST -> fastSegmenter.segment(bitmap)
            SegmentationMode.STUDIO -> {
                if (!studioSegmenter.isModelDownloaded()) {
                    fastSegmenter.segment(bitmap)
                } else {
                    studioSegmenter.segment(bitmap)
                }
            }
            SegmentationMode.ULTRA -> {
                if (!ultraSegmenter.isModelDownloaded()) {
                    // Fallback to studio or fast if ultra is not ready
                    if (studioSegmenter.isModelDownloaded()) {
                        studioSegmenter.segment(bitmap)
                    } else {
                        fastSegmenter.segment(bitmap)
                    }
                } else {
                    ultraSegmenter.segment(bitmap)
                }
            }
        }
    }

    override fun isModelReady(mode: SegmentationMode): Boolean {
        return when (mode) {
            SegmentationMode.FAST -> true
            SegmentationMode.STUDIO -> studioSegmenter.isModelDownloaded()
            SegmentationMode.ULTRA -> ultraSegmenter.isModelDownloaded()
        }
    }

    override suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> {
        return when (mode) {
            SegmentationMode.FAST -> Result.success(File(""))
            SegmentationMode.STUDIO -> studioSegmenter.downloadModel(onProgress)
            SegmentationMode.ULTRA -> ultraSegmenter.downloadModel(onProgress)
        }
    }

    override fun close() {
        fastSegmenter.close()
        studioSegmenter.close()
        ultraSegmenter.close()
    }
}
