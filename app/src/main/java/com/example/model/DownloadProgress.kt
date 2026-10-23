package com.example.model

data class DownloadProgress(
    val url: String = "",
    val fileName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val progressPercent: Int = 0,
    val speedText: String = "",
    val isDownloading: Boolean = false,
    val isSuccess: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String = "",
    val savedPath: String? = null,
    val batchCurrent: Int = 0,
    val batchTotal: Int = 0,
    val statusMessage: String = ""
)
