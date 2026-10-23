package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val platform: String,
    val sourceUrl: String,
    val localFilePath: String,
    val mediaUri: String,
    val fileSize: Long,
    val formattedSize: String,
    val mimeType: String = "video/mp4",
    val timestamp: Long = System.currentTimeMillis(),
    val thumbnailUri: String? = null
)
