package com.cleancut.bgremover.domain.repository

import com.cleancut.bgremover.domain.model.AppUpdate
import java.io.File

/**
 * Deep module seam for checking, downloading, and installing in-app updates.
 */
interface UpdateManager {
    /**
     * Queries for newer version availability.
     */
    suspend fun checkForUpdate(currentVersionName: String): Result<AppUpdate>

    /**
     * Downloads the APK file to local cache with progress notification.
     */
    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): Result<File>

    /**
     * Triggers the system package installer intent for the downloaded APK.
     */
    fun installApk(apkFile: File): Result<Unit>
}
