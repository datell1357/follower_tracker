package dev.datell.followertracker.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.SyncStatus
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.formatCount
import dev.datell.followertracker.ui.compactObservationTime
import dev.datell.followertracker.ui.providerPalette
import dev.datell.followertracker.ui.trackerColorScheme
import kotlinx.coroutines.CancellationException
import kotlin.math.min

private val WidgetColors = ColorProviders(trackerColorScheme(false), trackerColorScheme(true))
internal val WidgetAccountKey = stringPreferencesKey("accountKey")
internal val WidgetKindKey = stringPreferencesKey("kind")
internal val WidgetShowAccountNameKey = booleanPreferencesKey("showAccountName")

private data class WidgetEnvironment(val density: Float, val fontScale: Float, val dark: Boolean) {
    companion object {
        // Glance 1.1 has its own LocalContext and no Compose UI LocalConfiguration.
        // Snapshot the host resources for this RemoteViews composition; the app preview
        // recomposes it with Compose UI's reactive LocalConfiguration as a key.
        fun from(context: Context): WidgetEnvironment {
            val configuration = context.resources.configuration
            return WidgetEnvironment(context.resources.displayMetrics.density, configuration.fontScale,
                configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
    }
}
private val LocalWidgetEnvironment = staticCompositionLocalOf<WidgetEnvironment> { error("Widget environment is missing") }

// Keep this provider identity so installed widgets and their saved account selections survive.
class TrackerWidget : BaseTrackerWidget(false)
class OverviewWidget : BaseTrackerWidget(true)

abstract class BaseTrackerWidget(private val overview: Boolean) : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = context.appGraph.repository
        val snapshot = try { repository.widgetOverviews() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        provideContent {
            val preferences = currentState<Preferences>()
            val selected = preferences[WidgetAccountKey]
            // Older all-account widgets had no selection or kind. Preserve their contents in place.
            val records by produceState(initialValue = snapshot) {
                try { repository.widgetOverviewsFlow().collect { value = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { value = null }
            }
            val all = isOverviewWidget(overview, selected, preferences[WidgetKindKey])
            val rows = records?.filter { all || it.account.key == selected }
            TrackerWidgetContent(rows, singleAccount = !all, showAccountName = preferences[WidgetShowAccountNameKey] ?: false)
        }
    }
}

@Composable
internal fun TrackerWidgetContent(rows: List<AccountOverview>?, singleAccount: Boolean = false, inlineOverview: Boolean = false, showAccountName: Boolean = false) {
    CompositionLocalProvider(LocalWidgetEnvironment provides WidgetEnvironment.from(LocalContext.current)) {
      GlanceTheme(colors = WidgetColors) {
        if (singleAccount && !rows.isNullOrEmpty()) SingleAccount(rows.first(), showAccountName)
        else Overview(rows, singleAccount, inlineOverview, showAccountName)
      }
    }
}

private fun openAccounts(context: Context) = actionStartActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRACKING)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))

