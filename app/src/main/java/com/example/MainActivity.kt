package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.PeriodicSyncWorker
import com.example.ui.MainViewModel
import com.example.ui.UpdatesScreen
import com.example.ui.components.AppHeader
import com.example.ui.components.FirstRunSetupDialog
import com.example.ui.components.HistoryView
import com.example.ui.components.MediaPreviewModal
import com.example.ui.components.ForcedUpdateDialog
import com.example.ui.components.PlatformChips
import com.example.ui.components.RemoteConfigDialog
import com.example.ui.components.UrlInputCard
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.YouTubePolicyDialog
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MediaDownloadTheme
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Silent OTA engine sync every 3 days (KEEP => never duplicated across launches).
        try {
            PeriodicSyncWorker.schedulePeriodic(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Unable to schedule engine sync: ${e.message}")
        }

        setContent {
            MediaDownloadTheme {
                MediaDownloadApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MediaDownloadApp(viewModel: MainViewModel) {
    val isArabic by viewModel.isArabic.collectAsState()
    val urlInput by viewModel.urlInput.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val extractedMedia by viewModel.extractedMedia.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadsHistory by viewModel.downloadsHistory.collectAsState()
    val showYouTubeBlockedDialog by viewModel.showYouTubeBlockedDialog.collectAsState()
    val clipboardDetectedUrl by viewModel.clipboardDetectedUrl.collectAsState()
    val activePlayingItem by viewModel.activePlayingItem.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val remoteConfig by viewModel.remoteConfig.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateDownloadState by viewModel.updateDownloadState.collectAsState()
    val showInAppAd by viewModel.showInAppAd.collectAsState()
    val showPreAdToast by viewModel.showPreAdToast.collectAsState()
    val showFirstRunSetup by viewModel.showFirstRunSetup.collectAsState()
    val firstRunProgress by viewModel.firstRunProgress.collectAsState()
    val firstRunError by viewModel.firstRunError.collectAsState()

    var showConfigDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Listen to ViewModel toast events
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { msg ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    // Auto inspect clipboard on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkClipboardForLink()
                // Tiny cache-busted version.txt read; downloads only when remote > local.
                viewModel.silentEngineCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBackground),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    containerColor = CyberSurface,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { viewModel.setTab(0) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Downloader"
                            )
                        },
                        label = {
                            Text(
                                text = if (isArabic) "التحميل" else "Downloader",
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF03101E),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = CyberTextMuted,
                            unselectedTextColor = CyberTextMuted
                        ),
                        modifier = Modifier.testTag("tab_downloader")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { viewModel.setTab(1) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (downloadsHistory.isNotEmpty()) {
                                        Badge(containerColor = ElectricBlue) {
                                            Text(
                                                text = downloadsHistory.size.toString(),
                                                color = Color.White,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = "Library"
                                )
                            }
                        },
                        label = {
                            Text(
                                text = if (isArabic) "السجل" else "Library",
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF03101E),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color(0xFF8A99AD),
                            unselectedTextColor = Color(0xFF8A99AD)
                        ),
                        modifier = Modifier.testTag("tab_history")
                    )

                    // Tab 3: Updates
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { viewModel.setTab(2) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "Updates"
                            )
                        },
                        label = {
                            Text(
                                text = if (isArabic) "تحديثات" else "Updates",
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF03101E),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color(0xFF8A99AD),
                            unselectedTextColor = Color(0xFF8A99AD)
                        ),
                        modifier = Modifier.testTag("tab_updates")
                    )
                }
            }
        ) { paddingValues ->
            if (selectedTab != 0) {
                BackHandler {
                    viewModel.setTab(0)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CyberBackground)
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> {
                        // Downloader Main Screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            AppHeader(
                                isArabic = isArabic,
                                adCounter = viewModel.adCounter,
                                onToggleLanguage = { viewModel.toggleLanguage() },
                                onOpenSettings = { showConfigDialog = true },
                                modifier = Modifier.statusBarsPadding()
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            PlatformChips(
                                isArabic = isArabic,
                                onPlatformClick = { platform ->
                                    viewModel.onUrlChange("https://${platform.samplePattern}")
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            UrlInputCard(
                                url = urlInput,
                                isArabic = isArabic,
                                isLoading = isExtracting,
                                clipboardDetectedUrl = clipboardDetectedUrl,
                                onUrlChange = { viewModel.onUrlChange(it) },
                                onPasteClick = { viewModel.pasteAndDownload() },
                                onClearClick = { viewModel.onUrlChange("") },
                                onSubmit = { viewModel.startExtraction() },
                                onDismissClipboard = { /* auto-managed */ }
                            )

                            // Extracted Media Preview Modal / Card
                            if (extractedMedia != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    MediaPreviewModal(
                                        media = extractedMedia!!,
                                        isArabic = isArabic,
                                        progress = downloadProgress,
                                        onDownloadQuality = { quality ->
                                            viewModel.startDownload(quality)
                                        },
                                        onCancelDownload = { viewModel.cancelDownload() },
                                        onDismiss = { viewModel.dismissExtractedModal() }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                    1 -> {
                        // History Screen
                        HistoryView(
                            downloads = downloadsHistory,
                            isArabic = isArabic,
                            onPlayItem = { viewModel.playItem(it) },
                            onShareItem = { viewModel.shareItem(it) },
                            onDeleteItem = { viewModel.deleteHistoryItem(it) },
                            onClearAll = { viewModel.clearAllHistory() },
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                    else -> {
                        // Updates Screen
                        UpdatesScreen(
                            isArabic = isArabic,
                            onNavigateBack = { viewModel.setTab(0) },
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                }
            }

            // Dialogs
            if (showYouTubeBlockedDialog) {
                YouTubePolicyDialog(
                    isArabic = isArabic,
                    onDismiss = { viewModel.dismissYouTubeDialog() }
                )
            }

            if (activePlayingItem != null) {
                VideoPlayerDialog(
                    item = activePlayingItem!!,
                    onDismiss = { viewModel.dismissPlayer() }
                )
            }

            if (showConfigDialog) {
                RemoteConfigDialog(
                    config = remoteConfig,
                    isArabic = isArabic,
                    adCounter = viewModel.adCounter,
                    onRefreshConfig = { viewModel.refreshRemoteConfigNow() },
                    onTestAdLink = { viewModel.testAdLinkDirectly() },
                    onDismiss = { showConfigDialog = false }
                )
            }

            // Non-dismissible Forced Update Dialog for GitHub Releases
            if (updateInfo != null && updateInfo!!.hasUpdate) {
                ForcedUpdateDialog(
                    updateInfo = updateInfo!!,
                    downloadState = updateDownloadState,
                    onStartUpdate = { viewModel.startAppUpdate() }
                )
            }

            // Centered Pre-Ad Toast (1.2s delay before showing ad)
            if (showPreAdToast) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { /* brief non-blocking alert */ },
                    properties = androidx.compose.ui.window.DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xF20A1118)),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCyan),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .testTag("pre_ad_centered_toast")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Campaign,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = if (isArabic) "سنعرض إعلان يمكنك التخطي" else "Showing ad, you can skip",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Fullscreen In-App Interstitial Ad Dialog
            if (showInAppAd != null) {
                com.example.ui.components.InAppAdDialog(
                    adUrl = showInAppAd!!,
                    isArabic = isArabic,
                    onDismiss = { viewModel.dismissInAppAd() }
                )
            }

            // First-run engine/library installation window
            if (showFirstRunSetup) {
                FirstRunSetupDialog(
                    progress = firstRunProgress,
                    error = firstRunError,
                    isArabic = isArabic,
                    onRetry = { viewModel.startFirstRunSetup() },
                    onStart = { viewModel.startFirstRunSetup() }
                )
            }
        }
    }
}
