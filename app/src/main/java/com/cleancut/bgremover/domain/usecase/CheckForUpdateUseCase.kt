package com.cleancut.bgremover.domain.usecase

import com.cleancut.bgremover.domain.model.AppUpdate
import com.cleancut.bgremover.domain.repository.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase to check if a new application release is available.
 */
class CheckForUpdateUseCase(
    private val updateManager: UpdateManager
) {
    suspend operator fun invoke(currentVersionName: String): Result<AppUpdate> = withContext(Dispatchers.IO) {
        updateManager.checkForUpdate(currentVersionName)
    }
}
