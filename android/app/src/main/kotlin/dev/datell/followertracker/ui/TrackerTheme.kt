package dev.datell.followertracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TrackerBlue = Color(0xFF3958D9)
val TrackerGreen = Color(0xFF08775D)
val TrackerInk = Color(0xFF172039)
val TrackerBackground = Color(0xFFF5F6FA)

fun trackerColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFFADBCFF), onPrimary = Color(0xFF192A75), primaryContainer = Color(0xFF253365), onPrimaryContainer = Color(0xFFE2E7FF),
        background = Color(0xFF101522), surface = Color(0xFF1B2232), surfaceContainer = Color(0xFF222B3D), surfaceVariant = Color(0xFF293247),
        onSurface = Color(0xFFEEF1FA), onSurfaceVariant = Color(0xFFBBC3D7), secondary = Color(0xFF74D5B7),
        secondaryContainer = Color(0xFF173F35), onSecondaryContainer = Color(0xFFB4F1DC),
        outlineVariant = Color(0xFF333D52)) else lightColorScheme(
        primary = TrackerBlue, background = TrackerBackground, surface = Color.White,
        onPrimary = Color.White, primaryContainer = Color(0xFFE9EDFF), onPrimaryContainer = Color(0xFF243D99),
        onSurface = TrackerInk, onSurfaceVariant = Color(0xFF5F6A80), surfaceContainer = Color(0xFFF0F2F8), surfaceVariant = Color(0xFFF0F2F8),
        secondary = TrackerGreen, secondaryContainer = Color(0xFFE1F5EF), onSecondaryContainer = Color(0xFF075742),
        outlineVariant = Color(0xFFE5E9F1))

@Composable
fun TrackerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = trackerColorScheme(darkTheme)
    val typography = Typography(
        displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
        displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium))
    MaterialTheme(colorScheme = colors, typography = typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)), content = content)
}
