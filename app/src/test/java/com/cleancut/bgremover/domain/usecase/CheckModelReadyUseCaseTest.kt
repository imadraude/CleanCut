package com.cleancut.bgremover.domain.usecase

import android.graphics.Bitmap
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.model.SegmentationResult
import com.cleancut.bgremover.domain.repository.SubjectSegmenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckModelReadyUseCaseTest {

    private class StubSegmenter(private val readyModes: Set<SegmentationMode>) : SubjectSegmenter {
        override suspend fun segment(bitmap: Bitmap, mode: SegmentationMode): Result<SegmentationResult> =
            Result.failure(NotImplementedError())
        override fun isModelReady(mode: SegmentationMode): Boolean = mode in readyModes
        override suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> =
            Result.failure(NotImplementedError())
        override fun close() {}
    }

    @Test
    fun testIsModelReadyReturnsTrueWhenCached() {
        val segmenter = StubSegmenter(setOf(SegmentationMode.FAST, SegmentationMode.STUDIO))
        val useCase = CheckModelReadyUseCase(segmenter)

        assertTrue(useCase(SegmentationMode.FAST))
        assertTrue(useCase(SegmentationMode.STUDIO))
        assertFalse(useCase(SegmentationMode.ULTRA))
        assertFalse(useCase(SegmentationMode.ANIME))
    }
}
