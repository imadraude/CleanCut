package com.cleancut.bgremover.data.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.cleancut.bgremover.domain.model.SegmentationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Ultra-precision on-device matting using BiRefNet-Lite via ONNX Runtime Mobile.
 * Uses Bilateral Reference Network architecture with Swin Transformer backbone.
 */
class OnnxBiRefNetSegmenter(
    private val context: Context
) {
    private val modelUrl = "https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx"
    private val modelFile = File(File(context.filesDir, "models").apply { mkdirs() }, "birefnet_lite.onnx")

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 200_000_000L

    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        try {
            var currentUrl = modelUrl
            var connection: HttpURLConnection
            var redirects = 0

            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "CleanCut-Android-App")
                    connectTimeout = 15000
                    readTimeout = 30000
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER
                ) {
                    currentUrl = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Помилка перенаправлення завантаження моделі")
                    redirects++
                    if (redirects > 5) throw IllegalStateException("Забагато перенаправлень")
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Помилка завантаження моделі BiRefNet: HTTP $status")
                }
                break
            }

            val fileLength = connection.contentLength
            val inputStream: InputStream = connection.inputStream
            val tempFile = File(modelFile.parentFile, "birefnet_temp.onnx")
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(64 * 1024)
            var totalRead: Long = 0
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (fileLength > 0) {
                    val progress = ((totalRead * 100) / fileLength).toInt()
                    onProgress(progress.coerceIn(0, 100))
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.renameTo(modelFile)) {
                Result.success(modelFile)
            } else {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
                Result.success(modelFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getOrCreateSession(): OrtSession {
        if (ortSession == null) {
            if (!isModelDownloaded()) {
                throw IllegalStateException("Модель BiRefNet ще не завантажена на пристрій.")
            }
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    suspend fun segment(originalBitmap: Bitmap): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        try {
            val session = getOrCreateSession()
            val env = ortEnv!!

            val origWidth = originalBitmap.width
            val origHeight = originalBitmap.height
            val targetDim = 1024

            var cutoutBitmap: Bitmap? = null
            var execTime = 0L

            execTime = measureTimeMillis {
                // 1. Resize input image to 1024x1024 for BiRefNet
                val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, targetDim, targetDim, true)

                // 2. Normalize and prepare NCHW FloatBuffer with ImageNet mean/std
                val inputPixels = IntArray(targetDim * targetDim)
                resizedBitmap.getPixels(inputPixels, 0, targetDim, 0, 0, targetDim, targetDim)

                val floatBuffer = FloatBuffer.allocate(1 * 3 * targetDim * targetDim)
                val planeSize = targetDim * targetDim

                // Channel R: mean=0.485, std=0.229
                for (i in 0 until planeSize) {
                    val r = (inputPixels[i] shr 16 and 0xFF) / 255.0f
                    floatBuffer.put(i, (r - 0.485f) / 0.229f)
                }
                // Channel G: mean=0.456, std=0.224
                for (i in 0 until planeSize) {
                    val g = (inputPixels[i] shr 8 and 0xFF) / 255.0f
                    floatBuffer.put(planeSize + i, (g - 0.456f) / 0.224f)
                }
                // Channel B: mean=0.406, std=0.225
                for (i in 0 until planeSize) {
                    val b = (inputPixels[i] and 0xFF) / 255.0f
                    floatBuffer.put(2 * planeSize + i, (b - 0.406f) / 0.225f)
                }
                floatBuffer.rewind()

                // 3. Execute inference
                val inputTensor = OnnxTensor.createTensor(
                    env,
                    floatBuffer,
                    longArrayOf(1, 3, targetDim.toLong(), targetDim.toLong())
                )

                val inputName = session.inputNames.iterator().next()
                val results = session.run(Collections.singletonMap(inputName, inputTensor))

                // 4. Extract output probability mask [1, 1, 1024, 1024]
                val outputTensor = results.get(0) as OnnxTensor
                val outputBuffer = outputTensor.floatBuffer
                outputBuffer.rewind()

                val mask1024 = FloatArray(targetDim * targetDim)
                for (i in 0 until targetDim * targetDim) {
                    val rawVal = outputBuffer.get()
                    // Apply sigmoid in case output is raw logits
                    mask1024[i] = if (rawVal in 0f..1f) rawVal else (1f / (1f + exp(-rawVal)))
                }

                // 5. Bilinear upsample mask to original dimensions
                val fullResMask = resampleMask(mask1024, targetDim, targetDim, origWidth, origHeight)

                // 6. Composite transparent cutout
                val originalPixels = IntArray(origWidth * origHeight)
                originalBitmap.getPixels(originalPixels, 0, origWidth, 0, 0, origWidth, origHeight)

                val resultPixels = IntArray(origWidth * origHeight)
                for (i in originalPixels.indices) {
                    val alpha = (fullResMask[i] * 255f).toInt().coerceIn(0, 255)
                    val rgb = originalPixels[i] and 0x00FFFFFF
                    resultPixels[i] = (alpha shl 24) or rgb
                }

                val outBitmap = Bitmap.createBitmap(origWidth, origHeight, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, origWidth, 0, 0, origWidth, origHeight)
                cutoutBitmap = outBitmap

                inputTensor.close()
                results.close()
            }

            Result.success(
                SegmentationResult(
                    originalBitmap = originalBitmap,
                    foregroundCutout = cutoutBitmap!!,
                    processingTimeMs = execTime
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resampleMask(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val xRatio = (srcW - 1).toFloat() / dstW
        val yRatio = (srcH - 1).toFloat() / dstH

        for (y in 0 until dstH) {
            val srcY = (y * yRatio).toInt()
            val yDiff = (y * yRatio) - srcY
            val rowOffsetDst = y * dstW
            val rowOffsetSrc = srcY * srcW
            val nextRowOffsetSrc = min(srcH - 1, srcY + 1) * srcW

            for (x in 0 until dstW) {
                val srcX = (x * xRatio).toInt()
                val xDiff = (x * xRatio) - srcX

                val a = src[rowOffsetSrc + srcX]
                val b = src[rowOffsetSrc + min(srcW - 1, srcX + 1)]
                val c = src[nextRowOffsetSrc + srcX]
                val d = src[nextRowOffsetSrc + min(srcW - 1, srcX + 1)]

                val value = a * (1 - xDiff) * (1 - yDiff) +
                        b * xDiff * (1 - yDiff) +
                        c * yDiff * (1 - xDiff) +
                        d * xDiff * yDiff

                dst[rowOffsetDst + x] = value.coerceIn(0f, 1f)
            }
        }
        return dst
    }

    fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }
}
