package dev.datell.followertracker.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.material3.ColorProviders
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.SyncStatus
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.formatChange
import dev.datell.followertracker.ui.formatCount
import dev.datell.followertracker.ui.compactObservationTime
import dev.datell.followertracker.ui.trackerColorScheme
import kotlinx.coroutines.CancellationException
import kotlin.math.min

private val WidgetColors = ColorProviders(trackerColorScheme(false), trackerColorScheme(true))

class TrackerWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(160.dp, 160.dp), DpSize(160.dp, 300.dp),
        DpSize(300.dp, 180.dp), DpSize(300.dp, 300.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = context.appGraph.repository
        val snapshot = runCatching { repository.widgetOverviews() }.getOrNull()
        provideContent {
            val selected = currentState<Preferences>()[stringPreferencesKey("accountKey")]
            // Glance may reuse a live composition for updates; keep its compact data reactive.
            val records by produceState(initialValue = snapshot) {
                try { repository.widgetOverviewsFlow().collect { value = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { value = null }
            }
            val rows = records?.filter { selected == null || it.account.key == selected }
            TrackerWidgetContent(rows, selected != null)
        }
    }
}

@Composable
internal fun TrackerWidgetContent(rows: List<AccountOverview>?, singleAccount: Boolean = false) {
    val single = !rows.isNullOrEmpty() && (LocalSize.current.width < 250.dp || singleAccount || rows.size == 1)
    val openTracking = Intent(LocalContext.current, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_TRACKING)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    GlanceTheme(colors = WidgetColors) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).appWidgetBackground()
            .cornerRadius(24.dp).padding(18.dp).clickable(actionStartActivity(openTracking))) {
            if (!single) {
                Text("팔로워 트래커", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.height(4.dp))
            }
            when {
                rows == null -> Text("기록을 읽지 못했어요\n앱에서 확인해주세요", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                rows.isEmpty() -> {
                    Text("SNS 연결하기", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(8.dp))
                    Text("눌러서 앱 열기", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                }
                single -> SingleAccount(rows.first())
                else -> {
                    val limit = if (LocalSize.current.height >= 280.dp) 5 else 3
                    Column { rows.take(limit).forEachIndexed { index, row -> if (index > 0) Spacer(GlanceModifier.height(2.dp)); WidgetRow(row) } }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SingleAccount(row: AccountOverview) {
    val count = row.latest?.let { formatCount(it.followers) } ?: "—"
    val tall = LocalSize.current.height >= 250.dp
    val countSize = fittedCountSize(LocalContext.current, count, LocalSize.current.width.value - 36,
        if (tall) 52f else 30f)
    Text(row.account.provider.title + " · @" + row.account.username, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
    Spacer(GlanceModifier.height(6.dp))
    if (tall) Spacer(GlanceModifier.defaultWeight())
    Text(count, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = countSize, fontWeight = FontWeight.Bold), maxLines = 1)
    Text("팔로워 · ${formatChange(row)}", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp), maxLines = 1)
    row.comparisonAt?.let { Text("비교 ${compactObservationTime(it)}", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 1) }
    Spacer(GlanceModifier.defaultWeight())
    Text(widgetStatus(row), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 2)
}

private fun fittedCountSize(context: Context, value: String, widthDp: Float, maximum: Float = 30f): TextUnit {
    val density = context.resources.displayMetrics.density
    val fontScale = context.resources.configuration.fontScale
    val paint = Paint().apply { textSize = maximum * density * fontScale; typeface = Typeface.DEFAULT_BOLD }
    val ratio = widthDp * density / paint.measureText(value).coerceAtLeast(1f)
    return min(maximum, maximum * ratio).coerceAtLeast(8f).sp
}

@Composable
private fun WidgetRow(row: AccountOverview) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.account.provider.title, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            val count = row.latest?.let { formatCount(it.followers) } ?: "—"
            Text(count, style = TextStyle(color = GlanceTheme.colors.onSurface,
                fontSize = fittedCountSize(LocalContext.current, count, LocalSize.current.width.value - 126,
                    if (LocalSize.current.height < 280.dp) 18f else 20f), fontWeight = FontWeight.Bold), maxLines = 1)
        }
        Row(GlanceModifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(widgetStatus(row), modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 1)
            Spacer(GlanceModifier.width(8.dp))
            Text(formatChange(row) + (row.comparisonAt?.let { " · ${compactObservationTime(it)} 대비" } ?: ""),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp), maxLines = 1)
        }
    }
}
private fun widgetStatus(row: AccountOverview): String {
    // A host can retain these RemoteViews after tracking stops. Relative text would become stale.
    val observed = row.latest?.observedAt?.let { "${compactObservationTime(it)} 수집" } ?: "수집 기록 없음"
    return if (row.account.status == SyncStatus.READY) observed else row.account.status.label + " · " + observed
}

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TrackerWidget() }
