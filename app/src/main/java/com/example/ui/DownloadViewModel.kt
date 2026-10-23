package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DetectedMediaType
import com.example.data.DownloadQueueItem
import com.example.data.DownloaderRepository
import com.example.model.DownloadItem
import com.example.model.MediaItem
import com.example.model.MediaKind
import com.example.model.MediaResult
import com.example.model.mediaItems
import com.example.model.mediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Strictly separated download states:
 *
 * - [Idle]        nothing selected yet
 * - [Ready]       post card + the right download button, NOTHING is downloaded or saved
 * - [Downloading] progress bar and counter ("حفظ 2 من 5")
 * - [Success]     shown ONLY after every file was fully written to MediaStore
 * - [Error]       clear Arabic/English message, with retry
 */
sealed interface DownloadUiState {
    data object Idle : DownloadUiState

    data class Ready(val media: MediaResult) : DownloadUiState

    data class Downloading(
        val currentItem: Int,
        val totalItems: Int,
        val percent: Int,
        val speedText: String,
        val statusMessage: String
    ) : DownloadUiState

    data class Success(
        val message: String,
        val savedCount: Int,
        val totalCount: Int
    ) : DownloadUiState

    data class Error(val errorMessage: String) : DownloadUiState
}

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloaderRepository(application.applicationContext)
    private val downloadDao = AppDatabase.getInstance(application.applicationContext).downloadDao()

    private val _uiState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private var currentMedia: MediaResult? = null
    private var activeJob: Job? = null

    /**
     * Identifies the current download. A cancelled or replaced download can still deliver one
     * last progress callback; its state write is ignored because its run id is outdated.
     */
    @Volatile
    private var runId = 0

    private fun publish(run: Int, state: DownloadUiState) {
        if (run == runId) _uiState.value = state
    }

    /**
     * Shows a freshly extracted post in the [DownloadUiState.Ready] state.
     * Calling it again with the same post (e.g. after switching tabs) keeps the current state,
     * so a running download or a finished success card is never reset by recomposition.
     */
    fun prepare(media: MediaResult) {
        if (currentMedia == media && _uiState.value !is DownloadUiState.Idle) return
        runId += 1
        activeJob?.cancel()
        currentMedia = media
        _uiState.value = DownloadUiState.Ready(media)
    }

    /**
     * One entry point for everything: single video, single photo, photo album, mixed carousel
     * (or the audio track when [isAudioOnly] is true).
     *
     * Photos go to Pictures/MediaDownloader, videos to Movies/MediaDownloader.
     * The Success state is published only after the last byte was saved.
     */
    fun downloadMedia(isAudioOnly: Boolean = false, isArabic: Boolean = true) {
        val media = currentMedia ?: return
        if (_uiState.value is DownloadUiState.Downloading) return

        runId += 1
        val run = runId
        // Switch immediately so a second tap can never start a second download
        _uiState.value = DownloadUiState.Downloading(1, 1, 0, "", if (isArabic) "جاري التحضير..." else "Preparing...")
        activeJob?.cancel()
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isAudioOnly && !media.audioUrl.isNullOrBlank()) {
                    saveAudio(run, media, media.audioUrl, isArabic)
                } else {
                    saveItems(run, media, isArabic)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                val reason = e.message?.takeIf { it.isNotBlank() }
                publish(
                    run,
                    DownloadUiState.Error(
                        reason ?: if (isArabic) "فشل التحميل، حاول مرة أخرى" else "Download failed, please try again"
                    )
                )
            }
        }
    }

    private suspend fun saveAudio(run: Int, media: MediaResult, audioUrl: String, isArabic: Boolean) {
        val status = if (isArabic) "جاري تحميل المقطع الصوتي..." else "Downloading audio..."
        publish(run, DownloadUiState.Downloading(1, 1, 0, "", status))

        val result = repository.downloadMedia(
            downloadUrl = audioUrl,
            baseTitle = media.title,
            expectedType = DetectedMediaType.AUDIO,
            onProgress = { p ->
                publish(run, DownloadUiState.Downloading(1, 1, p.progressPercent, p.speedText, status))
            }
        )

        saveHistory(
            media = media,
            title = media.title,
            path = result.file?.absolutePath ?: result.uri?.toString().orEmpty(),
            uri = result.uri?.toString(),
            bytes = result.bytesDownloaded,
            sizeText = formatBytes(result.bytesDownloaded),
            mimeType = result.mimeType,
            thumb = media.thumbnailUrl
        )

        val message = if (isArabic) "تم حفظ الصوت في المعرض بنجاح!" else "Audio saved to your device!"
        publish(run, DownloadUiState.Success(message, 1, 1))
    }

    private suspend fun saveItems(run: Int, media: MediaResult, isArabic: Boolean) {
        val items: List<MediaItem> = media.mediaItems()
        val kind = media.mediaKind()
        if (items.isEmpty()) {
            throw IllegalStateException(
                if (isArabic) "لم يتم العثور على ملفات قابلة للتحميل في هذا المنشور"
                else "No downloadable files were found in this post"
            )
        }

        val total = items.size
        val firstStatus = statusText(1, total, kind, isArabic)
        publish(run, DownloadUiState.Downloading(1, total, 0, "", firstStatus))

        val queue = items.mapIndexed { index, item ->
            DownloadQueueItem(
                url = item.url,
                type = if (item.isVideo) DetectedMediaType.VIDEO else DetectedMediaType.IMAGE,
                titlePrefix = if (item.isVideo) "video_${index + 1}" else "photo_${index + 1}"
            )
        }

        val batch = repository.downloadBatch(items = queue, baseTitle = media.title) { current, count, p ->
            // overall percent across the whole album, not just the current file
            val overall = (((current - 1) * 100) + p.progressPercent) / count.coerceAtLeast(1)
            publish(
                run,
                DownloadUiState.Downloading(
                    currentItem = current,
                    totalItems = count,
                    percent = overall.coerceIn(0, 100),
                    speedText = p.speedText,
                    statusMessage = statusText(current, count, kind, isArabic)
                )
            )
        }

        // Nothing saved at all -> real error, never a green card
        if (batch.successCount == 0) {
            throw IllegalStateException(
                batch.firstError ?: if (isArabic) "تعذر حفظ الملفات، حاول مرة أخرى" else "Could not save the files"
            )
        }

        var firstPath = ""
        var firstUri = ""
        var totalBytes = 0L
        var videoCount = 0
        var imageCount = 0
        batch.results.forEach { res ->
            totalBytes += res.bytesDownloaded
            val path = res.file?.absolutePath ?: res.uri?.toString().orEmpty()
            if (firstPath.isBlank()) firstPath = path
            if (firstUri.isBlank()) firstUri = res.uri?.toString() ?: path
            if (res.mediaType == DetectedMediaType.VIDEO) videoCount++ else imageCount++
        }

        val historyTitle = if (batch.successCount > 1) {
            if (isArabic) "${media.title} (${batch.successCount} ملفات)" else "${media.title} (${batch.successCount} files)"
        } else media.title

        val sizeText = if (batch.successCount > 1) {
            if (isArabic) "$videoCount فيديو • $imageCount صورة" else "$videoCount videos • $imageCount photos"
        } else formatBytes(totalBytes)

        saveHistory(
            media = media,
            title = historyTitle,
            path = firstPath,
            uri = firstUri.ifBlank { null },
            bytes = totalBytes,
            sizeText = sizeText,
            mimeType = if (videoCount > 0) "video/mp4" else "image/jpeg",
            thumb = media.thumbnailUrl ?: items.firstOrNull { !it.isVideo }?.url
        )

        publish(
            run,
            DownloadUiState.Success(
                message = successText(batch.successCount, batch.failedCount, total, kind, isArabic),
                savedCount = batch.successCount,
                totalCount = total
            )
        )
    }

    private suspend fun saveHistory(
        media: MediaResult,
        title: String,
        path: String,
        uri: String?,
        bytes: Long,
        sizeText: String,
        mimeType: String,
        thumb: String?
    ) {
        // The file is already saved; a history failure must never turn the result into an error
        try {
            downloadDao.insertDownload(
                DownloadItem(
                    title = title,
                    platform = media.platform,
                    sourceUrl = media.sourceUrl,
                    localFilePath = path,
                    mediaUri = uri ?: path,
                    fileSize = bytes,
                    formattedSize = sizeText,
                    mimeType = mimeType,
                    thumbnailUri = thumb
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "History insert failed: ${e.message}")
        }
    }

    private fun statusText(current: Int, total: Int, kind: MediaKind, isArabic: Boolean): String {
        return if (total > 1) {
            if (isArabic) "جاري حفظ $current من $total..." else "Saving $current of $total..."
        } else {
            when (kind) {
                MediaKind.IMAGE -> if (isArabic) "جاري حفظ الصورة..." else "Saving photo..."
                else -> if (isArabic) "جاري حفظ الفيديو..." else "Saving video..."
            }
        }
    }

    private fun successText(saved: Int, failed: Int, total: Int, kind: MediaKind, isArabic: Boolean): String {
        val noun = when (kind) {
            MediaKind.VIDEO -> if (isArabic) "الفيديو" else "video"
            MediaKind.IMAGE -> if (isArabic) "الصورة" else "photo"
            MediaKind.CAROUSEL_IMAGES -> if (isArabic) "صور" else "photos"
            MediaKind.CAROUSEL_MIXED -> if (isArabic) "ملفات" else "files"
        }
        return when {
            total == 1 && kind == MediaKind.VIDEO ->
                if (isArabic) "تم حفظ الفيديو في المعرض بنجاح!" else "Video saved to your gallery!"
            total == 1 ->
                if (isArabic) "تم حفظ الصورة في المعرض بنجاح!" else "Photo saved to your gallery!"
            failed > 0 ->
                if (isArabic) "تم حفظ $saved من $total $noun في المعرض (تعذر حفظ $failed)"
                else "Saved $saved of $total $noun to your gallery ($failed failed)"
            else ->
                if (isArabic) "تم حفظ $saved $noun في المعرض بنجاح!" else "Saved $saved $noun to your gallery!"
        }
    }

    /** Stops the running download and returns to the post card. */
    fun cancelDownload() {
        runId += 1
        activeJob?.cancel()
        activeJob = null
        val media = currentMedia
        _uiState.value = if (media != null) DownloadUiState.Ready(media) else DownloadUiState.Idle
    }

    /** Clears everything (the post card was closed or a new link is being extracted). */
    fun reset() {
        runId += 1
        activeJob?.cancel()
        activeJob = null
        currentMedia = null
        _uiState.value = DownloadUiState.Idle
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private companion object {
        const val TAG = "DownloadViewModel"
    }
}
