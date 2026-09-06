package com.cleancut.bgremover.domain.usecase

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SegmentImageUseCaseTest {

    private class FakeSubjectSegmenter : SubjectSegmenter {
        var lastModeRequested: SegmentationMode? = null
        var shouldFail: Boolean = false

        override suspend fun segment(bitmap: Bitmap, mode: SegmentationMode): Result<SegmentationResult> {
            lastModeRequested = mode
            return if (shouldFail) {
                Result.failure(RuntimeException("Segmentation failure"))
            } else {
                Result.success(SegmentationResult(bitmap, bitmap, 42L))
            }
        }

        override fun isModelReady(mode: SegmentationMode): Boolean = true
        override suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> =
            Result.success(File("fake_model.onnx"))
        override fun close() {}
    }

    @Test
    fun testInvokeDelegatesToSegmenterWithGivenMode() = runBlocking {
        val fakeSegmenter = FakeSubjectSegmenter()
        val useCase = SegmentImageUseCase(fakeSegmenter)

        val dummyBitmap = createDummyBitmap()
        val result = useCase(dummyBitmap, SegmentationMode.STUDIO)

        assertTrue(result.isSuccess)
        assertEquals(SegmentationMode.STUDIO, fakeSegmenter.lastModeRequested)
        assertEquals(42L, result.getOrNull()?.processingTimeMs)
    }

    @Test
    fun testInvokePropagatesFailure() = runBlocking {
        val fakeSegmenter = FakeSubjectSegmenter().apply { shouldFail = true }
        val useCase = SegmentImageUseCase(fakeSegmenter)

        val dummyBitmap = createDummyBitmap()
        val result = useCase(dummyBitmap, SegmentationMode.FAST)

        assertTrue(result.isFailure)
        assertEquals("Segmentation failure", result.exceptionOrNull()?.message)
    }

    private fun createDummyBitmap(): Bitmap {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            allocateMethod.invoke(unsafe, Bitmap::class.java) as Bitmap
        } catch (_: Exception) {
            val constructor = Bitmap::class.java.declaredConstructors.first()
            constructor.isAccessible = true
            val args = arrayOfNulls<Any>(constructor.parameterTypes.size)
            constructor.newInstance(*args) as Bitmap
        }
    }
}
