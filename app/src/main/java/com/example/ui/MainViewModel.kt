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
import com.example.service.EngineUpdateManager
import com.example.service.FileDownloadService
import com.example.service.FirstRunProgress
import com.example.service.FirstRunSetupManager
import com.example.service.MasterDownloadEngine
import com.example.service.UpdateDownloadState
import com.example.service.YouTubeBlockedException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val downloadEngine = MasterDownloadEngine(context = context)
    private val fileDownloadService = FileDownloadService(context)
    private val downloadDao = AppDatabase.getInstance(context).downloadDao()
    private val appUpdateManager = AppUpdateManager(context)
    private val firstRunSetupManager = FirstRunSetupManager(context)
    private val engineUpdateManager = EngineUpdateManager(context)
    private var engineCheckJob: Job? = null
    private var lastEngineCheckAt = 0L

    private val _showFirstRunSetup = MutableStateFlow(firstRunSetupManager.shouldRunSetup())
    val showFirstRunSetup: StateFlow<Boolean> = _showFirstRunSetup.asStateFlow()

    private val _firstRunProgress = MutableStateFlow<FirstRunProgress?>(null)
    val firstRunProgress: StateFlow<FirstRunProgress?> = _firstRunProgress.asStateFlow()

    private val _firstRunError = MutableStateFlow<String?>(null)
    val firstRunError: StateFlow<String?> = _firstRunError.asStateFlow()

    private var firstRunJob: Job? = null

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

    /**
     * Silent launch check: reads the cache-busted `version.txt` and, only if the remote version is
     * newer, downloads + atomically swaps the engines with no UI. If the app is still on the
     * offline fallback and a connection is now available, provisioning is retried instead.
     * Throttled so repeated resumes don't hit the CDN.
     */
    fun silentEngineCheck() {
        if (_showFirstRunSetup.value) return
        if (engineCheckJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastEngineCheckAt < ENGINE_CHECK_THROTTLE_MS) return
        lastEngineCheckAt = now

        engineCheckJob = viewModelScope.launch {
            try {
                if (!firstRunSetupManager.isProvisioned) {
                    if (firstRunSetupManager.isOnline()) {
                        val r = firstRunSetupManager.runSetup { }
                        Log.i("MainViewModel", "Deferred provisioning: provisioned=${r.provisioned} v=${r.version}")
                    }
                } else {
                    val r = engineUpdateManager.checkAndUpdate()
                    Log.i("MainViewModel", "Silent engine check: ok=${r.success} updated=${r.updated} v=${r.activeVersion} err=${r.error}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "Silent engine check failed: ${e.message}")
            }
        }
    }

    /**
     * Runs the first-run provisioning (download latest engines from the CDN, prepare libraries,
     * verify) off the main thread; the dialog only observes progress. The bundled engines are used
     * only as an offline last resort. Safe to call repeatedly (e.g. from the retry button).
     */
    fun startFirstRunSetup() {
        if (firstRunJob?.isActive == true) return
        firstRunJob = viewModelScope.launch {
            _firstRunError.value = null
            val result = firstRunSetupManager.runSetup { progress ->
                _firstRunProgress.value = progress
            }
            if (result.success) {
                _showFirstRunSetup.value = false
                _firstRunProgress.value = null
                if (!result.provisioned) {
                    // Offline / unreachable source: bundled engines are used silently and the
                    // background sync retries later. Nothing is shown to the user.
                    Log.i("MainViewModel", "Using bundled engines (${result.error ?: "offline"})")
                }
            } else {
                _firstRunError.value = result.error ?: "فشل التهيئة الأولية"
            }
        }
    }

    fun onUrlChange(newUrl: String) {
        // If the input contains pasted content with captions, symbols like "==", spaces, newlines, or accidental prefixes
        val hasHttp = newUrl.contains("http://", ignoreCase = true) || newUrl.contains("https://", ignoreCase = true)
        val finalUrl = if (hasHttp) {
            val trimmed = newUrl.trim()
            val startsClean = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
            val hasExtraPrefixOrSpaces = !startsClean ||
                    trimmed.contains(" ") ||
                    trimmed.contains("\n") ||
                    trimmed.contains("==") ||
                    trimmed.startsWith("/") ||
                    trimmed.startsWith("\\")
            if (hasExtraPrefixOrSpaces) {
                val extracted = com.example.service.UrlResolver.sanitizeAndExtractUrl(trimmed)
                if (extracted.isNotBlank()) extracted else newUrl
            } else {
                newUrl
            }
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
                // Show the engine's own exception text so experimental/engine errors are visible.
                Log.e("MainViewModel", "Extraction failed", e)
                val reason = (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(500)
                val msg = if (_isArabic.value) "فشل الاستخراج: $reason" else "Extraction failed: $reason"
                emitToast(msg)
            } finally {
                _isExtracting.value = false
            }
        }
    }

    private val _showPreAdToast = MutableStateFlow(false)
    val showPreAdToast: StateFlow<Boolean> = _showPreAdToast.asStateFlow()

    private val _showInAppAd = MutableStateFlow<String?>(null)
    val showInAppAd: StateFlow<String?> = _showInAppAd.asStateFlow()

    fun dismissInAppAd() {
        _showInAppAd.value = null
    }

    private fun triggerAdWithPreNotice(adUrl: String) {
        viewModelScope.launch {
            _showPreAdToast.value = true
            delay(1200L) // 1.2s delay for centered notification
            _showPreAdToast.value = false
            _showInAppAd.value = adUrl
        }
    }

    fun startDownload(quality: QualityOption? = null) {
        val media = _extractedMedia.value ?: return
        val targetUrl = quality?.url ?: media.videoUrl
        val format = quality?.format ?: (
            if (media.carouselItems.any { it.format == "mp4" } || media.videoUrl.contains(".mp4") || !media.videoUrl.contains(".jpg")) "mp4"
            else "jpg"
        )

        // Monetization & Ad Frequency logic
        // If 4th download: trigger centered toast followed by fullscreen ad
        val adTriggered = adManager.onDownloadTrigger(remoteConfig.value.adSettings)
        if (adTriggered) {
            val adUrl = remoteConfig.value.adSettings.url.ifBlank {
                "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce"
            }
            triggerAdWithPreNotice(adUrl)
        }

        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch {
            // Multi-slide post (e.g. Instagram Carousel): download every slide separately
            // with its own correct format (video -> mp4, image -> jpg).
            if (format == "carousel" && media.carouselItems.isNotEmpty()) {
                _downloadProgress.value = DownloadProgress(
                    url = targetUrl,
                    fileName = media.title,
                    isDownloading = true
                )

                try {
                    val downloadedPairs = fileDownloadService.downloadCarousel(
                        items = media.carouselItems,
                        baseTitle = media.title
                    ) { progress ->
                        _downloadProgress.value = progress
                    }

                    val firstPair = downloadedPairs.firstOrNull()
                    val filePath = firstPair?.first?.absolutePath ?: (firstPair?.second?.toString() ?: "")
                    val videoCount = media.carouselItems.count { it.format == "mp4" }
                    val imageCount = media.carouselItems.size - videoCount

                    val item = DownloadItem(
                        title = "${media.title} (${media.carouselItems.size} slides)",
                        platform = media.platform,
                        sourceUrl = media.sourceUrl,
                        localFilePath = filePath,
                        mediaUri = firstPair?.second?.toString() ?: filePath,
                        fileSize = _downloadProgress.value.bytesDownloaded,
                        formattedSize = "$videoCount video / $imageCount image",
                        mimeType = if (videoCount > 0) "video/mp4" else "image/jpeg",
                        thumbnailUri = media.thumbnailUrl ?: media.carouselItems.firstOrNull()?.url
                    )
                    downloadDao.insertDownload(item)

                    emitToast(
                        if (_isArabic.value) "تم حفظ جميع عناصر المنشور (${media.carouselItems.size}) في المعرض بنجاح! ($videoCount فيديو و $imageCount صورة)"
                        else "Saved all ${media.carouselItems.size} slides to Gallery! ($videoCount video & $imageCount photo)"
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Carousel download error", e)
                    emitToast(
                        if (_isArabic.value) "فشل تحميل عناصر المنشور: ${e.message}" else "Carousel download failed: ${e.message}"
                    )
                }
                return@launch
            }

            // Pure Photo Album batch download (multiple photos, e.g. TikTok photos, when explicitly requesting album batch)
            val isAlbumBatch = format == "album" || (format == "jpg" && quality?.label?.contains("جميع") == true && media.photoUrls.size > 1)
            if (isAlbumBatch && media.photoUrls.isNotEmpty()) {
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

            // Normal single download (Video MP4, Audio MP3, or Single Photo JPG)
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
                val mimeType = when (format) {
                    "mp3" -> "audio/mpeg"
                    "jpg", "jpeg", "png", "webp" -> "image/jpeg"
                    else -> "video/mp4"
                }

                val item = DownloadItem(
                    title = media.title,
                    platform = media.platform,
                    sourceUrl = media.sourceUrl,
                    localFilePath = filePath,
                    mediaUri = uri?.toString() ?: filePath,
                    fileSize = _downloadProgress.value.bytesDownloaded,
                    formattedSize = formatBytes(_downloadProgress.value.bytesDownloaded),
                    mimeType = mimeType,
                    thumbnailUri = media.thumbnailUrl
                )
                downloadDao.insertDownload(item)

                val successMsg = if (_isArabic.value) {
                    when {
                        format == "mp3" -> "تم حفظ الصوت في المعرض بنجاح!"
                        format in listOf("jpg", "jpeg", "png", "webp") -> "تم حفظ الصورة في المعرض بنجاح!"
                        else -> "تم حفظ الفيديو في المعرض بنجاح!"
                    }
                } else {
                    when {
                        format == "mp3" -> "Audio saved to Gallery successfully!"
                        format in listOf("jpg", "jpeg", "png", "webp") -> "Photo saved to Gallery successfully!"
                        else -> "Video saved to Gallery successfully!"
                    }
                }
                emitToast(successMsg)
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
        triggerAdWithPreNotice(adUrl)
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

    private companion object {
        /** Minimum gap between in-app silent engine checks (each is a ~1-byte request). */
        const val ENGINE_CHECK_THROTTLE_MS = 15 * 60 * 1000L
    }
}
