package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.repository.UpdateManager
import java.io.File

/**
 * UseCase to trigger installation of a downloaded APK.
 */
class InstallApkUseCase(
    private val updateManager: UpdateManager
) {
    operator fun invoke(apkFile: File): Result<Unit> {
        return updateManager.installApk(apkFile)
    }
}
