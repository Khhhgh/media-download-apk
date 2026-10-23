package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlatformInfo
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.WarningAmber

fun getPlatformColor(id: String): Color {
    return when (id.lowercase()) {
        "tiktok" -> NeonCyan
        "instagram" -> Color(0xFFE1306C)
        "facebook" -> Color(0xFF1877F2)
        "twitter" -> Color(0xFF1DA1F2)
        "pinterest" -> Color(0xFFE60023)
        "snapchat" -> Color(0xFFFFFC00)
        "reddit" -> Color(0xFFFF4500)
        "threads" -> Color(0xFFCBD5E1)
        else -> ElectricViolet
    }
}

@Composable
fun PlatformChips(
    platforms: List<PlatformInfo>,
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
                text = if (isArabic) "المنصات النشطة على السيرفر" else "Live Cloud Platforms",
                color = CyberTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            platforms.forEach { platform ->
                val pColor = getPlatformColor(platform.id)
                val isOnline = platform.enabled

                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CyberSurfaceCard)
                        .border(
                            width = 1.dp,
                            color = if (isOnline) pColor.copy(alpha = 0.35f) else WarningAmber.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onPlatformClick(platform) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("platform_chip_${platform.id}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) pColor else WarningAmber)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = platform.getDisplayName(isArabic),
                            color = CyberTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!isOnline) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isArabic) "(صيانة)" else "(Maint.)",
                                color = WarningAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
