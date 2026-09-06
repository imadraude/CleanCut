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

class DownloadModelUseCaseTest {

    private class StubSegmenter : SubjectSegmenter {
        var downloadedMode: SegmentationMode? = null
        var lastProgress: Int = 0

        override suspend fun segment(bitmap: Bitmap, mode: SegmentationMode): Result<SegmentationResult> =
            Result.failure(NotImplementedError())
        override fun isModelReady(mode: SegmentationMode): Boolean = false

        override suspend fun downloadModel(mode: SegmentationMode, onProgress: (Int) -> Unit): Result<File> {
            downloadedMode = mode
            onProgress(50)
            onProgress(100)
            lastProgress = 100
            return Result.success(File("downloaded_${mode.name.lowercase()}.onnx"))
        }

        override fun close() {}
    }

    @Test
    fun testDownloadModelExecutesAndReportsProgress() = runBlocking {
        val stub = StubSegmenter()
        val useCase = DownloadModelUseCase(stub)

        val progressList = mutableListOf<Int>()
        val result = useCase(SegmentationMode.ULTRA) { progress ->
            progressList.add(progress)
        }

        assertTrue(result.isSuccess)
        assertEquals(SegmentationMode.ULTRA, stub.downloadedMode)
        assertEquals(listOf(50, 100), progressList)
    }
}
