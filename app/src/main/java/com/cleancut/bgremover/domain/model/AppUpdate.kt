package com.cleancut.bgremover.domain.model

/**
 * Information regarding available application updates.
 */
data class AppUpdate(
    val latestVersionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val isUpdateAvailable: Boolean
)
