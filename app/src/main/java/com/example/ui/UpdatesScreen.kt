package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen

val TelegramCyan = Color(0xFF00E5FF)
val TelegramBlue = Color(0xFF229ED9)
val TelegramDarkNavy = Color(0xFF0A1118)
val TelegramCardNavy = Color(0xFF0F1A26)

/**
 * Draws the iconic Telegram Paper Plane vector icon on Canvas
 */
@Composable
fun TelegramPaperPlaneIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.50f)
            lineTo(w * 0.88f, h * 0.16f)
            lineTo(w * 0.70f, h * 0.84f)
            lineTo(w * 0.48f, h * 0.62f)
            lineTo(w * 0.38f, h * 0.72f)
            lineTo(w * 0.35f, h * 0.56f)
            lineTo(w * 0.66f, h * 0.34f)
            lineTo(w * 0.28f, h * 0.53f)
            close()
        }
        drawPath(path, color = tint)
    }
}

/**
 * Robust, crash-free intent launcher to open Telegram channel
 * First attempts to resolve the Telegram native app; if not installed, falls back to web browser.
 */
fun openTelegramChannel(context: Context, channelUrl: String = "https://t.me/smsm195") {
    val username = channelUrl.substringAfterLast("/").trimStart('@')
    val isAr = context.resources.configuration.locales[0].language.startsWith("ar")

    // Attempt 1: Native Telegram App intent via tg:// protocol
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (appIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(appIntent)
            return
        }
    } catch (_: Exception) {}

    // Attempt 2: Direct web URL intent (will open browser or app via intent filters)
    try {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(channelUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    } catch (e: Exception) {
        Toast.makeText(
            context,
            if (isAr) "تعذر فتح الرابط، يرجى تثبيت تليجرام أو استخدام المتصفح" else "Could not open link. Please check Telegram or browser.",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun UpdatesScreen(
    isArabic: Boolean,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val channelUrl = "https://t.me/smsm195"
    val channelHandle = "@smsm195"

    // Intercept hardware Back to return to Downloader tab
    BackHandler {
        onNavigateBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TelegramDarkNavy)
                    .border(1.dp, TelegramCyan.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TelegramCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isArabic) "تحديثات التطبيق" else "App Updates & News",
                    color = CyberTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (isArabic) "تابع جديد التحديثات والميزات المضافة أولاً بأول" else "Stay tuned for new releases and features",
                    color = CyberTextSecondary,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TelegramDarkNavy)
                    .border(1.dp, TelegramCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME.ifBlank { "1.0.11" }}",
                    color = TelegramCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Central Telegram Community Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("telegram_community_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = TelegramDarkNavy),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, TelegramCyan.copy(alpha = 0.7f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Telegram Avatar / Circular Glow Badge
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(TelegramCyan, TelegramBlue)
                            )
                        )
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0C1929)),
                    contentAlignment = Alignment.Center
                ) {
                    TelegramPaperPlaneIcon(
                        modifier = Modifier.size(40.dp),
                        tint = TelegramCyan
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Channel Title
                Text(
                    text = if (isArabic) "قناة التليجرام الرسمية" else "Official Telegram Channel",
                    color = CyberTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Handle Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF132337))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = channelHandle,
                        color = TelegramCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Explanatory Text
                Text(
                    text = if (isArabic)
                        "انضم إلى قناتنا الرسمية على تليجرام للحصول على أحدث إصدارات وتحديثات التطبيق، ومتابعة حلول استخراج الوسائط الجديدة، واقتراح أي مواقع ترغب بإضافتها!"
                    else
                        "Join our official Telegram channel to get latest app updates, follow new media extraction engine releases, and suggest new platforms!",
                    color = CyberTextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Primary CTA Button: Join Channel
                Button(
                    onClick = { openTelegramChannel(context, channelUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("join_telegram_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(TelegramCyan, TelegramBlue, ElectricBlue)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TelegramPaperPlaneIcon(
                                modifier = Modifier.size(22.dp),
                                tint = Color(0xFF03101E)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isArabic) "انضم لقناة التطبيق" else "Join App Channel",
                                color = Color(0xFF03101E),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary Copy Link & Open in Browser Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy Link Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TelegramCardNavy)
                            .border(1.dp, Color(0xFF1E3048), RoundedCornerShape(12.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Telegram Channel", channelUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    if (isArabic) "تم نسخ رابط القناة بنجاح!" else "Channel link copied to clipboard!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = CyberTextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "نسخ الرابط" else "Copy Link",
                                color = CyberTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Open Browser Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TelegramCardNavy)
                            .border(1.dp, Color(0xFF1E3048), RoundedCornerShape(12.dp))
                            .clickable {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(channelUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(browserIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Browser",
                                tint = TelegramCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "فتح في المتصفح" else "Open in Browser",
                                color = CyberTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Installed Version & Features Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = TelegramCardNavy),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2E44))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Status",
                            tint = SuccessGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "حالة الإصدار" else "Version Status",
                            color = CyberTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SuccessGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isArabic) "محدث بالكامل" else "Up to date",
                            color = SuccessGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Highlights
                UpdateFeatureRow(
                    icon = Icons.Default.RocketLaunch,
                    title = if (isArabic) "دعم كامل واستخراج مباشر لسناب شات" else "Snapchat Spotlight & Stories Engine",
                    description = if (isArabic) "فك روابط /t/ واستخراج فيديوهات Spotlight وقصص المستخدمين بأعلى دقة" else "Resolves /t/ links and extracts high-resolution Spotlight and Stories"
                )

                Spacer(modifier = Modifier.height(10.dp))

                UpdateFeatureRow(
                    icon = Icons.Default.Campaign,
                    title = if (isArabic) "معالجة أخطاء CDN وحظر 403" else "Zero-Session & CDN 403 Bypass",
                    description = if (isArabic) "معالجة ترويسات CDN وطلب الوسائط بدون قيود الجلسة أو تعارض الروابط" else "Sanitizes CDN signatures to prevent HTTP 403 Forbidden errors"
                )

                Spacer(modifier = Modifier.height(10.dp))

                UpdateFeatureRow(
                    icon = Icons.Default.Shield,
                    title = if (isArabic) "تثبيت وتحديث بدون تعارض" else "Conflict-Free Package & Keystore",
                    description = if (isArabic) "توقيع موحد ومعرف حزمة سلس لمنع أخطاء التثبيت فوق الإصدارات السابقة" else "Unified signature preventing install conflicts on existing devices"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UpdateFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0xFF132232)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TelegramCyan,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = CyberTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = CyberTextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}
