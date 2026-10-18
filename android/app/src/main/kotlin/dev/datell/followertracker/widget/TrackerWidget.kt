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
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.SyncStatus
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.formatChange
import dev.datell.followertracker.ui.formatCount
import dev.datell.followertracker.ui.compactObservationTime
import dev.datell.followertracker.ui.providerPalette
import dev.datell.followertracker.ui.trackerColorScheme
import kotlinx.coroutines.CancellationException
import kotlin.math.min

private val WidgetColors = ColorProviders(trackerColorScheme(false), trackerColorScheme(true))
internal val WidgetAccountKey = stringPreferencesKey("accountKey")
internal val WidgetKindKey = stringPreferencesKey("kind")

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
            TrackerWidgetContent(rows, singleAccount = !all)
        }
    }
}

@Composable
internal fun TrackerWidgetContent(rows: List<AccountOverview>?, singleAccount: Boolean = false, inlineOverview: Boolean = false) {
    CompositionLocalProvider(LocalWidgetEnvironment provides WidgetEnvironment.from(LocalContext.current)) {
      GlanceTheme(colors = WidgetColors) {
        if (singleAccount && !rows.isNullOrEmpty()) SingleAccount(rows.first())
        else Overview(rows, singleAccount, inlineOverview)
      }
    }
}

private fun openAccounts(context: Context) = actionStartActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRACKING)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))

@Composable
private fun SingleAccount(row: AccountOverview) {
    val size = LocalSize.current
    val fontScale = LocalWidgetEnvironment.current.fontScale
    val micro = size.height.value / fontScale < 110f || size.width.value / fontScale < 110f
    val tiny = size.height < 56.dp
    val padding = if (tiny) 4.dp else if (micro) 6.dp else 12.dp
    val iconSize = if (tiny) 8.dp else if (micro) 12.dp else 24.dp
    val showChange = size.height >= 64.dp
    val showAuxiliary = size.height >= 84.dp
    val headerHeight = if (micro) iconSize.value else 29f * fontScale
    val timeHeight = if (micro) { if (tiny) 8f else 11f } else 15f * fontScale
    val metaHeight = (if (showChange) { if (micro) 12f else 16f * fontScale } else 0f) +
        (if (showAuxiliary) { if (micro) 12f else 15f * fontScale } else 0f) +
        (if (!micro && row.comparisonAt != null) 13f * fontScale else 0f) +
        (if (!micro && row.account.status != SyncStatus.READY) 14f * fontScale else 0f)
    val countBudget = (size.height.value - padding.value * 2 - headerHeight - timeHeight - metaHeight).coerceAtLeast(1f)
    val maximum = min(if (micro) 26f else if (size.height >= 250.dp) 70f else 42f, countBudget / (1.55f * fontScale))
    val width = (size.width - padding * 2).value
    val count = countLabel(row, width, maximum, 14f)
    val auxiliary = row.latest?.following?.let { "팔로잉 " + if (micro) compactWidgetCount(it) else formatCount(it) }
        ?: row.previous?.let { "이전 " + if (micro) compactWidgetCount(it.followers) else formatCount(it.followers) }
        ?: "첫 기록"
    val fullDescription = "${row.account.connectionTitle} ${row.account.identityLabel}, 팔로워 ${row.latest?.let { formatCount(it.followers) } ?: "기록 없음"}, ${formatChange(row)}, $auxiliary, ${widgetStatus(row)}"
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(WidgetArtwork.background(row.account.provider, true)))
        .appWidgetBackground().cornerRadius(if (micro) 18.dp else 26.dp)
        .clickable(openAccounts(LocalContext.current)).semantics { contentDescription = fullDescription }.padding(padding)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProviderIcon(row.account.provider, iconSize.value)
            if (!tiny) {
                Spacer(GlanceModifier.width(if (micro) 4.dp else 7.dp))
                if (micro) {
                    Text(row.account.identityLabel, GlanceModifier.defaultWeight(), style = fixedWhiteStyle(9f), maxLines = 1)
                    if (row.account.status !in setOf(SyncStatus.READY, SyncStatus.REFRESHING)) Text("!", style = fixedWhiteStyle(9f, bold = true))
                } else Column(GlanceModifier.defaultWeight()) {
                    Text(row.account.connectionTitle, style = whiteStyle(11f, true), maxLines = 1)
                    Text(row.account.identityLabel, style = whiteStyle(9f, alpha = .9f), maxLines = 1)
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(count.first, GlanceModifier.fillMaxWidth(), style = TextStyle(color = ColorProvider(Color.White), fontSize = count.second, fontWeight = FontWeight.Bold), maxLines = 1)
        if (showChange) Text((if (micro) "" else "팔로워 · ") + formatChange(row),
            style = if (micro) fixedWhiteStyle(9f, bold = true) else whiteStyle(11f, true), maxLines = 1)
        if (showAuxiliary) Text(auxiliary, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .9f)),
            fontSize = fittedCountSize(auxiliary, width, if (micro) 9f / fontScale else 10f)), maxLines = 1)
        if (!micro) row.comparisonAt?.let { Text("${compactObservationTime(it)} 대비", style = whiteStyle(9f, alpha = .85f), maxLines = 1) }
        Spacer(GlanceModifier.defaultWeight())
        if (row.account.status != SyncStatus.READY && !micro) Text(row.account.status.label, style = whiteStyle(9f), maxLines = 1)
        val stamp = row.latest?.let { compactObservationTime(it.observedAt) } ?: "수집 전"
        val label = if (micro) stamp else "$stamp 수집"
        Text(label, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .9f)),
            fontSize = fittedCountSize(label, width, if (micro) (if (tiny) 7f else 9f) / fontScale else 10f)), maxLines = 1)
    }
}

