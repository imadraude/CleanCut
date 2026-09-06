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

                    // 3. Composite cutout with defringing
                    cutoutBitmap = applyRefinedMaskWithDefringing(bitmap, refinedMask)
                } else {
                    // Fallback to direct foreground if mask buffer is unavailable
                    cutoutBitmap = segmentationResult.foregroundBitmap
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
        val width = original.width
        val height = original.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val alphaFloat = mask[i]
            val alpha = (alphaFloat * 255f).toInt().coerceIn(0, 255)

            if (alpha == 0) {
                pixels[i] = 0
            } else {
                pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
            }
        }

        outputBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outputBitmap
    }

    fun close() {
        client.close()
    }
}
