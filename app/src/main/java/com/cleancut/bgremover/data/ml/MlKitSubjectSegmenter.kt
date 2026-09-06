package com.cleancut.bgremover.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Enhanced ML Kit Subject Segmenter combining Google ML Kit with
 * Guided Filter edge refinement and color defringing for crisp boundaries.
 */
class MlKitSubjectSegmenter {

    private val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .enableForegroundConfidenceMask()
        .build()

    private val client = SubjectSegmentation.getClient(options)

    suspend fun segment(bitmap: Bitmap): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            var cutoutBitmap: Bitmap? = null

            val executionTime = measureTimeMillis {
                val segmentationResult = client.process(inputImage).await()
                val confidenceMask = segmentationResult.foregroundConfidenceMask

                if (confidenceMask != null) {
                    // 1. Extract raw mask buffer
                    confidenceMask.rewind()
                    val maskWidth = bitmap.width
                    val maskHeight = bitmap.height
                    val rawMask = FloatArray(maskWidth * maskHeight)
                    confidenceMask.get(rawMask)

                    // 2. Edge Refinement via Guided Filter
                    val refinedMask = GuidedFilter.filter(
                        original = bitmap,
                        inputMask = rawMask,
                        radius = 6,
                        eps = 1e-3f
                    )

                    // 3. Composite cutout with industrial-grade defringing & color decontamination
                    cutoutBitmap = applyRefinedMaskWithDefringing(bitmap, refinedMask)
                } else {
                    // Fallback to direct foreground with defringing if raw mask buffer is unavailable
                    val fgBmp = segmentationResult.foregroundBitmap
                    if (fgBmp != null) {
                        val w = fgBmp.width
                        val h = fgBmp.height
                        val fgPixels = IntArray(w * h)
                        fgBmp.getPixels(fgPixels, 0, w, 0, 0, w, h)
                        val extractedMask = FloatArray(w * h)
                        for (idx in fgPixels.indices) {
                            extractedMask[idx] = ((fgPixels[idx] ushr 24) and 0xFF) / 255f
                        }
                        cutoutBitmap = MatteDefringer.createCutout(bitmap, extractedMask, w, h)
                    }
                }
            }

            val finalCutout = cutoutBitmap ?: throw IllegalStateException(
                "Не вдалося виділити об'єкт на фотографії."
            )

            Result.success(
                SegmentationResult(
                    originalBitmap = bitmap,
                    foregroundCutout = finalCutout,
                    processingTimeMs = executionTime
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Applies refined mask and suppresses background color bleed along edges (defringing).
     */
    private fun applyRefinedMaskWithDefringing(original: Bitmap, mask: FloatArray): Bitmap {
        return MatteDefringer.createCutout(
            original = original,
            mask = mask,
            width = original.width,
            height = original.height
        )
    }

    fun close() {
        client.close()
    }
}
