package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val YellowLuxuryColorScheme = darkColorScheme(
    primary = YellowPrimary,
    onPrimary = Color(0xFF141417),
    primaryContainer = YellowPrimaryDark,
    onPrimaryContainer = Color(0xFF141417),
    secondary = ElectricBlue,
    onSecondary = Color(0xFF141417),
    secondaryContainer = Color(0xFF2C2A1C),
    onSecondaryContainer = YellowAccent,
    tertiary = YellowAccent,
    onTertiary = Color(0xFF141417),
    background = CyberBackground,
    onBackground = CyberTextPrimary,
    surface = CyberSurface,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceCard,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorder,
    outlineVariant = YellowGlow.copy(alpha = 0.35f),
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun MediaDownloadTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = YellowLuxuryColorScheme,
        typography = Typography,
        content = content
    )
}