@Composable
private fun Overview(rows: List<AccountOverview>?, singleAccount: Boolean, inline: Boolean) {
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
                    Text("소셜 모아보기", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 1)
                    Text("팔로워 · 이전 수집 대비", style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
                Text("${rows.size}개 계정", GlanceModifier.background(GlanceTheme.colors.surface).cornerRadius(12.dp).padding(horizontal = 9.dp, vertical = 5.dp),
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
            Spacer(GlanceModifier.height(12.dp))
            val fontScale = LocalWidgetEnvironment.current.fontScale
            val rowHeight = 54f * fontScale.coerceAtLeast(1f)
            val attentionHeight = rows.count { it.account.status !in setOf(SyncStatus.READY, SyncStatus.REFRESHING) } * 15f * fontScale
            val fits = size.height.value - padding.value * 2 - 48f * fontScale >= rows.size * rowHeight + attentionHeight
            if (inline || (fits && rows.size <= 8)) {
                Column(GlanceModifier.fillMaxWidth()) {
                    rows.chunked(8).forEach { chunk -> Column(GlanceModifier.fillMaxWidth()) { chunk.forEach { WidgetRow(it, dark) } } }
                }
            } else {
                // No take()/prefix(): every connected account remains reachable at every large size.
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(rows, itemId = { stableAccountId(it.account.key) }) { WidgetRow(it, dark) }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(row: AccountOverview, dark: Boolean) {
    val palette = providerPalette(row.account.provider, dark)
    val background = ColorProvider(palette.background.first())
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Column(GlanceModifier.fillMaxWidth().background(background).cornerRadius(14.dp).clickable(openAccounts(LocalContext.current))
            .semantics { contentDescription = "${row.account.connectionTitle} ${row.account.identityLabel}, 팔로워 ${row.latest?.let { formatCount(it.followers) } ?: "기록 없음"}, ${formatChange(row)}, ${widgetStatus(row)}" }
            .padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(row.account.provider, 25f)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(row.account.connectionTitle, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorProvider(palette.accent)), maxLines = 1)
                    Text(row.account.identityLabel, style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
                Spacer(GlanceModifier.width(8.dp))
                val count = countLabel(row, (LocalSize.current.width.value - 148f).coerceAtLeast(70f), 20f, 12f)
                Text(count.first, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = count.second, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            if (row.account.status !in setOf(SyncStatus.READY, SyncStatus.REFRESHING)) {
                Text(row.account.status.label, style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.error), maxLines = 1)
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val stamp = row.latest?.let { "${compactObservationTime(it.observedAt)} 수집" } ?: "수집 기록 없음"
                Text(stamp, GlanceModifier.defaultWeight(), style = TextStyle(fontSize = 8.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                Spacer(GlanceModifier.width(6.dp))
                Text(formatChange(row) + (row.comparisonAt?.let { " · ${compactObservationTime(it)} 대비" } ?: ""),
                    style = TextStyle(fontSize = 8.sp, color = ColorProvider(palette.accent)), maxLines = 1)
            }
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
            modifier = GlanceModifier.size((if (provider == Provider.REDDIT) size else size * .66f).dp))
    }
}

@Composable
private fun whiteStyle(size: Float, bold: Boolean = false, alpha: Float = 1f) = TextStyle(
    color = ColorProvider(Color.White.copy(alpha = alpha)), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun fixedWhiteStyle(size: Float, bold: Boolean = false) = whiteStyle(size / LocalWidgetEnvironment.current.fontScale, bold)

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
