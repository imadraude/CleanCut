package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.repository.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UseCase to download application APK for updates.
 */
class DownloadApkUseCase(
    private val updateManager: UpdateManager
) {
    suspend operator fun invoke(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        updateManager.downloadApk(downloadUrl, onProgress)
    }
}
