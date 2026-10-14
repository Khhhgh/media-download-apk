package com.example.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.CarouselItem
import com.example.model.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class FileDownloadService(private val context: Context) {
    companion object {
        // Monotonic counter guarantees collision-free file names even when many slides
        // are written within the same millisecond.
        private val DOWNLOAD_COUNTER = AtomicLong(0L)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadMedia(
        downloadUrl: String,
        title: String,
        format: String = "mp4",
        onProgress: (DownloadProgress) -> Unit
    ): Pair<File?, Uri?> = withContext(Dispatchers.IO) {
        val sanitizedTitle = sanitizeFilename(title)
        val uniqueSuffix = "${System.currentTimeMillis()}_${DOWNLOAD_COUNTER.incrementAndGet()}"
        val fileName = "MediaDownload_${sanitizedTitle}_$uniqueSuffix.$format"
        val isAudio = format == "mp3"
        val isImage = format in listOf("jpg", "jpeg", "png", "webp")
        val mimeType = when {
            isAudio -> "audio/mpeg"
            format == "png" -> "image/png"
            format == "webp" -> "image/webp"
            isImage -> "image/jpeg"
            else -> "video/mp4"
        }

        try {
            val isInstagram = downloadUrl.contains("cdninstagram.com", ignoreCase = true) ||
                    downloadUrl.contains("fbcdn.net", ignoreCase = true) ||
                    downloadUrl.contains("instagram.com", ignoreCase = true)

            val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            fun buildDownloadRequest(ua: String, referer: String?): Request {
                val bld = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                if (!referer.isNullOrBlank()) {
                    bld.header("Referer", referer)
                }
                if (isInstagram) {
                    bld.header("Origin", "https://www.instagram.com")
                    bld.header("Sec-Fetch-Dest", "video")
                    bld.header("Sec-Fetch-Mode", "no-cors")
                    bld.header("Sec-Fetch-Site", "cross-site")
                }
                return bld.build()
            }

            // Primary attempt: Mobile Android User-Agent + Instagram Referer (Required by Facebook CDN)
            var request = buildDownloadRequest(
                ua = mobileUserAgent,
                referer = if (isInstagram) "https://www.instagram.com/" else getRefererForUrl(downloadUrl)
            )

            var response = client.newCall(request).execute()

            // If 403 Forbidden is encountered, retry with fallback configurations
            if (response.code == 403) {
                response.close()
                Log.w("FileDownloadService", "Received HTTP 403 on primary download, retrying with fallback headers...")
                request = buildDownloadRequest(
                    ua = UNIVERSAL_USER_AGENT,
                    referer = "https://www.instagram.com/"
                )
                response = client.newCall(request).execute()

                if (response.code == 403) {
                    response.close()
                    request = buildDownloadRequest(
                        ua = mobileUserAgent,
                        referer = null
                    )
                    response = client.newCall(request).execute()
                }
            }

            if (!response.isSuccessful) {
                throw IOException("Download failed with HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()
            val inputStream: InputStream = body.byteStream()

            var savedFile: File? = null
            var savedUri: Uri? = null
            var outputStream: OutputStream? = null

            val relativeFolder = when {
                isAudio -> Environment.DIRECTORY_MUSIC + "/MediaDownload"
                isImage -> Environment.DIRECTORY_PICTURES + "/MediaDownload"
                else -> Environment.DIRECTORY_MOVIES + "/MediaDownload"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore API (Scoped Storage Android 10+)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collection = when {
                    isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                savedUri = context.contentResolver.insert(collection, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry")
                outputStream = context.contentResolver.openOutputStream(savedUri)
            } else {
                // Legacy storage for older devices
                val baseDir = when {
                    isAudio -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    isImage -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                }
                val dir = File(baseDir, "MediaDownload")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)
                savedFile = targetFile
                outputStream = FileOutputStream(targetFile)
            }

            outputStream = outputStream ?: throw IOException("Could not open output stream")

            val buffer = ByteArray(8 * 1024)
            var bytesCopied = 0L
            var read: Int
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            outputStream.use { out ->
                inputStream.use { inStream ->
                    while (inStream.read(buffer).also { read = it } != -1) {
                        if (!coroutineContext.isActive) {
                            throw IOException("Download cancelled by user")
                        }
                        out.write(buffer, 0, read)
                        bytesCopied += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300 || bytesCopied == totalBytes) {
                            lastUpdate = now
                            val elapsedSec = (now - startTime) / 1000.0
                            val speed = if (elapsedSec > 0) bytesCopied / elapsedSec else 0.0
                            val speedText = formatSpeed(speed)
                            val percent = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt() else 0

                            onProgress(
                                DownloadProgress(
                                    url = downloadUrl,
                                    fileName = fileName,
                                    bytesDownloaded = bytesCopied,
                                    totalBytes = totalBytes,
                                    progressPercent = percent,
                                    speedText = speedText,
                                    isDownloading = true
                                )
                            )
                        }
                    }
                    out.flush()
                }
            }

            // Mark complete in MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(savedUri, completeValues, null, null)
            }

            // Scan with MediaScanner for instant appearance in Gallery
            if (savedFile != null) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(savedFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )
            }

            onProgress(
                DownloadProgress(
                    url = downloadUrl,
                    fileName = fileName,
                    bytesDownloaded = bytesCopied,
                    totalBytes = totalBytes,
                    progressPercent = 100,
                    speedText = "Done",
                    isDownloading = false,
                    isSuccess = true,
                    savedPath = savedFile?.absolutePath ?: savedUri.toString()
                )
            )

            Pair(savedFile, savedUri)
        } catch (e: Exception) {
            Log.e("FileDownloadService", "Download failed: ${e.message}", e)
            onProgress(
                DownloadProgress(
                    url = downloadUrl,
                    fileName = fileName,
                    isDownloading = false,
                    isError = true,
                    errorMessage = e.message ?: "Download failed"
                )
            )
            throw e
        }
    }

    suspend fun downloadPhotoAlbum(
        photoUrls: List<String>,
        baseTitle: String,
        onProgress: (DownloadProgress) -> Unit
    ): List<Pair<File?, Uri?>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<File?, Uri?>>()
        val totalPhotos = photoUrls.size
        val sanitizedBase = sanitizeFilename(baseTitle)

        photoUrls.forEachIndexed { index, photoUrl ->
            val photoNumber = index + 1
            val filename = "${sanitizedBase}_photo_$photoNumber"
            val percent = ((photoNumber.toFloat() / totalPhotos) * 100).toInt()

            onProgress(
                DownloadProgress(
                    url = photoUrl,
                    fileName = "$filename.jpg ($photoNumber/$totalPhotos)",
                    progressPercent = percent,
                    isDownloading = true,
                    speedText = "$photoNumber of $totalPhotos"
                )
            )

            try {
                val res = downloadMedia(
                    downloadUrl = photoUrl,
                    title = filename,
                    format = "jpg",
                    onProgress = { /* sub-progress */ }
                )
                results.add(res)
            } catch (e: Exception) {
                Log.w("FileDownloadService", "Failed to download photo $photoNumber: ${e.message}")
            }
        }

        onProgress(
            DownloadProgress(
                url = photoUrls.firstOrNull() ?: "",
                fileName = "$sanitizedBase (All $totalPhotos photos saved)",
                progressPercent = 100,
                isDownloading = false,
                isSuccess = true,
                savedPath = results.firstOrNull()?.second?.toString() ?: ""
            )
        )

        results
    }

    /**
     * Downloads a multi-slide post (e.g. Instagram Carousel) where every slide is saved
     * separately: video slides as .mp4 and image slides as .jpg.
     */
    suspend fun downloadCarousel(
        items: List<CarouselItem>,
        baseTitle: String,
        onProgress: (DownloadProgress) -> Unit
    ): List<Pair<File?, Uri?>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<File?, Uri?>>()
        val total = items.size
        if (total == 0) return@withContext results

        val sanitizedBase = sanitizeFilename(baseTitle)

        items.forEachIndexed { index, item ->
            val number = index + 1
            val format = item.format.ifBlank { "jpg" }.lowercase()
            val slideTitle = "${sanitizedBase}_slide_$number"
            val percent = ((number.toFloat() / total) * 100).toInt()

            onProgress(
                DownloadProgress(
                    url = item.url,
                    fileName = "$slideTitle.$format ($number/$total)",
                    progressPercent = percent,
                    isDownloading = true,
                    speedText = "$number of $total"
                )
            )

            try {
                val res = downloadMedia(
                    downloadUrl = item.url,
                    title = slideTitle,
                    format = format,
                    onProgress = { /* sub-progress reported via outer counter */ }
                )
                results.add(res)
            } catch (e: Exception) {
                Log.w("FileDownloadService", "Failed to download carousel slide $number: ${e.message}")
            }
        }

        onProgress(
            DownloadProgress(
                url = items.firstOrNull()?.url ?: "",
                fileName = "$sanitizedBase (All $total slides saved)",
                progressPercent = 100,
                isDownloading = false,
                isSuccess = true,
                savedPath = results.firstOrNull()?.second?.toString()
                    ?: results.firstOrNull()?.first?.absolutePath
                    ?: ""
            )
        )

        results
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(30)
            .ifBlank { "media" }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    private fun getRefererForUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("tiktok") -> "https://www.tiktok.com/"
            lower.contains("twitter") || lower.contains("x.com") || lower.contains("twimg") -> "https://twitter.com/"
            lower.contains("pinterest") -> "https://www.pinterest.com/"
            lower.contains("facebook") || lower.contains("fbcdn") -> "https://www.facebook.com/"
            lower.contains("instagram") || lower.contains("cdninstagram") -> "https://www.instagram.com/"
            else -> "https://www.instagram.com/"
        }
    }
}
