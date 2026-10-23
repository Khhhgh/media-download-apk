package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.DownloadProgress
import com.example.model.MediaUrlHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class DetectedMediaType {
    IMAGE,
    VIDEO,
    AUDIO
}

data class SingleDownloadResult(
    val file: File?,
    val uri: Uri?,
    val mimeType: String,
    val bytesDownloaded: Long,
    val fileName: String,
    val mediaType: DetectedMediaType
)

data class BatchDownloadResult(
    val successCount: Int,
    val failedCount: Int,
    val totalCount: Int,
    val results: List<SingleDownloadResult>,
    /** Arabic message of the first failure, used when nothing could be saved. */
    val firstError: String? = null
)

data class DownloadQueueItem(
    val url: String,
    /** The type decided from the API response / link. Null = decide from the server headers. */
    val type: DetectedMediaType? = null,
    val titlePrefix: String = "Media"
)

/**
 * MediaStore downloader with strict separation between media kinds:
 *
 * - Images (JPG)  -> Pictures/MediaDownloader  via MediaStore.Images.Media.EXTERNAL_CONTENT_URI
 * - Videos (MP4)  -> Movies/MediaDownloader    via MediaStore.Video.Media.EXTERNAL_CONTENT_URI
 * - Audio  (MP3)  -> Music/MediaDownloader     via MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
 *
 * Android 10+ (API 29 up to 36): Scoped Storage with RELATIVE_PATH + IS_PENDING, no permission needed.
 * Android 7-9 (API 24-28): public directory + MediaScanner (needs WRITE_EXTERNAL_STORAGE).
 *
 * A result is returned ONLY after every byte was written, the size was verified and the entry
 * was published, so callers can safely show the success state right after this returns.
 */
class DownloaderRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloaderRepository"
        private val COUNTER = AtomicLong(0L)
        private const val DIRECTORY_NAME = "MediaDownloader"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Decides Image / Video / Audio. The type chosen by the caller wins unless the server
     * clearly says otherwise; with no caller type the Content-Type and link extension decide.
     */
    fun detectMediaType(
        url: String,
        contentType: String?,
        expected: DetectedMediaType?
    ): DetectedMediaType {
        val ct = (contentType ?: "").lowercase(Locale.ROOT)
        val path = url.lowercase(Locale.ROOT).substringBefore('#').substringBefore('?')

        if (ct.startsWith("image/")) return DetectedMediaType.IMAGE
        if (ct.startsWith("video/")) return DetectedMediaType.VIDEO
        if (ct.startsWith("audio/")) return DetectedMediaType.AUDIO

        // octet-stream / binary / missing header: trust the caller
        if (expected != null) return expected

        return when {
            listOf(".jpg", ".jpeg", ".png", ".webp", ".awebp", ".heic", ".gif", ".avif").any { path.endsWith(it) } ->
                DetectedMediaType.IMAGE
            listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus").any { path.endsWith(it) } ->
                DetectedMediaType.AUDIO
            else -> DetectedMediaType.VIDEO
        }
    }

    /**
     * Downloads one file and saves it into the right MediaStore collection.
     * Throws an IOException with a clear Arabic message on any failure; the half-written
     * entry is always removed. Cancellation is propagated as CancellationException.
     */
    suspend fun downloadMedia(
        downloadUrl: String,
        baseTitle: String,
        expectedType: DetectedMediaType? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): SingleDownloadResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
            .build()

        val call = httpClient.newCall(request)
        // Cancelling the coroutine must also abort the blocking network call
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw IOException(networkErrorMessage(e), e)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException(httpErrorMessage(resp.code))
                }
                val body = resp.body ?: throw IOException("استجابة خادم التحميل فارغة")
                val rawContentType = resp.header("Content-Type") ?: body.contentType()?.toString()
                val ct = (rawContentType ?: "").lowercase(Locale.ROOT)

                // HLS / DASH manifests would be saved as a broken ".mp4"
                if (ct.contains("mpegurl") || ct.contains("dash+xml") || MediaUrlHints.looksLikeStreamPlaylist(downloadUrl)) {
                    throw IOException("هذا المقطع يُبث بصيغة مجزأة (HLS) ولا يمكن حفظه مباشرة، جرّب رابطاً آخر للمنشور")
                }

                // An HTML / JSON page is not a media file (expired link, captcha, error page)
                if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html")) {
                    throw IOException("الرابط لا يشير إلى ملف وسائط صالح، حاول استخراج المنشور من جديد")
                }

                val mediaType = detectMediaType(downloadUrl, rawContentType, expectedType)
                val target = targetFor(mediaType, ct)
                val totalBytes = body.contentLength()
                val fileName = "${sanitizeFilename(baseTitle)}_${System.currentTimeMillis()}_${COUNTER.incrementAndGet()}.${target.extension}"

                saveStream(
                    source = body.byteStream(),
                    totalBytes = totalBytes,
                    downloadUrl = downloadUrl,
                    fileName = fileName,
                    mediaType = mediaType,
                    target = target,
                    onProgress = onProgress
                )
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private data class SaveTarget(
        val mimeType: String,
        val extension: String,
        val folder: String
    )

    private fun targetFor(mediaType: DetectedMediaType, contentTypeLower: String): SaveTarget {
        return when (mediaType) {
            DetectedMediaType.IMAGE -> {
                // Photos are saved as .jpg / image/jpeg. PNG and WebP are kept only when the
                // server really sends them, so the file name always matches its content.
                val (mime, ext) = when {
                    contentTypeLower.contains("png") -> "image/png" to "png"
                    contentTypeLower.contains("webp") -> "image/webp" to "webp"
                    else -> "image/jpeg" to "jpg"
                }
                SaveTarget(mime, ext, Environment.DIRECTORY_PICTURES + "/" + DIRECTORY_NAME)
            }
            DetectedMediaType.VIDEO ->
                SaveTarget("video/mp4", "mp4", Environment.DIRECTORY_MOVIES + "/" + DIRECTORY_NAME)
            DetectedMediaType.AUDIO ->
                SaveTarget("audio/mpeg", "mp3", Environment.DIRECTORY_MUSIC + "/" + DIRECTORY_NAME)
        }
    }

    private fun collectionUri(mediaType: DetectedMediaType): Uri = when (mediaType) {
        DetectedMediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        DetectedMediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        DetectedMediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private suspend fun saveStream(
        source: java.io.InputStream,
        totalBytes: Long,
        downloadUrl: String,
        fileName: String,
        mediaType: DetectedMediaType,
        target: SaveTarget,
        onProgress: (DownloadProgress) -> Unit
    ): SingleDownloadResult {
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var savedUri: Uri? = null
        var savedFile: File? = null

        try {
            val output: OutputStream
            if (scoped) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target.folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collectionUri(mediaType), values)
                    ?: throw IOException("تعذر إنشاء مسار الحفظ في معرض الهاتف")
                savedUri = uri
                output = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("تعذر فتح ملف الحفظ في ذاكرة الهاتف")
            } else {
                @Suppress("DEPRECATION")
                val publicDir = Environment.getExternalStoragePublicDirectory(
                    target.folder.substringBefore('/')
                )
                val dir = File(publicDir, DIRECTORY_NAME)
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("تعذر إنشاء مجلد الحفظ، تأكد من منح التطبيق إذن التخزين")
                }
                val file = File(dir, fileName)
                savedFile = file
                output = try {
                    FileOutputStream(file)
                } catch (e: IOException) {
                    throw IOException("تعذر الكتابة في ذاكرة الهاتف، تأكد من منح التطبيق إذن التخزين", e)
                }
            }

            val buffer = ByteArray(32 * 1024)
            var bytesCopied = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            output.use { out ->
                source.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw IOException("انقطع الاتصال أثناء التحميل", e)
                        }
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        bytesCopied += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            val elapsedSec = (now - startTime) / 1000.0
                            val speed = if (elapsedSec > 0) bytesCopied / elapsedSec else 0.0
                            val percent = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                            onProgress(
                                DownloadProgress(
                                    url = downloadUrl,
                                    fileName = fileName,
                                    bytesDownloaded = bytesCopied,
                                    totalBytes = totalBytes,
                                    progressPercent = percent,
                                    speedText = formatSpeed(speed),
                                    isDownloading = true
                                )
                            )
                        }
                    }
                    out.flush()
                }
            }

            // Verify the write is complete before anything is reported as saved
            if (bytesCopied <= 0L) {
                throw IOException("الملف المُستلم فارغ، حاول مرة أخرى")
            }
            if (totalBytes > 0 && bytesCopied < totalBytes) {
                throw IOException("انقطع التحميل قبل اكتمال الملف، حاول مرة أخرى")
            }

            onProgress(
                DownloadProgress(
                    url = downloadUrl,
                    fileName = fileName,
                    bytesDownloaded = bytesCopied,
                    totalBytes = bytesCopied,
                    progressPercent = 100,
                    isDownloading = true
                )
            )

            if (scoped) {
                val uri = savedUri ?: throw IOException("تعذر إكمال الحفظ في المعرض")
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val rows = context.contentResolver.update(uri, publish, null, null)
                if (rows <= 0) throw IOException("تعذر نشر الملف في المعرض")
            } else {
                val file = savedFile ?: throw IOException("تعذر إكمال الحفظ في المعرض")
                savedUri = scanFile(file, target.mimeType)
            }

            return SingleDownloadResult(
                file = savedFile,
                uri = savedUri,
                mimeType = target.mimeType,
                bytesDownloaded = bytesCopied,
                fileName = fileName,
                mediaType = mediaType
            )
        } catch (e: Throwable) {
            // Remove the incomplete entry so no empty / broken file stays in the gallery
            if (scoped) {
                savedUri?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            } else {
                try {
                    savedFile?.takeIf { it.exists() }?.delete()
                } catch (_: Exception) {
                }
            }
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    /** Legacy (API 24-28): registers the file with MediaStore and waits for its content Uri. */
    private suspend fun scanFile(file: File, mimeType: String): Uri? {
        return withTimeoutOrNull(4000L) {
            suspendCancellableCoroutine<Uri?> { cont ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType)
                ) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }
        }
    }

    /**
     * Downloads a batch (Instagram carousel / TikTok photo album) one file after another.
     * Each file gets one retry. A failed file never aborts the rest, and a cancelled
     * coroutine is never swallowed.
     */
    suspend fun downloadBatch(
        items: List<DownloadQueueItem>,
        baseTitle: String,
        onBatchProgress: (currentIndex: Int, totalCount: Int, itemProgress: DownloadProgress) -> Unit
    ): BatchDownloadResult = withContext(Dispatchers.IO) {
        val total = items.size
        val results = mutableListOf<SingleDownloadResult>()
        var failed = 0
        var firstError: String? = null

        items.forEachIndexed { index, queueItem ->
            val number = index + 1
            val itemTitle = "${baseTitle}_${queueItem.titlePrefix}_$number"

            onBatchProgress(
                number,
                total,
                DownloadProgress(url = queueItem.url, fileName = itemTitle, progressPercent = 0, isDownloading = true)
            )

            var saved: SingleDownloadResult? = null
            var attempt = 0
            while (saved == null && attempt < 2) {
                attempt++
                try {
                    saved = downloadMedia(
                        downloadUrl = queueItem.url,
                        baseTitle = itemTitle,
                        expectedType = queueItem.type,
                        onProgress = { itemProgress -> onBatchProgress(number, total, itemProgress) }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Item $number/$total failed (attempt $attempt): ${e.message}")
                    if (attempt >= 2) {
                        failed++
                        if (firstError == null) firstError = e.message
                    }
                }
            }
            saved?.let { results.add(it) }
        }

        BatchDownloadResult(
            successCount = results.size,
            failedCount = failed,
            totalCount = total,
            results = results,
            firstError = firstError
        )
    }

    private fun networkErrorMessage(e: IOException): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ->
                "انقطع الاتصال بالإنترنت أو تعذر الوصول إلى الخادم"
            msg.contains("timeout", ignoreCase = true) ->
                "انتهت مهلة الاتصال بخادم التحميل، حاول مرة أخرى"
            else -> "فشل الاتصال برابط التحميل، تحقق من الإنترنت وحاول مرة أخرى"
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        403 -> "انتهت صلاحية رابط التحميل أو يتطلب مصادقة (رمز 403)"
        404 -> "الملف غير موجود على خوادم المصدر (رمز 404)"
        429 -> "تم تجاوز حد الطلبات المسموح به من المنصة (رمز 429)"
        else -> "فشل تحميل الملف من السيرفر (رمز خطأ: $code)"
    }

    private fun sanitizeFilename(name: String): String {
        val clean = name.replace(Regex("[^a-zA-Z0-9_\\-\\u0600-\\u06FF]"), "_")
            .take(45)
            .trim('_')
        return if (clean.isBlank()) "Media" else clean
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }
}
