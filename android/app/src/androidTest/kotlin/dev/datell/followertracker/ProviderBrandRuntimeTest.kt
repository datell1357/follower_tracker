package dev.datell.followertracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders synthetic artwork only; does not read or write accounts, sessions, or sync settings. */
@RunWith(AndroidJUnit4::class)
class ProviderBrandRuntimeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun officialLogosAndCardsRenderInLightAppearance() = renderGallery(false)
    @Test fun officialLogosAndCardsRenderInDarkAppearance() = renderGallery(true)

    @Test fun cardTextAndActionsRemainReadableAcrossEveryBrandGradient() {
        for (dark in listOf(false, true)) {
            val colors = trackerColorScheme(dark)
            for (provider in Provider.entries) {
                val palette = providerPalette(provider, dark)
                for (step in 0..20) {
                    val background = lerp(palette.background.first(), palette.background.last(), step / 20f)
                    for (foreground in listOf(colors.onSurface, colors.onSurfaceVariant, palette.accent, colors.error)) {
                        assertTrue("${provider.title} text contrast must be at least 4.5:1 (dark=$dark, step=$step)",
                            ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
                    }
                }
            }
        }
    }

    private fun renderGallery(dark: Boolean) {
        rule.setContent {
            TrackerTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("SNS 카드 · 디자인 샘플", style = MaterialTheme.typography.titleMedium) }
                        items(Provider.entries) { provider ->
                            ProviderCard(provider) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ProviderMark(provider)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(provider.title, style = MaterialTheme.typography.titleMedium)
                                        Text("팔로워 12,480 · 갱신 0분 전", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (provider in Provider.entries) rule.onNodeWithText(provider.title).assertIsDisplayed()
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "provider-brand-${if (dark) "dark" else "light"}.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
