package com.example.model

import androidx.annotation.Keep

@Keep
data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val apkDownloadUrl: String = "",
    val releasePageUrl: String = "",
    val fileSize: Long = 0L
)

