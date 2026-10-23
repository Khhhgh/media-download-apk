package com.example.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AdManager
import com.example.data.AppDatabase
import com.example.data.api.ApiClient
import com.example.model.DownloadItem
import com.example.model.MediaResult
import com.example.model.PlatformInfo
import com.example.service.MasterDownloadEngine
import com.example.service.UrlResolver
import com.example.service.YouTubeBlockedException
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication<Application>().applicationContext

    private val apiClient = ApiClient()
    private val downloadEngine = MasterDownloadEngine(context = context, apiClient = apiClient)
    private val downloadDao = AppDatabase.getInstance(context).downloadDao()
    private val adManager = AdManager(context)

    // Dynamic platforms fetched from Ubuntu VPS: GET /api/platforms
    private val _platforms = MutableStateFlow<List<PlatformInfo>>(ApiClient.DEFAULT_PLATFORMS)
    val platforms: StateFlow<List<PlatformInfo>> = _platforms.asStateFlow()

    private val _isServerOnline = MutableStateFlow(true)
    val isServerOnline: StateFlow<Boolean> = _isServerOnline.asStateFlow()

    val downloadsHistory: StateFlow<List<DownloadItem>> = downloadDao.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _detectedPlatform = MutableStateFlow<PlatformInfo?>(null)
    val detectedPlatform: StateFlow<PlatformInfo?> = _detectedPlatform.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractedMedia = MutableStateFlow<MediaResult?>(null)
    val extractedMedia: StateFlow<MediaResult?> = _extractedMedia.asStateFlow()

    private val _isArabic = MutableStateFlow(true)
    val isArabic: StateFlow<Boolean> = _isArabic.asStateFlow()

    private val _showYouTubeBlockedDialog = MutableStateFlow(false)
    val showYouTubeBlockedDialog: StateFlow<Boolean> = _showYouTubeBlockedDialog.asStateFlow()

    private val _showInAppAd = MutableStateFlow(false)
    val showInAppAd: StateFlow<Boolean> = _showInAppAd.asStateFlow()

    fun dismissInAppAd() {
        _showInAppAd.value = false
    }

    private val _clipboardDetectedUrl = MutableStateFlow<String?>(null)
    val clipboardDetectedUrl: StateFlow<String?> = _clipboardDetectedUrl.asStateFlow()

    private val _activePlayingItem = MutableStateFlow<DownloadItem?>(null)
    val activePlayingItem: StateFlow<DownloadItem?> = _activePlayingItem.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Downloader, 1: History
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    init {
        loadDynamicPlatforms()
        checkClipboardForLink()
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    /**
     * Loads live dynamic platform status from the Ubuntu VPS.
     * When new platforms are added or toggled in platforms.json, they appear immediately.
     */
    fun loadDynamicPlatforms() {
        viewModelScope.launch {
            try {
                val list = apiClient.fetchPlatforms()
                if (list.isNotEmpty()) {
                    _platforms.value = list
                    _isServerOnline.value = true
                    updateDetectedPlatform(_urlInput.value)
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Could not fetch dynamic platforms: ${e.message}")
                _isServerOnline.value = false
            }
        }
    }

    private fun updateDetectedPlatform(input: String) {
        if (input.isBlank()) {
            _detectedPlatform.value = null
            return
        }
        val match = _platforms.value.firstOrNull { it.matchesUrl(input) }
        _detectedPlatform.value = match
    }

    fun onUrlChange(newUrl: String) {
        val hasHttp = newUrl.contains("http://", ignoreCase = true) || newUrl.contains("https://", ignoreCase = true)
        val finalUrl = if (hasHttp) {
            val sanitized = UrlResolver.sanitizeAndExtractUrl(newUrl)
            if (sanitized.isNotBlank()) sanitized else newUrl
        } else {
            newUrl
        }

        _urlInput.value = finalUrl
        updateDetectedPlatform(finalUrl)

        if (downloadEngine.isYouTubeUrl(finalUrl)) {
            _showYouTubeBlockedDialog.value = true
        }
    }

    fun dismissYouTubeDialog() {
        _showYouTubeBlockedDialog.value = false
        _urlInput.value = ""
        _detectedPlatform.value = null
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
                    val extracted = UrlResolver.sanitizeAndExtractUrl(text)
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

        val target = UrlResolver.sanitizeAndExtractUrl(raw)
        if (target.isNotBlank()) {
            _urlInput.value = target
            updateDetectedPlatform(target)
            _clipboardDetectedUrl.value = null
            startExtraction(target)
        }
    }

    fun startExtraction(url: String = _urlInput.value) {
        val cleanUrl = UrlResolver.sanitizeAndExtractUrl(url)
        if (cleanUrl.isBlank() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            emitToast(if (_isArabic.value) "يرجى إدخال رابط صالح" else "Please enter a valid link")
            return
        }
        _urlInput.value = cleanUrl
        updateDetectedPlatform(cleanUrl)

        if (downloadEngine.isYouTubeUrl(cleanUrl)) {
            _showYouTubeBlockedDialog.value = true
            return
        }

        // عند الضغط على ابدا ثالث مره يظهر الإعلان داخل التطبيق
        if (adManager.shouldShowInAppAd()) {
            _showInAppAd.value = true
        }

        viewModelScope.launch {
            _isExtracting.value = true
            _extractedMedia.value = null

            try {
                val result = downloadEngine.resolveMedia(cleanUrl, _platforms.value)
                _extractedMedia.value = result
                emitToast(if (_isArabic.value) "تم استخراج الميديا بنجاح! جاهز للتنزيل" else "Media extracted successfully! Ready to download")
            } catch (e: YouTubeBlockedException) {
                _showYouTubeBlockedDialog.value = true
            } catch (e: Exception) {
                Log.e("MainViewModel", "Extraction failed: ${e.message}", e)
                val reason = (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(300)
                val msg = if (_isArabic.value) "فشل الاستخراج: $reason" else "Extraction failed: $reason"
                emitToast(msg)
            } finally {
                _isExtracting.value = false
            }
        }
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

    private fun emitToast(msg: String) {
        viewModelScope.launch {
            _toastEvents.emit(msg)
        }
    }
}
