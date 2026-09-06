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
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * High-performance on-device SOTA matting using Bria AI RMBG-1.4 via ONNX Runtime Mobile.
 * Optimized with direct native buffers, lookup-table preprocessing, and fused bilinear compositing.
 */
class OnnxRmbgSegmenter(
    private val context: Context
) {
    private val modelUrl = "https://huggingface.co/briaai/RMBG-1.4/resolve/main/onnx/model_quantized.onnx"
    private val modelFile = File(File(context.filesDir, "models").apply { mkdirs() }, "rmbg_1.4_quantized.onnx")

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Precalculated normalization lookup table: (pixelVal / 255.0f - 0.5f) / 0.5f
    private val normLut = FloatArray(256) { i -> (i / 255.0f - 0.5f) / 0.5f }

    // Reusable direct FloatBuffer for native zero-copy JNI tensor creation
    private var cachedDirectFloatBuffer: FloatBuffer? = null
    private var cachedRPlane: FloatArray? = null
    private var cachedGPlane: FloatArray? = null
    private var cachedBPlane: FloatArray? = null

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 40_000_000L

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
                    throw IllegalStateException("Помилка завантаження моделі: HTTP $status")
                }
                break
            }

            val fileLength = connection.contentLength
            val inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)
            val tempFile = File(modelFile.parentFile, "rmbg_temp.onnx")
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
                throw IllegalStateException("Модель RMBG ще не завантажена на пристрій.")
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
                // 1. Aspect-ratio preserving letterboxing (prevents geometric squashing)
                val scale = targetDim.toFloat() / max(origWidth, origHeight)
                val scaledWidth = (origWidth * scale).toInt().coerceIn(1, targetDim)
                val scaledHeight = (origHeight * scale).toInt().coerceIn(1, targetDim)
                val padX = (targetDim - scaledWidth) / 2
                val padY = (targetDim - scaledHeight) / 2

                val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
                val scaledPixels = IntArray(scaledWidth * scaledHeight)
                resizedBitmap.getPixels(scaledPixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }

                val rPlane = cachedRPlane ?: FloatArray(planeSize).also { cachedRPlane = it }
                val gPlane = cachedGPlane ?: FloatArray(planeSize).also { cachedGPlane = it }
                val bPlane = cachedBPlane ?: FloatArray(planeSize).also { cachedBPlane = it }

                // Neutral padding (0.0f in normalized space)
                rPlane.fill(0f)
                gPlane.fill(0f)
                bPlane.fill(0f)

                for (y in 0 until scaledHeight) {
                    val srcRowOffset = y * scaledWidth
                    val dstRowOffset = (padY + y) * targetDim + padX
                    for (x in 0 until scaledWidth) {
                        val px = scaledPixels[srcRowOffset + x]
                        rPlane[dstRowOffset + x] = normLut[(px shr 16) and 0xFF]
                        gPlane[dstRowOffset + x] = normLut[(px shr 8) and 0xFF]
                        bPlane[dstRowOffset + x] = normLut[px and 0xFF]
                    }
                }

                val floatBuffer = getOrCreateDirectBuffer(targetDim)
                floatBuffer.put(rPlane)
                floatBuffer.put(gPlane)
                floatBuffer.put(bPlane)
                floatBuffer.flip()

                // 2. Create zero-copy input tensor and execute session
                val inputTensor = OnnxTensor.createTensor(
                    env,
                    floatBuffer,
                    longArrayOf(1, 3, targetDim.toLong(), targetDim.toLong())
                )

                val inputName = session.inputNames.iterator().next()
                val results = session.run(Collections.singletonMap(inputName, inputTensor))

                // 3. Extract output probability mask [1, 1, 1024, 1024]
                val outputTensor = results.get(0) as OnnxTensor
                val outputBuffer = outputTensor.floatBuffer
                outputBuffer.rewind()

                val mask1024 = FloatArray(planeSize)
                outputBuffer.get(mask1024)

                // 4. Unpad and resample mask from active letterbox region to native dimensions
                val rawMask = FloatArray(origWidth * origHeight)
                val xRatio = (scaledWidth - 1).toFloat() / max(1, origWidth - 1)
                val yRatio = (scaledHeight - 1).toFloat() / max(1, origHeight - 1)

                val xTable = IntArray(origWidth)
                val nextXTable = IntArray(origWidth)
                val xDiffTable = FloatArray(origWidth)
                for (x in 0 until origWidth) {
                    val sx = (x * xRatio).toInt().coerceIn(0, scaledWidth - 1)
                    xTable[x] = padX + sx
                    nextXTable[x] = padX + min(scaledWidth - 1, sx + 1)
                    xDiffTable[x] = ((x * xRatio) - sx).coerceIn(0f, 1f)
                }

                for (y in 0 until origHeight) {
                    val sy = (y * yRatio).toInt().coerceIn(0, scaledHeight - 1)
                    val nextSy = min(scaledHeight - 1, sy + 1)
                    val yDiff = ((y * yRatio) - sy).coerceIn(0f, 1f)
                    val invYDiff = 1f - yDiff

                    val rowOffsetDst = y * origWidth
                    val rowOffsetSrc = (padY + sy) * targetDim
                    val nextRowOffsetSrc = (padY + nextSy) * targetDim

                    for (x in 0 until origWidth) {
                        val sx = xTable[x]
                        val nextSx = nextXTable[x]
                        val xDiff = xDiffTable[x]
                        val invXDiff = 1f - xDiff

                        val a = mask1024[rowOffsetSrc + sx]
                        val b = mask1024[rowOffsetSrc + nextSx]
                        val c = mask1024[nextRowOffsetSrc + sx]
                        val d = mask1024[nextRowOffsetSrc + nextSx]

                        val alphaVal = (a * invXDiff + b * xDiff) * invYDiff +
                                (c * invXDiff + d * xDiff) * yDiff
                        rawMask[rowOffsetDst + x] = alphaVal.coerceIn(0f, 1f)
                    }
                }

                // 5. Edge refinement via Fast Guided Filter with full-res guidance image
                val refinedMask = GuidedFilter.filter(
                    original = originalBitmap,
                    inputMask = rawMask,
                    radius = 4,
                    eps = 1e-3f
                )

                // 6. Defringing and cutout compositing
                val pixels = IntArray(origWidth * origHeight)
                originalBitmap.getPixels(pixels, 0, origWidth, 0, 0, origWidth, origHeight)

                for (i in pixels.indices) {
                    var alpha = refinedMask[i]
                    alpha = when {
                        alpha < 0.05f -> 0f
                        alpha > 0.95f -> 1f
                        else -> smoothstep(0.05f, 0.95f, alpha)
                    }

                    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
                    if (alphaInt == 0) {
                        pixels[i] = 0
                    } else {
                        pixels[i] = (alphaInt shl 24) or (pixels[i] and 0x00FFFFFF)
                    }
                }

                val outBitmap = Bitmap.createBitmap(origWidth, origHeight, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(pixels, 0, origWidth, 0, 0, origWidth, origHeight)
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

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
