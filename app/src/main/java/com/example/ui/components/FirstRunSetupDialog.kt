package com.example.ui.components

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.FirstRunProgress
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.NeonCyan

/**
 * Centered, non-dismissable first-run window shown while the engines and libraries are installed.
 * It displays a real progress bar, percentage, download speed and the current stage, and offers a
 * retry action when setup fails.
 */
@Composable
fun FirstRunSetupDialog(
    progress: FirstRunProgress?,
    error: String?,
    isArabic: Boolean,
    onRetry: () -> Unit,
    onStart: () -> Unit,
) {
    LaunchedEffect(Unit) { onStart() }

    Dialog(
        onDismissRequest = { /* non-dismissable: setup must complete */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .border(1.5.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                    .testTag("first_run_setup_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (isArabic) "جارِ تهيئة وإعداد محركات التحميل بأحدث إصدار..."
                        else "Setting up the download engines with the latest version...",
                        color = CyberTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    if (error != null) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = error,
                            color = ErrorRed,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            modifier = Modifier.testTag("first_run_retry")
                        ) {
                            Text(
                                text = if (isArabic) "إعادة المحاولة" else "Retry",
                                color = Color(0xFF03101E),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        val percent = progress?.percent?.coerceIn(0, 100) ?: 0
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            color = NeonCyan,
                            trackColor = CyberBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = progress?.stage ?: (if (isArabic) "جارِ التحضير..." else "Preparing..."),
                                color = CyberTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "$percent%",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val speed = progress?.bytesPerSecond ?: 0L
                        val total = progress?.totalBytes ?: 0L
                        if (speed > 0 || total > 0) {
                            val sizeText = if (total > 0) {
                                "${(progress?.downloadedBytes ?: 0L) / 1024} / ${total / 1024} KB"
                            } else ""
                            Text(
                                text = listOf(sizeText, formatSpeed(speed))
                                    .filter { it.isNotBlank() }
                                    .joinToString("  •  "),
                                color = CyberTextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Speed indicator is always shown in KB/s. */
private fun formatSpeed(bytesPerSecond: Long): String = "%.1f KB/s".format(bytesPerSecond / 1024.0)
