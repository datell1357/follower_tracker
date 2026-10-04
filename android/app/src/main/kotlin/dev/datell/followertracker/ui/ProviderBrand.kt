package dev.datell.followertracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.Provider

data class ProviderPalette(val accent: Color, val background: List<Color>, val stripe: List<Color>)

fun providerPalette(provider: Provider, dark: Boolean): ProviderPalette {
    fun color(value: Long) = Color(0xFF000000 or value)
    fun palette(accent: Long, start: Long, end: Long, vararg stripe: Long) =
        ProviderPalette(color(accent), listOf(color(start), color(end)), stripe.map(::color))
    return when (provider) {
        Provider.INSTAGRAM -> if (dark) palette(0xFFADD4, 0x2D1F30, 0x30271F, 0x833AB4, 0xFD1D1D, 0xFCAF45)
            else palette(0xA83077, 0xF7E9F5, 0xFFF3E5, 0x833AB4, 0xFD1D1D, 0xFCAF45)
        Provider.TIKTOK -> if (dark) palette(0x8CDDDD, 0x123032, 0x31202A, 0x25F4EE, 0x171A20, 0xFE2C55)
            else palette(0x14636A, 0xE7F7F6, 0xFAE7EE, 0x25F4EE, 0x171A20, 0xFE2C55)
        Provider.X -> if (dark) palette(0xE8EAEF, 0x26282E, 0x202228, 0xFFFFFF, 0xAEB4BE)
            else palette(0x20242B, 0xEAEDEF, 0xF7F8FA, 0x101217, 0x67707D)
        Provider.FACEBOOK -> if (dark) palette(0x9DBEFF, 0x192B47, 0x1C2433, 0x0866FF, 0x4B91FF)
            else palette(0x064CB8, 0xE9F2FF, 0xF6F9FF, 0x0866FF, 0x4B91FF)
        Provider.REDDIT -> if (dark) palette(0xFFB088, 0x39251E, 0x2E2521, 0xFF4500, 0xFF8717)
            else palette(0xA93600, 0xFFEBE0, 0xFFF5EF, 0xFF4500, 0xFF8717)
    }
}

@Composable
fun providerPalette(provider: Provider) = providerPalette(provider, MaterialTheme.colorScheme.surface.luminance() < .5f)

@Composable
fun ProviderCard(provider: Provider, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val palette = providerPalette(provider)
    val colors = CardDefaults.cardColors(containerColor = palette.background.last(), contentColor = MaterialTheme.colorScheme.onSurface)
    val shape = RoundedCornerShape(24.dp)
    val border = BorderStroke(1.dp, palette.accent.copy(alpha = .18f))
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(palette.background))) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Brush.horizontalGradient(palette.stripe)))
            content()
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border) { body() }
    else Card(modifier = modifier, shape = shape, colors = colors, border = border) { body() }
}
