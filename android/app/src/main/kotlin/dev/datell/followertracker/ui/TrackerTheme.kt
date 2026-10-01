package dev.datell.followertracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TrackerBlue = Color(0xFF375ED8)
val TrackerGreen = Color(0xFF168567)
val TrackerInk = Color(0xFF17243D)
val TrackerBackground = Color(0xFFF6F8FB)

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF9AAEFA), background = Color(0xFF101827), surface = Color(0xFF1B2638),
        onSurface = Color(0xFFE9EEF8), secondary = Color(0xFF70D4B4)) else lightColorScheme(
        primary = TrackerBlue, background = TrackerBackground, surface = Color.White,
        onSurface = TrackerInk, secondary = TrackerGreen, outlineVariant = Color(0xFFE4E9F2))
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
