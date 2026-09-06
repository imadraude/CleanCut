package com.cleancut.bgremover.data.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Specialized on-device matting for 2D anime, manga, and digital illustrations
 * using the SkyTNT IS-Net Anime model via ONNX Runtime Mobile (~168 MB).
 *
 * Trained specifically on anime artwork to produce razor-sharp line-art boundaries
 * without photographic transition halos.
 */
class OnnxIsnetAnimeSegmenter(
    private val context: Context
) : AutoCloseable {

    private val modelUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx"
    private val modelFile = File(File(context.filesDir, "models").apply { mkdirs() }, "isnet_anime.onnx")

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Precomputed normalization LUTs for mean=(0.485, 0.456, 0.406), std=(1.0, 1.0, 1.0)
    private val rLut = FloatArray(256) { (it / 255.0f) - 0.485f }
    private val gLut = FloatArray(256) { (it / 255.0f) - 0.456f }
    private val bLut = FloatArray(256) { (it / 255.0f) - 0.406f }

    private var cachedDirectBuffer: FloatBuffer? = null
    private var cachedBufferCapacity: Int = 0

    fun isModelDownloaded(): Boolean {
        return modelFile.exists() && modelFile.length() > 50L * 1024L * 1024L
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            onProgress(100)
            return@withContext Result.success(modelFile)
        }

        try {
            var currentUrl = modelUrl
            var connection: HttpURLConnection
            var redirects = 0
            val maxRedirects = 5

            // Follow HTTP 301/302 redirects automatically (GitHub Releases -> S3/CDN)
            while (true) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val newUrl = connection.getHeaderField("Location")
                        ?: return@withContext Result.failure(Exception("Redirect with missing Location header"))
                    connection.disconnect()
                    currentUrl = newUrl
                    redirects++
                    if (redirects > maxRedirects) {
                        return@withContext Result.failure(Exception("Too many HTTP redirects ($redirects)"))
                    }
                } else if (status == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    return@withContext Result.failure(Exception("HTTP error $status when downloading IS-Net Anime model"))
                }
            }

            val totalBytes = connection.contentLength.toLong()
            val tempFile = File(modelFile.parentFile, "isnet_anime_temp.onnx")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var downloadedBytes = 0L
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastReportedPercent) {
                                lastReportedPercent = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            if (tempFile.length() < 50L * 1024L * 1024L) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Downloaded IS-Net Anime model file is corrupt or truncated"))
            }

            if (modelFile.exists()) {
                modelFile.delete()
            }
            if (!tempFile.renameTo(modelFile)) {
                return@withContext Result.failure(Exception("Failed to save IS-Net Anime model file"))
            }

            onProgress(100)
            Result.success(modelFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    private fun initSession(): Pair<OrtEnvironment, OrtSession> {
        if (!isModelDownloaded()) {
            throw IllegalStateException("IS-Net Anime model is not downloaded yet")
        }

        ortSession?.let { session ->
            ortEnv?.let { env ->
                return Pair(env, session)
            }
        }

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        ortEnv = env
        ortSession = session
        return Pair(env, session)
    }

    private fun getOrCreateDirectBuffer(dim: Int): FloatBuffer {
        val requiredCapacity = 3 * dim * dim
        val existing = cachedDirectBuffer
        if (existing != null && cachedBufferCapacity >= requiredCapacity) {
            existing.clear()
            return existing
        }

        val byteBuffer = ByteBuffer.allocateDirect(requiredCapacity * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        cachedDirectBuffer = floatBuffer
        cachedBufferCapacity = requiredCapacity
        return floatBuffer
    }

    suspend fun segment(originalBitmap: Bitmap): Result<SegmentationResult> = withContext(Dispatchers.Default) {
        try {
            val (env, session) = initSession()

            val origWidth = originalBitmap.width
            val origHeight = originalBitmap.height
            val targetDim = 1024
            val planeSize = targetDim * targetDim

            var cutoutBitmap: Bitmap? = null

            val execTime = measureTimeMillis {
                // 1. Direct resize to standard 1024x1024 input
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetDim, targetDim, true)
                val scaledPixels = IntArray(planeSize)
                scaledBitmap.getPixels(scaledPixels, 0, targetDim, 0, 0, targetDim, targetDim)
                if (scaledBitmap !== originalBitmap) {
                    scaledBitmap.recycle()
                }

                // 2. Normalize and construct planar FloatBuffer
                val rPlane = FloatArray(planeSize)
                val gPlane = FloatArray(planeSize)
                val bPlane = FloatArray(planeSize)

                for (i in 0 until planeSize) {
                    val px = scaledPixels[i]
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

                // 5. Min-Max normalization standard for IS-Net architectures
                var minVal = Float.MAX_VALUE
                var maxVal = -Float.MAX_VALUE
                for (i in 0 until planeSize) {
                    val v = mask1024[i]
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                }

                val range = maxVal - minVal
                val invRange = if (range > 1e-6f) 1f / range else 1f
                for (i in 0 until planeSize) {
                    mask1024[i] = ((mask1024[i] - minVal) * invRange).coerceIn(0f, 1f)
                }

                // 6. Direct bilinear interpolation from 1024x1024 mask to native dimensions
                val rawMask = FloatArray(origWidth * origHeight)
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

                        val alphaVal = (a * invXDiff + b * xDiff) * invYDiff +
                                (c * invXDiff + d * xDiff) * yDiff
                        rawMask[rowOffsetDst + x] = alphaVal.coerceIn(0f, 1f)
                    }
                }

                // 7. Natural alpha compositing with MatteDefringer for pristine halo-free edges
                cutoutBitmap = MatteDefringer.createCutout(
                    original = originalBitmap,
                    mask = rawMask,
                    width = origWidth,
                    height = origHeight
                )

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

    override fun close() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
            cachedDirectBuffer = null
            cachedBufferCapacity = 0
        } catch (_: Exception) {
        }
    }
}
