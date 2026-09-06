package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.model.AppUpdate
import com.cleancut.bgremover.domain.repository.UpdateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckForUpdateUseCaseTest {

    private class StubUpdateManager : UpdateManager {
        override suspend fun checkForUpdate(currentVersionName: String): Result<AppUpdate> {
            val update = AppUpdate(
                isUpdateAvailable = currentVersionName == "1.0.0",
                latestVersionName = "1.5.0",
                releaseNotes = "New features",
                apkDownloadUrl = "https://example.com/app.apk",
                apkSizeBytes = 1024L
            )
            return Result.success(update)
        }

        override suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): Result<File> =
            Result.success(File("app.apk"))

        override fun installApk(apkFile: File): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun testCheckForUpdateIdentifiesNewVersion() = runBlocking {
        val manager = StubUpdateManager()
        val useCase = CheckForUpdateUseCase(manager)

        val resultAvailable = useCase("1.0.0")
        assertTrue(resultAvailable.isSuccess)
        assertEquals(true, resultAvailable.getOrNull()?.isUpdateAvailable)
        assertEquals("1.5.0", resultAvailable.getOrNull()?.latestVersionName)

        val resultCurrent = useCase("1.5.0")
        assertTrue(resultCurrent.isSuccess)
        assertEquals(false, resultCurrent.getOrNull()?.isUpdateAvailable)
    }
}
