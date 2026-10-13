package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkCyberColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF03101E),
    primaryContainer = ElectricBlue,
    onPrimaryContainer = Color.White,
    secondary = ElectricBlueLight,
    onSecondary = Color.White,
    secondaryContainer = CyberSurfaceElevated,
    onSecondaryContainer = CyberTextPrimary,
    tertiary = NeonCyanDim,
    onTertiary = Color(0xFF03101E),
    background = CyberBackground,
    onBackground = CyberTextPrimary,
    surface = CyberSurface,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceCard,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorder,
    outlineVariant = CyberBorderGlow.copy(alpha = 0.3f),
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun MediaDownloadTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkCyberColorScheme,
        typography = Typography,
        content = content
    )
}
