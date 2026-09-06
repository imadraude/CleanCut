package com.cleancut.bgremover.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import java.io.File

/**
 * Multi-tier hybrid implementation:
 * - FAST mode: Google ML Kit with Guided Filter edge refinement (0 MB, 50ms).
 * - STUDIO mode: Bria AI RMBG-1.4 via ONNX Runtime Mobile (~42 MB).
 * - ULTRA mode: BiRefNet-Lite via ONNX Runtime Mobile for flagship precision (~224 MB).
 * - ANIME mode: IS-Net Anime via ONNX Runtime Mobile for 2D illustration precision (~168 MB).
 */
class HybridSubjectSegmenter(
    context: Context
) : SubjectSegmenter {

    private val fastSegmenter by lazy { MlKitSubjectSegmenter() }
    private val studioSegmenter by lazy { OnnxRmbgSegmenter(context) }
    private val ultraSegmenter by lazy { OnnxBiRefNetSegmenter(context) }
    private val animeSegmenter by lazy { OnnxIsnetAnimeSegmenter(context) }

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
            SegmentationMode.ANIME -> {
                if (!animeSegmenter.isModelDownloaded()) {
                    // Fallback to ultra, studio, or fast
                    if (ultraSegmenter.isModelDownloaded()) {
                        ultraSegmenter.segment(bitmap)
                    } else if (studioSegmenter.isModelDownloaded()) {
                        studioSegmenter.segment(bitmap)
                    } else {
                        fastSegmenter.segment(bitmap)
                    }
                } else {
                    animeSegmenter.segment(bitmap)
                }
            }
        }
    }

    override fun isModelReady(mode: SegmentationMode): Boolean {
        return when (mode) {
            SegmentationMode.FAST -> true
            SegmentationMode.STUDIO -> studioSegmenter.isModelDownloaded()
            SegmentationMode.ULTRA -> ultraSegmenter.isModelDownloaded()
            SegmentationMode.ANIME -> animeSegmenter.isModelDownloaded()
        }
    }

    override suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> {
        return when (mode) {
            SegmentationMode.FAST -> Result.success(File(""))
            SegmentationMode.STUDIO -> studioSegmenter.downloadModel(onProgress)
            SegmentationMode.ULTRA -> ultraSegmenter.downloadModel(onProgress)
            SegmentationMode.ANIME -> animeSegmenter.downloadModel(onProgress)
        }
    }

    override fun close() {
        fastSegmenter.close()
        studioSegmenter.close()
        ultraSegmenter.close()
        animeSegmenter.close()
    }
}
