package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.MediaKind
import com.example.model.MediaResult
import com.example.model.authorLabel
import com.example.model.mediaItems
import com.example.model.mediaKind
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen

/**
 * Post card + download actions with strict Scoped Storage routing:
 * - Photos -> Pictures/MediaDownloader (JPG)
 * - Videos -> Movies/MediaDownloader (MP4)
 * - Audio  -> Music/MediaDownloader (MP3)
 *
 * The button text and icon follow the post type:
 * single video, single photo, or a photo album (TikTok slideshow / Instagram carousel).
 * The green success card is drawn ONLY for [DownloadUiState.Success], which the ViewModel
 * publishes after every file was fully written to the gallery.
 */
@Composable
fun DownloadScreen(
    media: MediaResult,
    viewModel: DownloadViewModel,
    isArabic: Boolean = true,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Shows the post in the Ready state; keeps a running download / success card on recomposition
    LaunchedEffect(media) { viewModel.prepare(media) }

    val items = remember(media) { media.mediaItems() }
    val kind = remember(media) { media.mediaKind() }
    val totalItems = items.size.coerceAtLeast(1)
    val photoCount = items.count { !it.isVideo }
    val videoCount = items.count { it.isVideo }
    val isMultiple = totalItems > 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CyberSurfaceCard)
            .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(18.dp)
            .testTag("download_screen_card")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElectricBlue.copy(alpha = 0.2f))
                    .border(1.dp, ElectricBlue, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = media.platform.uppercase(),
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = CyberTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Thumbnail and media details
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CyberSurfaceElevated)
                    .border(1.dp, CyberBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                val thumbUrl = media.thumbnailUrl
                    ?: media.carouselItems.firstOrNull()?.url
                    ?: media.photoUrls.firstOrNull()

                if (!thumbUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = "Thumbnail",
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (kind == MediaKind.VIDEO) Icons.Default.PlayArrow else Icons.Default.Image,
                        contentDescription = "Preview",
                        tint = NeonCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                if (isMultiple) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.85f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$totalItems",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title.ifBlank { if (isArabic) "وسائط بدون عنوان" else "Untitled Media" },
                    color = CyberTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = media.authorLabel(),
                    color = CyberTextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (kind) {
                        MediaKind.CAROUSEL_IMAGES ->
                            if (isArabic) "$totalItems صور جاهزة للتنزيل" else "$totalItems photos ready to save"
                        MediaKind.CAROUSEL_MIXED ->
                            if (isArabic) "$totalItems ملفات جاهزة للتنزيل" else "$totalItems files ready to save"
                        MediaKind.IMAGE ->
                            if (isArabic) "صورة جاهزة للحفظ في المعرض" else "Photo ready to save"
                        MediaKind.VIDEO ->
                            if (isArabic) "فيديو جاهز للحفظ في المعرض" else "Video ready to save"
                    },
                    color = SuccessGreen,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // UI State Handling
        when (val state = uiState) {
            is DownloadUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F261C))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = SuccessGreen,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.message,
                        color = SuccessGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.resetState(); onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SuccessGreen,
                            contentColor = Color(0xFF141417)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        Text(
                            text = if (isArabic) "إغلاق" else "Done",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            is DownloadUiState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CyberBackground)
                        .border(1.dp, CyberBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.statusMessage,
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${state.percent}%",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.percent <= 0) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = NeonCyan,
                            trackColor = Color(0xFF1E2A40)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = NeonCyan,
                            trackColor = Color(0xFF1E2A40)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.speedText.isNotBlank()) state.speedText else if (isArabic) "جاري المعالجة..." else "Processing...",
                            color = CyberTextMuted,
                            fontSize = 11.sp
                        )

                        Text(
                            text = if (isArabic) "إلغاء" else "Cancel",
                            color = ErrorRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { viewModel.cancelDownload() }
                                .padding(4.dp)
                        )
                    }
                }
            }

            is DownloadUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2A1515))
                        .border(1.dp, ErrorRed.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.errorMessage,
                        color = ErrorRed,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.downloadMedia(isArabic = isArabic) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            contentColor = Color(0xFF141417)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isArabic) "إعادة المحاولة" else "Retry")
                    }
                }
            }

            is DownloadUiState.Ready, DownloadUiState.Idle -> {
                // Dynamic action button: text and icon follow the post type
                val primaryTitle = when (kind) {
                    MediaKind.VIDEO ->
                        if (isArabic) "تحميل الفيديو (HD MP4)" else "Download Video (HD MP4)"
                    MediaKind.IMAGE ->
                        if (isArabic) "تحميل الصورة (HD JPG)" else "Download Photo (HD JPG)"
                    MediaKind.CAROUSEL_IMAGES ->
                        if (isArabic) "تحميل الصور بالكامل ($totalItems صور)" else "Download All Photos ($totalItems photos)"
                    MediaKind.CAROUSEL_MIXED ->
                        if (isArabic) "تحميل الكل ($totalItems ملفات)" else "Download All ($totalItems files)"
                }

                val primarySubtext = when (kind) {
                    MediaKind.VIDEO ->
                        if (isArabic) "حفظ في ألبوم Movies" else "Saved to Movies"
                    MediaKind.IMAGE ->
                        if (isArabic) "حفظ في ألبوم Pictures" else "Saved to Pictures"
                    MediaKind.CAROUSEL_IMAGES ->
                        if (isArabic) "حفظ جميع الصور في ألبوم Pictures" else "All photos saved to Pictures"
                    MediaKind.CAROUSEL_MIXED ->
                        if (isArabic) "$photoCount صور (Pictures) • $videoCount فيديو (Movies)"
                        else "$photoCount photos (Pictures) • $videoCount videos (Movies)"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(NeonCyan, Color(0xFF00B4D8), ElectricBlue)
                            )
                        )
                        .clickable {
                            viewModel.downloadMedia(isAudioOnly = false, isArabic = isArabic)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("download_screen_unified_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF141417).copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (kind) {
                                        MediaKind.VIDEO -> Icons.Default.Videocam
                                        MediaKind.IMAGE -> Icons.Default.Image
                                        MediaKind.CAROUSEL_IMAGES, MediaKind.CAROUSEL_MIXED -> Icons.Default.PhotoLibrary
                                    },
                                    contentDescription = null,
                                    tint = Color(0xFF141417),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = primaryTitle,
                                    color = Color(0xFF141417),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = primarySubtext,
                                    color = Color(0xFF141417).copy(alpha = 0.8f),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = Color(0xFF141417),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (!media.audioUrl.isNullOrBlank() && kind == MediaKind.VIDEO) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CyberSurfaceElevated)
                            .border(1.dp, CyberBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                viewModel.downloadMedia(isAudioOnly = true, isArabic = isArabic)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = "Audio",
                                    tint = Color(0xFFFFB703),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isArabic) "تحميل الصوت فقط (MP3)" else "Audio Only (MP3)",
                                    color = CyberTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "MP3",
                                color = Color(0xFFFFB703),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
