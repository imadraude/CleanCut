package com.cleancut.bgremover.data.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Ultra-precision on-device matting using BiRefNet-Lite via ONNX Runtime Mobile.
 * Uses Bilateral Reference Network architecture with Swin Transformer backbone.
 * Optimized with direct native buffers, ImageNet normalization LUTs, and fused bilinear compositing.
 */
class OnnxBiRefNetSegmenter(
    private val context: Context
) {
    private val modelUrl = "https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx"
    private val modelFile = File(File(context.filesDir, "models").apply { mkdirs() }, "birefnet_lite.onnx")

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Precalculated ImageNet normalization lookup tables:
    // R: mean=0.485, std=0.229; G: mean=0.456, std=0.224; B: mean=0.406, std=0.225
    private val rLut = FloatArray(256) { i -> (i / 255.0f - 0.485f) / 0.229f }
    private val gLut = FloatArray(256) { i -> (i / 255.0f - 0.456f) / 0.224f }
    private val bLut = FloatArray(256) { i -> (i / 255.0f - 0.406f) / 0.225f }

    // Reusable direct FloatBuffer for native zero-copy JNI tensor creation
    private var cachedDirectFloatBuffer: FloatBuffer? = null
    private var cachedRPlane: FloatArray? = null
    private var cachedGPlane: FloatArray? = null
    private var cachedBPlane: FloatArray? = null

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
            val inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)
            val tempFile = File(modelFile.parentFile, "birefnet_temp.onnx")
            val outputStream = BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024)

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

    @Synchronized
    private fun getOrCreateSession(): OrtSession {
        if (ortSession == null) {
            if (!isModelDownloaded()) {
                throw IllegalStateException("Модель BiRefNet ще не завантажена на пристрій.")
            }
            ortEnv = OrtEnvironment.getEnvironment()
            val availableCores = Runtime.getRuntime().availableProcessors()
            val optimalThreads = min(4, max(2, availableCores))
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(optimalThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    private fun getOrCreateDirectBuffer(targetDim: Int): FloatBuffer {
        val requiredCapacity = 1 * 3 * targetDim * targetDim
        val existing = cachedDirectFloatBuffer
        if (existing != null && existing.capacity() == requiredCapacity) {
            existing.clear()
            return existing
        }
        val directBuffer = ByteBuffer.allocateDirect(requiredCapacity * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        cachedDirectFloatBuffer = directBuffer
        return directBuffer
    }

    suspend fun segment(originalBitmap: Bitmap): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        try {
            val session = getOrCreateSession()
            val env = ortEnv!!

            val origWidth = originalBitmap.width
            val origHeight = originalBitmap.height
            val targetDim = 1024
            val planeSize = targetDim * targetDim

            var cutoutBitmap: Bitmap? = null
            var execTime = 0L

            execTime = measureTimeMillis {
                // 1. Resize input image to 1024x1024 for BiRefNet
                val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, targetDim, targetDim, true)

                // 2. Normalize and prepare NCHW direct FloatBuffer with ImageNet mean/std LUTs
                val inputPixels = IntArray(planeSize)
                resizedBitmap.getPixels(inputPixels, 0, targetDim, 0, 0, targetDim, targetDim)

                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }

                val rPlane = cachedRPlane ?: FloatArray(planeSize).also { cachedRPlane = it }
                val gPlane = cachedGPlane ?: FloatArray(planeSize).also { cachedGPlane = it }
                val bPlane = cachedBPlane ?: FloatArray(planeSize).also { cachedBPlane = it }

                for (i in 0 until planeSize) {
                    val px = inputPixels[i]
                    rPlane[i] = rLut[(px shr 16) and 0xFF]
                    gPlane[i] = gLut[(px shr 8) and 0xFF]
                    bPlane[i] = bLut[px and 0xFF]
                }

                val floatBuffer = getOrCreateDirectBuffer(targetDim)
                floatBuffer.put(rPlane)
                floatBuffer.put(gPlane)
                floatBuffer.put(bPlane)
                floatBuffer.flip()

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

                val mask1024 = FloatArray(planeSize)
                outputBuffer.get(mask1024)

                // Check if output is raw logits needing sigmoid activation
                var needsSigmoid = false
                val checkLimit = min(100, planeSize)
                for (i in 0 until checkLimit) {
                    if (mask1024[i] < -0.01f || mask1024[i] > 1.01f) {
                        needsSigmoid = true
                        break
                    }
                }
                if (needsSigmoid) {
                    for (i in 0 until planeSize) {
                        mask1024[i] = 1f / (1f + exp(-mask1024[i]))
                    }
                }

                // 5. Fused bilinear upsampling + transparent cutout compositing directly into resultPixels
                val resultPixels = IntArray(origWidth * origHeight)
                originalBitmap.getPixels(resultPixels, 0, origWidth, 0, 0, origWidth, origHeight)

                val xRatio = (targetDim - 1).toFloat() / max(1, origWidth - 1)
                val yRatio = (targetDim - 1).toFloat() / max(1, origHeight - 1)

                val xTable = IntArray(origWidth)
                val nextXTable = IntArray(origWidth)
                val xDiffTable = FloatArray(origWidth)
                for (x in 0 until origWidth) {
                    val sx = (x * xRatio).toInt().coerceIn(0, targetDim - 1)
                    xTable[x] = sx
                    nextXTable[x] = min(targetDim - 1, sx + 1)
                    xDiffTable[x] = ((x * xRatio) - sx).coerceIn(0f, 1f)
                }

                for (y in 0 until origHeight) {
                    val sy = (y * yRatio).toInt().coerceIn(0, targetDim - 1)
                    val nextSy = min(targetDim - 1, sy + 1)
                    val yDiff = ((y * yRatio) - sy).coerceIn(0f, 1f)
                    val invYDiff = 1f - yDiff

                    val rowOffsetDst = y * origWidth
                    val rowOffsetSrc = sy * targetDim
                    val nextRowOffsetSrc = nextSy * targetDim

                    for (x in 0 until origWidth) {
                        val sx = xTable[x]
                        val nextSx = nextXTable[x]
                        val xDiff = xDiffTable[x]
                        val invXDiff = 1f - xDiff

                        val a = mask1024[rowOffsetSrc + sx]
                        val b = mask1024[rowOffsetSrc + nextSx]
                        val c = mask1024[nextRowOffsetSrc + sx]
                        val d = mask1024[nextRowOffsetSrc + nextSx]

                        val alphaFloat = (a * invXDiff + b * xDiff) * invYDiff +
                                (c * invXDiff + d * xDiff) * yDiff
                        val alpha = (alphaFloat * 255f).toInt().coerceIn(0, 255)

                        val idx = rowOffsetDst + x
                        resultPixels[idx] = (alpha shl 24) or (resultPixels[idx] and 0x00FFFFFF)
                    }
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

    fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
        cachedDirectFloatBuffer = null
        cachedRPlane = null
        cachedGPlane = null
        cachedBPlane = null
    }
}
