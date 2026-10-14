package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.DownloadProgress
import com.example.model.MediaResult
import com.example.model.QualityOption
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

@Composable
fun MediaPreviewModal(
    media: MediaResult,
    isArabic: Boolean,
    progress: DownloadProgress,
    onDownloadQuality: (QualityOption?) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CyberSurfaceCard)
            .border(1.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .padding(18.dp)
            .testTag("media_preview_modal")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header with Platform Tag & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElectricBlue.copy(alpha = 0.25f))
                        .border(1.dp, ElectricBlue, RoundedCornerShape(12.dp))
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

            // Media Info Row (Poster thumbnail + Title + Author)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail or media placeholder
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CyberSurfaceElevated)
                        .border(1.dp, CyberBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!media.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = media.thumbnailUrl,
                            contentDescription = "Thumbnail",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = NeonCyan,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title,
                        color = CyberTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!media.author.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "@${media.author}",
                            color = CyberTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isArabic) "جاهز للحفظ في ألبوم الهاتف" else "Ready to save to Gallery",
                        color = SuccessGreen,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real-Time Download Progress Section
            if (progress.isDownloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CyberBackground)
                        .border(1.dp, CyberBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "جارٍ التحميل والحفظ..." else "Downloading to Gallery...",
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${progress.progressPercent}%",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { (progress.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = NeonCyan,
                        trackColor = Color(0xFF1E2A40)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (progress.speedText.isNotBlank()) "Speed: ${progress.speedText}" else "Connecting...",
                            color = CyberTextMuted,
                            fontSize = 11.sp
                        )

                        Text(
                            text = if (isArabic) "إلغاء" else "Cancel",
                            color = ErrorRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onCancelDownload() }
                                .padding(4.dp)
                        )
                    }
                }
            } else {
                // Quality Selector Options
                Text(
                    text = if (isArabic) "اختر جودة التحميل:" else "Select Download Format:",
                    color = CyberTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (media.carouselItems.size > 1) {
                    val videoCount = media.carouselItems.count { it.format == "mp4" }
                    val photoCount = media.carouselItems.count { it.format in listOf("jpg", "jpeg", "png", "webp") }

                    // 1. Primary button: Download ALL slides (videos as MP4, photos as JPG)
                    QualityButton(
                        option = QualityOption(
                            label = if (isArabic) "تحميل جميع عناصر المنشور (${media.carouselItems.size}) [$videoCount فيديو • $photoCount صورة]"
                            else "Download All Slides (${media.carouselItems.size}) [$videoCount Video • $photoCount Photo]",
                            url = media.carouselItems.first().url,
                            format = "carousel",
                            isHd = true
                        ),
                        isArabic = isArabic,
                        onClick = {
                            onDownloadQuality(
                                QualityOption(
                                    label = "Download All Slides",
                                    url = media.carouselItems.first().url,
                                    format = "carousel",
                                    isHd = true
                                )
                            )
                        }
                    )

                    // 2. Quick filters for all videos or all photos
                    if (videoCount > 0 && photoCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        media.qualityOptions.filter { it.format in listOf("mp4", "album", "jpg") && it.label.contains("جميع") }.forEach { opt ->
                            QualityButton(
                                option = opt,
                                isArabic = isArabic,
                                onClick = { onDownloadQuality(opt) }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // 3. Individual slide items with crystal clear distinction
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isArabic) "أو اختر عنصراً محدداً للتحميل:" else "Or download a specific item:",
                        color = CyberTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    media.carouselItems.forEachIndexed { idx, item ->
                        val isVid = item.format == "mp4"
                        val typeLabel = if (isVid) (if (isArabic) "فيديو MP4" else "Video MP4")
                        else (if (isArabic) "صورة JPG" else "Photo JPG")
                        val cleanLabel = if (item.label.contains("سلايد") || item.label.contains("Slide")) item.label
                        else if (isArabic) "سلايد ${idx + 1} ($typeLabel)" else "Slide ${idx + 1} ($typeLabel)"

                        QualityButton(
                            option = QualityOption(
                                label = cleanLabel,
                                url = item.url,
                                format = item.format,
                                isHd = true
                            ),
                            isArabic = isArabic,
                            onClick = {
                                onDownloadQuality(
                                    QualityOption(
                                        label = cleanLabel,
                                        url = item.url,
                                        format = item.format,
                                        isHd = true
                                    )
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                } else if (media.qualityOptions.isNotEmpty()) {
                    // Single Item (Video / Reel / Photo)
                    media.qualityOptions.forEach { opt ->
                        QualityButton(
                            option = opt,
                            isArabic = isArabic,
                            onClick = { onDownloadQuality(opt) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                } else if (media.photoUrls.isNotEmpty()) {
                    // Photo Album fallback
                    val photoCount = media.photoUrls.size
                    val isSingle = photoCount == 1
                    QualityButton(
                        option = QualityOption(
                            label = if (isSingle) {
                                if (isArabic) "تحميل الصورة (JPG)" else "Download Photo (JPG)"
                            } else {
                                if (isArabic) "تحميل جميع الصور ($photoCount صورة JPG)"
                                else "Download All Photos ($photoCount JPG)"
                            },
                            url = media.photoUrls.first(),
                            format = if (isSingle) "jpg" else "album",
                            isHd = true
                        ),
                        isArabic = isArabic,
                        onClick = {
                            onDownloadQuality(
                                QualityOption(
                                    label = if (isSingle) "Download Photo" else "Download All Photos",
                                    url = media.photoUrls.first(),
                                    format = if (isSingle) "jpg" else "album",
                                    isHd = true
                                )
                            )
                        }
                    )
                } else {
                    // Default Single HD Video Option
                    val isVid = media.videoUrl.contains(".mp4") || !media.videoUrl.contains(".jpg")
                    val format = if (isVid) "mp4" else "jpg"
                    QualityButton(
                        option = QualityOption(
                            label = if (isVid) (if (isArabic) "فيديو بجودة عالية (HD MP4)" else "High Definition (MP4)")
                            else (if (isArabic) "صورة عالية الجودة (JPG)" else "High Quality (JPG)"),
                            url = media.videoUrl,
                            format = format,
                            isHd = true
                        ),
                        isArabic = isArabic,
                        onClick = { onDownloadQuality(QualityOption(label = "Media", url = media.videoUrl, format = format)) }
                    )
                }

                if (media.photoUrls.isEmpty() && !media.audioUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    QualityButton(
                        option = QualityOption(
                            label = if (isArabic) "تحميل الصوت فقط (MP3)" else "Audio Only (MP3)",
                            url = media.audioUrl,
                            format = "mp3"
                        ),
                        isArabic = isArabic,
                        onClick = {
                            onDownloadQuality(
                                QualityOption(
                                    label = "Audio Only",
                                    url = media.audioUrl,
                                    format = "mp3"
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityButton(
    option: QualityOption,
    isArabic: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131D2E))
            .border(1.dp, Color(0xFF23324C), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isImage = option.format in listOf("jpg", "jpeg", "png", "webp")
                val isAudio = option.format == "mp3"
                Icon(
                    imageVector = when {
                        isAudio -> Icons.Default.Audiotrack
                        isImage -> Icons.Default.Image
                        else -> Icons.Default.HighQuality
                    },
                    contentDescription = null,
                    tint = when {
                        isAudio -> Color(0xFFFF9800)
                        isImage -> Color(0xFF00E676)
                        else -> NeonCyan
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = option.label,
                    color = CyberTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElectricBlue)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "تحميل" else "Save",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
