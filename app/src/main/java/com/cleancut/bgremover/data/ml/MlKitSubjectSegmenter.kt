package com.cleancut.bgremover.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.FloatBuffer
import kotlin.system.measureTimeMillis

/**
 * Adapter satisfying the SubjectSegmenter seam using Google ML Kit Subject Segmentation API.
 * Runs completely on-device with hardware acceleration (GPU/NPU).
 */
class MlKitSubjectSegmenter : SubjectSegmenter {

    private val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .enableForegroundConfidenceMask()
        .build()

    private val client = SubjectSegmentation.getClient(options)

    override suspend fun segment(bitmap: Bitmap): Result<SegmentationResult> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            var cutoutBitmap: Bitmap? = null

            val executionTime = measureTimeMillis {
                val segmentationResult = client.process(inputImage).await()

                // Preferred fast-path: hardware-generated foreground bitmap
                val directForeground = segmentationResult.foregroundBitmap
                if (directForeground != null) {
                    cutoutBitmap = directForeground
                } else {
                    // Fallback path: composite using confidence mask FloatBuffer
                    val confidenceMask = segmentationResult.foregroundConfidenceMask
                    if (confidenceMask != null) {
                        cutoutBitmap = applyConfidenceMask(bitmap, confidenceMask)
                    }
                }
            }

            val finalCutout = cutoutBitmap ?: throw IllegalStateException(
                "Не вдалося виділити об'єкт на фотографії. Спробуйте інше зображення з чіткішим переднім планом."
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
     * Composites an ARGB_8888 bitmap by applying the float confidence mask to alpha channels.
     */
    private fun applyConfidenceMask(original: Bitmap, maskBuffer: FloatBuffer): Bitmap {
        val width = original.width
        val height = original.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        maskBuffer.rewind()
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        val outputPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val confidence = if (maskBuffer.hasRemaining()) maskBuffer.get() else 0f
            val alpha = (confidence * 255).toInt().coerceIn(0, 255)
            val color = pixels[i]
            outputPixels[i] = Color.argb(
                alpha,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
        }

        outputBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return outputBitmap
    }

    override fun close() {
        client.close()
    }
}
