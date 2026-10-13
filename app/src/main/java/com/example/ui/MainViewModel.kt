package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.RemoteConfigRepository
import com.example.model.AppUpdateInfo
import com.example.model.DownloadItem
import com.example.model.DownloadProgress
import com.example.model.MediaResult
import com.example.model.QualityOption
import com.example.model.RemoteConfigData
import com.example.service.AdManager
import com.example.service.AppUpdateManager
import com.example.service.FileDownloadService
import com.example.service.MasterDownloadEngine
import com.example.service.UpdateDownloadState
import com.example.service.YouTubeBlockedException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication<Application>().applicationContext

    private val remoteConfigRepo = RemoteConfigRepository(context)
    private val adManager = AdManager(context)
    private val downloadEngine = MasterDownloadEngine()
    private val fileDownloadService = FileDownloadService(context)
    private val downloadDao = AppDatabase.getInstance(context).downloadDao()
    private val appUpdateManager = AppUpdateManager(context)

    val remoteConfig: StateFlow<RemoteConfigData> = remoteConfigRepo.configState
    val downloadsHistory: StateFlow<List<DownloadItem>> = downloadDao.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadState> = _updateDownloadState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractedMedia = MutableStateFlow<MediaResult?>(null)
    val extractedMedia: StateFlow<MediaResult?> = _extractedMedia.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _isArabic = MutableStateFlow(true)
    val isArabic: StateFlow<Boolean> = _isArabic.asStateFlow()

    private val _showYouTubeBlockedDialog = MutableStateFlow(false)
    val showYouTubeBlockedDialog: StateFlow<Boolean> = _showYouTubeBlockedDialog.asStateFlow()

    private val _clipboardDetectedUrl = MutableStateFlow<String?>(null)
    val clipboardDetectedUrl: StateFlow<String?> = _clipboardDetectedUrl.asStateFlow()

    private val _activePlayingItem = MutableStateFlow<DownloadItem?>(null)
    val activePlayingItem: StateFlow<DownloadItem?> = _activePlayingItem.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Downloader, 1: History, 2: Remote Engine
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    val adCounter: Int get() = adManager.downloadCount

    private var currentDownloadJob: Job? = null

    init {
        // Fetch latest remote config on app start in background
        viewModelScope.launch {
            remoteConfigRepo.fetchRemoteConfig()
        }
        // Check for app updates via GitHub Releases on startup
        checkAppUpdate()
        checkClipboardForLink()
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    fun onUrlChange(newUrl: String) {
        // If the input contains pasted content with captions, spaces, newlines, or accidental prefixes
        val hasPastedBlock = newUrl.contains("\n") ||
                (newUrl.contains(" ") && (newUrl.contains("http://") || newUrl.contains("https://"))) ||
                newUrl.startsWith("/http") || newUrl.startsWith("\\http") || newUrl.startsWith("//http")

        val finalUrl = if (hasPastedBlock) {
            val extracted = com.example.service.UrlResolver.sanitizeAndExtractUrl(newUrl)
            if (extracted.isNotBlank()) extracted else newUrl
        } else {
            newUrl
        }

        _urlInput.value = finalUrl
        if (downloadEngine.isYouTubeUrl(finalUrl)) {
            _showYouTubeBlockedDialog.value = true
        }
    }

    fun dismissYouTubeDialog() {
        _showYouTubeBlockedDialog.value = false
        _urlInput.value = ""
    }

    fun toggleLanguage() {
        _isArabic.value = !_isArabic.value
    }

    fun checkClipboardForLink() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.text?.toString()
                if (!text.isNullOrBlank()) {
                    val extracted = com.example.service.UrlResolver.sanitizeAndExtractUrl(text)
                    if (extracted.startsWith("http://") || extracted.startsWith("https://")) {
                        if (downloadEngine.isYouTubeUrl(extracted)) {
                            _clipboardDetectedUrl.value = null
                        } else if (extracted != _urlInput.value) {
                            _clipboardDetectedUrl.value = extracted
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "Clipboard inspection: ${e.message}")
        }
    }

    fun pasteAndDownload(urlToUse: String? = null) {
        val raw = urlToUse ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()
            } else null
        } ?: return

        val target = com.example.service.UrlResolver.sanitizeAndExtractUrl(raw)
        if (target.isNotBlank()) {
            _urlInput.value = target
            _clipboardDetectedUrl.value = null
            startExtraction(target)
        }
    }

    fun startExtraction(url: String = _urlInput.value) {
        val cleanUrl = com.example.service.UrlResolver.sanitizeAndExtractUrl(url)
        if (cleanUrl.isBlank() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            emitToast(if (_isArabic.value) "يرجى إدخال رابط صالح" else "Please enter a valid link")
            return
        }
        _urlInput.value = cleanUrl

        if (downloadEngine.isYouTubeUrl(cleanUrl)) {
            _showYouTubeBlockedDialog.value = true
            return
        }

        viewModelScope.launch {
            _isExtracting.value = true
            _extractedMedia.value = null

            try {
                val result = downloadEngine.resolveMedia(cleanUrl, remoteConfig.value)
                _extractedMedia.value = result
                emitToast(if (_isArabic.value) "تم استخراج الفيديو بنجاح! جاهز للتحميل" else "Media extracted successfully! Ready to download")
            } catch (e: YouTubeBlockedException) {
                _showYouTubeBlockedDialog.value = true
            } catch (e: Exception) {
                Log.e("MainViewModel", "Extraction failed", e)
                val msg = if (_isArabic.value) {
                    "فشل استخراج الرابط. جاري تجربة المحركات الاحتياطية أو تأكد من أن الرابط عام وليس خاص."
                } else {
                    "Failed to extract media: ${e.message ?: "Unknown error"}. Make sure the link is public."
                }
                emitToast(msg)
            } finally {
                _isExtracting.value = false
            }
        }
    }

    private val _showInAppAd = MutableStateFlow<String?>(null)
    val showInAppAd: StateFlow<String?> = _showInAppAd.asStateFlow()

    fun dismissInAppAd() {
        _showInAppAd.value = null
    }

    fun startDownload(quality: QualityOption? = null) {
        val media = _extractedMedia.value ?: return
        val targetUrl = quality?.url ?: media.videoUrl
        val format = quality?.format ?: "mp4"

        // Monetization & Ad Frequency logic
        // If 4th download: trigger in-app fullscreen ad modal (instead of launching external browser)
        val adTriggered = adManager.onDownloadTrigger(remoteConfig.value.adSettings)
        if (adTriggered) {
            val adUrl = remoteConfig.value.adSettings.url.ifBlank {
                "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce"
            }
            _showInAppAd.value = adUrl
        }

        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch {
            // Check if this is a TikTok Photo Album download (batch download all photos)
            if (media.photoUrls.isNotEmpty() && format != "mp3") {
                _downloadProgress.value = DownloadProgress(
                    url = targetUrl,
                    fileName = media.title,
                    isDownloading = true
                )

                try {
                    val downloadedPairs = fileDownloadService.downloadPhotoAlbum(
                        photoUrls = media.photoUrls,
                        baseTitle = media.title
                    ) { progress ->
                        _downloadProgress.value = progress
                    }

                    // Save first photo to Room history as record
                    val firstPair = downloadedPairs.firstOrNull()
                    val filePath = firstPair?.first?.absolutePath ?: (firstPair?.second?.toString() ?: "")
                    val item = DownloadItem(
                        title = "${media.title} (${media.photoUrls.size} Photos)",
                        platform = media.platform,
                        sourceUrl = media.sourceUrl,
                        localFilePath = filePath,
                        mediaUri = firstPair?.second?.toString() ?: filePath,
                        fileSize = _downloadProgress.value.bytesDownloaded,
                        formattedSize = "${media.photoUrls.size} Photos",
                        mimeType = "image/jpeg",
                        thumbnailUri = media.thumbnailUrl ?: media.photoUrls.firstOrNull()
                    )
                    downloadDao.insertDownload(item)

                    emitToast(
                        if (_isArabic.value) "تم حفظ جميع صور الألبوم (${media.photoUrls.size} صورة) في المعرض بنجاح!"
                        else "Saved all ${media.photoUrls.size} album photos to Gallery!"
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Photo album download error", e)
                    emitToast(
                        if (_isArabic.value) "فشل تحميل الألبوم: ${e.message}" else "Album download failed: ${e.message}"
                    )
                }
                return@launch
            }

            _downloadProgress.value = DownloadProgress(
                url = targetUrl,
                fileName = media.title,
                isDownloading = true
            )

            try {
                val (file, uri) = fileDownloadService.downloadMedia(
                    downloadUrl = targetUrl,
                    title = media.title,
                    format = format
                ) { progress ->
                    _downloadProgress.value = progress
                }

                // Save to Room history
                val filePath = file?.absolutePath ?: (uri?.toString() ?: "")
                val item = DownloadItem(
                    title = media.title,
                    platform = media.platform,
                    sourceUrl = media.sourceUrl,
                    localFilePath = filePath,
                    mediaUri = uri?.toString() ?: filePath,
                    fileSize = _downloadProgress.value.bytesDownloaded,
                    formattedSize = formatBytes(_downloadProgress.value.bytesDownloaded),
                    mimeType = if (format == "mp3") "audio/mpeg" else "video/mp4",
                    thumbnailUri = media.thumbnailUrl
                )
                downloadDao.insertDownload(item)

                emitToast(
                    if (_isArabic.value) "تم الحفظ في المعرض بنجاح!" else "Saved to Gallery successfully!"
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Download error", e)
                emitToast(
                    if (_isArabic.value) "فشل التحميل: ${e.message}" else "Download failed: ${e.message}"
                )
            }
        }
    }

    fun cancelDownload() {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        _downloadProgress.value = DownloadProgress(isDownloading = false)
        emitToast(if (_isArabic.value) "تم إلغاء التحميل" else "Download cancelled")
    }

    fun dismissExtractedModal() {
        _extractedMedia.value = null
    }

    fun deleteHistoryItem(item: DownloadItem) {
        viewModelScope.launch {
            downloadDao.deleteDownload(item)
            try {
                if (item.localFilePath.isNotBlank()) {
                    val file = File(item.localFilePath)
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Could not delete local file: ${e.message}")
            }
            emitToast(if (_isArabic.value) "تم حذف العنصر" else "Item deleted")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            downloadDao.clearAll()
            emitToast(if (_isArabic.value) "تم مسح السجل" else "History cleared")
        }
    }

    fun playItem(item: DownloadItem) {
        _activePlayingItem.value = item
    }

    fun dismissPlayer() {
        _activePlayingItem.value = null
    }

    fun shareItem(item: DownloadItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                if (item.mediaUri.startsWith("content://")) {
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(item.mediaUri))
                } else {
                    putExtra(Intent.EXTRA_TEXT, item.sourceUrl)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            emitToast("Share failed: ${e.message}")
        }
    }

    fun checkAppUpdate() {
        viewModelScope.launch {
            val result = appUpdateManager.checkForUpdates()
            result.onSuccess { info ->
                if (info.hasUpdate) {
                    _updateInfo.value = info
                    Log.d("MainViewModel", "Forced update required: v${info.latestVersion}")
                }
            }.onFailure {
                Log.d("MainViewModel", "Check update failed: ${it.message}")
            }
        }
    }

    fun startAppUpdate() {
        val info = _updateInfo.value ?: return
        val downloadUrl = info.apkDownloadUrl.ifBlank { info.releasePageUrl }
        if (downloadUrl.isBlank()) return

        val current = _updateDownloadState.value
        if (current is UpdateDownloadState.ReadyToInstall) {
            appUpdateManager.launchPackageInstaller(current.apkFile)
            return
        }

        viewModelScope.launch {
            appUpdateManager.downloadApkStream(downloadUrl).collect { state ->
                _updateDownloadState.value = state
                if (state is UpdateDownloadState.ReadyToInstall) {
                    appUpdateManager.launchPackageInstaller(state.apkFile)
                }
            }
        }
    }

    fun refreshRemoteConfigNow() {
        viewModelScope.launch {
            val result = remoteConfigRepo.fetchRemoteConfig()
            if (result.isSuccess) {
                emitToast(if (_isArabic.value) "تم تحديث الإعدادات عن بُعد بنجاح" else "Remote config refreshed successfully")
            } else {
                emitToast(if (_isArabic.value) "تعذر الاتصال بالخادم، تم استخدام النسخة المخزنة" else "Using cached/bundled config")
            }
        }
    }

    fun testAdLinkDirectly() {
        val adUrl = remoteConfig.value.adSettings.url.ifBlank {
            "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce"
        }
        _showInAppAd.value = adUrl
    }

    private fun emitToast(msg: String) {
        viewModelScope.launch {
            _toastEvents.emit(msg)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
