package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary

data class PlatformInfo(
    val nameEn: String,
    val nameAr: String,
    val color: Color,
    val samplePattern: String
)

val SupportedPlatformsList = listOf(
    PlatformInfo("TikTok", "تيك توك", Color(0xFF00F2FE), "tiktok.com/@user/video/..."),
    PlatformInfo("Instagram", "إنستغرام", Color(0xFFE1306C), "instagram.com/reel/..."),
    PlatformInfo("Twitter / X", "تويتر / X", Color(0xFF1DA1F2), "x.com/status/..."),
    PlatformInfo("Facebook", "فيسبوك", Color(0xFF1877F2), "facebook.com/watch/..."),
    PlatformInfo("CapCut", "كاب كات", Color(0xFF00E5FF), "capcut.com/template/..."),
    PlatformInfo("Snapchat", "سناب شات", Color(0xFFFFFC00), "snapchat.com/spotlight/..."),
    PlatformInfo("Pinterest", "بنترست", Color(0xFFE60023), "pinterest.com/pin/..."),
    PlatformInfo("Threads", "ثريدز", Color(0xFFFFFFFF), "threads.net/@user/post/..."),
    PlatformInfo("Reddit", "ريديت", Color(0xFFFF4500), "reddit.com/r/..."),
    PlatformInfo("Likee", "لايكي", Color(0xFFFF0055), "likee.video/@user/..."),
    PlatformInfo("Kwai", "كواي", Color(0xFFFF8C00), "kwai.com/..."),
    PlatformInfo("LinkedIn", "لينكد إن", Color(0xFF0A66C2), "linkedin.com/posts/..."),
    PlatformInfo("SoundCloud", "ساوند كلاود", Color(0xFFFF5500), "soundcloud.com/..."),
    PlatformInfo("Dailymotion", "ديلي موشن", Color(0xFF0066DC), "dailymotion.com/video/..."),
    PlatformInfo("Tumblr", "تمبلر", Color(0xFF36465D), "tumblr.com/post/...")
)

@Composable
fun PlatformChips(
    isArabic: Boolean,
    onPlatformClick: (PlatformInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isArabic) "المنصات المدعومة (15+ منصة بدون يوتيوب)" else "Supported Platforms (15+ Engines)",
                color = CyberTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SupportedPlatformsList.forEach { platform ->
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CyberSurfaceCard)
                        .border(1.dp, platform.color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable { onPlatformClick(platform) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(platform.color)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) platform.nameAr else platform.nameEn,
                            color = CyberTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
