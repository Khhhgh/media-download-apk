package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AdManager
import com.example.ui.DownloadScreen
import com.example.ui.DownloadViewModel
import com.example.ui.MainViewModel
import com.example.ui.components.AdWebViewDialog
import com.example.ui.components.AppHeader
import com.example.ui.components.HistoryView
import com.example.ui.components.PlatformChips
import com.example.ui.components.UrlInputCard
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.YouTubePolicyDialog
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MediaDownloadTheme
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by viewModels()

    // Android 7-9 only: saving to Pictures / Movies needs the legacy storage permission
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            MediaDownloadTheme {
                MediaDownloadApp(viewModel = viewModel, downloadViewModel = downloadViewModel)
            }
        }
    }
}

@Composable
fun MediaDownloadApp(viewModel: MainViewModel, downloadViewModel: DownloadViewModel) {
    val isArabic by viewModel.isArabic.collectAsState()
    val urlInput by viewModel.urlInput.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val extractedMedia by viewModel.extractedMedia.collectAsState()
    val downloadsHistory by viewModel.downloadsHistory.collectAsState()
    val showYouTubeBlockedDialog by viewModel.showYouTubeBlockedDialog.collectAsState()
    val clipboardDetectedUrl by viewModel.clipboardDetectedUrl.collectAsState()
    val activePlayingItem by viewModel.activePlayingItem.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val platforms by viewModel.platforms.collectAsState()
    val detectedPlatform by viewModel.detectedPlatform.collectAsState()
    val isServerOnline by viewModel.isServerOnline.collectAsState()
    val showInAppAd by viewModel.showInAppAd.collectAsState()

    // Clear the download state when the post card goes away (new link being extracted / card closed)
    LaunchedEffect(extractedMedia) {
        if (extractedMedia == null) downloadViewModel.reset()
    }

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

    // Auto inspect clipboard and refresh platforms on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkClipboardForLink()
                viewModel.loadDynamicPlatforms()
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
                            selectedIconColor = Color(0xFF141417),
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
                                                color = Color(0xFF141417),
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
                            selectedIconColor = Color(0xFF141417),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = CyberTextMuted,
                            unselectedTextColor = CyberTextMuted
                        ),
                        modifier = Modifier.testTag("tab_history")
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
                                isServerOnline = isServerOnline,
                                onToggleLanguage = { viewModel.toggleLanguage() },
                                modifier = Modifier.statusBarsPadding()
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Dynamic Live Platform Chips from Ubuntu VPS
                            PlatformChips(
                                platforms = platforms,
                                isArabic = isArabic,
                                onPlatformClick = { platform ->
                                    if (platform.sampleUrl.isNotBlank()) {
                                        viewModel.onUrlChange("https://${platform.sampleUrl}")
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            UrlInputCard(
                                url = urlInput,
                                isArabic = isArabic,
                                isLoading = isExtracting,
                                clipboardDetectedUrl = clipboardDetectedUrl,
                                detectedPlatform = detectedPlatform,
                                onUrlChange = { viewModel.onUrlChange(it) },
                                onPasteClick = { viewModel.pasteAndDownload() },
                                onClearClick = { viewModel.onUrlChange("") },
                                onSubmit = { viewModel.startExtraction() },
                                onDismissClipboard = { /* auto managed */ }
                            )

                            // Extracted Media Preview Modal / Card
                            if (extractedMedia != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    DownloadScreen(
                                        media = extractedMedia!!,
                                        viewModel = downloadViewModel,
                                        isArabic = isArabic,
                                        onDismiss = { viewModel.dismissExtractedModal() }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                    else -> {
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

            if (showInAppAd) {
                AdWebViewDialog(
                    adUrl = AdManager.ADSTERRA_SMARTLINK_URL,
                    isArabic = isArabic,
                    onDismiss = { viewModel.dismissInAppAd() }
                )
            }
        }
    }
}