@Composable
private fun SingleAccount(row: AccountOverview, showAccountName: Boolean) {
    val size = LocalSize.current
    val fontScale = LocalWidgetEnvironment.current.fontScale
    // Older installed hosts can temporarily remain below the new 2×1 minimum.
    val padding = if (size.height < 60.dp) 4.dp else if (size.height < 90.dp) 8.dp else 12.dp
    val width = (size.width - padding * 2).value.coerceAtLeast(1f)
    val footerSize = min(14f, size.height.value * .22f / fontScale)
    val mainHeight = (size.height.value - padding.value * 2 - footerSize * fontScale * 1.4f - 4f).coerceAtLeast(1f)
    val iconSize = min(64f, min(mainHeight, width * .26f)).coerceAtLeast(8f)
    val attention = row.account.status !in setOf(SyncStatus.READY, SyncStatus.REFRESHING)
    val countWidth = (width - iconSize - 12f - if (attention) 12f else 0f).coerceAtLeast(1f)
    val count = countLabel(row, countWidth, min(60f, mainHeight / (1.4f * fontScale)), 20f)
    val identity = if (showAccountName) " ${row.account.identityLabel}" else ""
    val description = "${row.account.connectionTitle}$identity, ${row.account.provider.metricLabel} ${row.latest?.let { formatCount(it.followers) } ?: "기록 없음"}, ${widgetStatus(row)}"
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(WidgetArtwork.background(row.account.provider, true)))
        .appWidgetBackground().cornerRadius(24.dp).clickable(openAccounts(LocalContext.current))
        .semantics { contentDescription = description }.padding(padding)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            ProviderIcon(row.account.provider, iconSize)
            if (attention) Text("!", style = whiteStyle(min(14f, mainHeight / (1.4f * fontScale)), true))
            Spacer(GlanceModifier.width(12.dp))
            Text(count.first, GlanceModifier.defaultWeight(), style = TextStyle(color = ColorProvider(Color.White),
                fontSize = count.second, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), maxLines = 1)
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showAccountName) Text(row.account.identityLabel, GlanceModifier.defaultWeight(),
                style = whiteStyle(footerSize, alpha = .95f), maxLines = 1)
            else Spacer(GlanceModifier.defaultWeight())
            val stamp = row.latest?.let { compactObservationTime(it.observedAt) } ?: "수집 전"
            val timeWidth = if (showAccountName) width * .62f else width
            Text(stamp, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .9f)),
                fontSize = fittedCountSize(stamp, timeWidth, footerSize), textAlign = TextAlign.End), maxLines = 1)
        }
    }
}

@Composable
private fun Overview(rows: List<AccountOverview>?, singleAccount: Boolean, inline: Boolean, showAccountName: Boolean) {
    val dark = LocalWidgetEnvironment.current.dark
    val size = LocalSize.current
    val padding = if (size.height < 64.dp) 4.dp else if (size.width < 180.dp) 10.dp else 16.dp
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(WidgetArtwork.background(null, dark)))
        .appWidgetBackground().cornerRadius(26.dp).padding(padding)) {
        if (rows.isNullOrEmpty()) {
            Column(GlanceModifier.fillMaxSize().clickable(openAccounts(LocalContext.current))) {
                if (size.height < 110.dp || size.width < 110.dp) {
                    Spacer(GlanceModifier.defaultWeight())
                    val scale = LocalWidgetEnvironment.current.fontScale
                    Text(if (rows == null) "읽기 오류" else "SNS 연결", style = TextStyle(fontSize = (10f / scale).sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 1)
                    Text("앱 열기", style = TextStyle(fontSize = (8f / scale).sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    Text(if (singleAccount) "내 계정" else "소셜 모아보기", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 1)
                    Spacer(GlanceModifier.defaultWeight())
                    Text(if (rows == null) "기록을 읽지 못했어요" else "SNS 연결하기", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 2)
                    Spacer(GlanceModifier.height(6.dp))
                    Text(if (rows == null) "앱에서 확인해주세요" else "눌러서 앱 열기", style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        } else {
            Row(GlanceModifier.fillMaxWidth().clickable(openAccounts(LocalContext.current)), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text("소셜 모아보기", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 1)
                    val metric = if (rows.all { it.account.provider == Provider.YOUTUBE }) "구독자"
                        else if (rows.any { it.account.provider == Provider.YOUTUBE }) "팔로워·구독자" else "팔로워"
                    Text(metric, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
                Text("${rows.size}개 계정", GlanceModifier.background(GlanceTheme.colors.surface).cornerRadius(12.dp).padding(horizontal = 9.dp, vertical = 5.dp),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
            Spacer(GlanceModifier.height(12.dp))
            val fontScale = LocalWidgetEnvironment.current.fontScale
            val rowHeight = (if (showAccountName) 98f else 78f) * fontScale.coerceAtLeast(1f)
            val attentionHeight = rows.count { it.account.status !in setOf(SyncStatus.READY, SyncStatus.REFRESHING) } * 15f * fontScale
            val fits = size.height.value - padding.value * 2 - 48f * fontScale >= rows.size * rowHeight + attentionHeight
            if (inline || (fits && rows.size <= 8)) {
                Column(GlanceModifier.fillMaxWidth()) {
                    rows.chunked(8).forEach { chunk -> Column(GlanceModifier.fillMaxWidth()) { chunk.forEach { WidgetRow(it, dark, showAccountName) } } }
                }
            } else {
                // No take()/prefix(): every connected account remains reachable at every large size.
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(rows, itemId = { stableAccountId(it.account.key) }) { WidgetRow(it, dark, showAccountName) }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(row: AccountOverview, dark: Boolean, showAccountName: Boolean) {
    val palette = providerPalette(row.account.provider, dark)
    val identity = if (showAccountName) " ${row.account.identityLabel}" else ""
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(GlanceModifier.fillMaxWidth().background(ColorProvider(palette.background.first())).cornerRadius(16.dp)
            .clickable(openAccounts(LocalContext.current))
            .semantics { contentDescription = "${row.account.connectionTitle}$identity, ${row.account.provider.metricLabel} ${row.latest?.let { formatCount(it.followers) } ?: "기록 없음"}, ${widgetStatus(row)}" }
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(row.account.provider, 40f)
                Spacer(GlanceModifier.width(12.dp))
                val count = countLabel(row, (LocalSize.current.width.value - 110f).coerceAtLeast(1f), 32f, 20f)
                Text(count.first, GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface,
                    fontSize = count.second, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), maxLines = 1)
            }
            if (showAccountName) Text(row.account.identityLabel, GlanceModifier.fillMaxWidth(),
                style = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            Text(widgetStatus(row), GlanceModifier.fillMaxWidth(), style = TextStyle(fontSize = 12.sp, textAlign = TextAlign.End,
                color = if (row.account.status in setOf(SyncStatus.READY, SyncStatus.REFRESHING)) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.error), maxLines = 1)
        }
    }
}

@Composable
private fun ProviderIcon(provider: Provider, size: Float) {
    val backdrop = when (provider) {
        Provider.INSTAGRAM -> Color(0xFF93387B)
        Provider.X, Provider.TIKTOK -> Color(0xFF101319)
        else -> Color.White
    }
    Box(GlanceModifier.size(size.dp).background(backdrop).cornerRadius((size * .29f).dp), contentAlignment = Alignment.Center) {
        Image(ImageProvider(WidgetArtwork.icon(provider)), contentDescription = provider.title,
            modifier = GlanceModifier.size((if (provider == Provider.REDDIT) size else size * .8f).dp))
    }
}

@Composable
private fun whiteStyle(size: Float, bold: Boolean = false, alpha: Float = 1f) = TextStyle(
    color = ColorProvider(Color.White.copy(alpha = alpha)), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun countLabel(row: AccountOverview, availableWidth: Float, maximum: Float, readable: Float): Pair<String, TextUnit> {
    val count = row.latest?.followers
    val exact = count?.let(::formatCount) ?: "—"
    val fontScale = LocalWidgetEnvironment.current.fontScale
    val exactSize = fittedCountSize(exact, availableWidth, maximum)
    val label = if (exactSize.value * fontScale >= readable || count == null) exact else compactWidgetCount(count)
    return label to fittedCountSize(label, availableWidth, maximum)
}

@Composable
private fun fittedCountSize(text: String, width: Float, maximum: Float): TextUnit {
    val environment = LocalWidgetEnvironment.current
    val fontScale = environment.fontScale
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = maximum * environment.density * fontScale
    }
    // RemoteViews applies font spans and the host's system font. Leave room for their
    // wider glyphs and pixel rounding, rather than fitting the paint width to the last pixel.
    val ratio = (width.coerceAtLeast(1f) * .9f * environment.density / paint.measureText(text).coerceAtLeast(1f)).coerceAtMost(1f)
    return min(maximum * fontScale, maximum * fontScale * ratio).div(fontScale).sp
}

internal fun widgetStatus(row: AccountOverview): String {
    val observed = row.latest?.let { "${compactObservationTime(it.observedAt)} 수집" } ?: "수집 기록 없음"
    return if (row.account.status == SyncStatus.READY) observed else "${row.account.status.label} · $observed"
}

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackerWidget()
}
class OverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverviewWidget()
}
