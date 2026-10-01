package dev.datell.followertracker.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.SyncStatus
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.formatChange
import dev.datell.followertracker.ui.formatCount
import dev.datell.followertracker.ui.relativeTime
import kotlin.math.min

class TrackerWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(160.dp, 160.dp), DpSize(300.dp, 180.dp), DpSize(300.dp, 300.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = context.appGraph.repository
        val initial = runCatching { repository.overviews() }.getOrNull()
        provideContent {
            val selected = currentState<Preferences>()[stringPreferencesKey("accountKey")]
            val accounts by repository.accounts.collectAsState(initial = emptyList())
            val records by produceState(initialValue = initial, accounts) { value = runCatching { repository.overviews() }.getOrNull() }
            val rows = records?.filter { selected == null || it.account.key == selected }
            TrackerWidgetContent(rows, selected != null)
        }
    }
}

@Composable
internal fun TrackerWidgetContent(rows: List<AccountOverview>?, singleAccount: Boolean = false) {
    val single = !rows.isNullOrEmpty() && (LocalSize.current.width < 250.dp || singleAccount || rows.size == 1)
    GlanceTheme {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).appWidgetBackground()
            .cornerRadius(24.dp).padding(18.dp).clickable(actionStartActivity<MainActivity>())) {
            if (!single) {
                Text("팔로워 트래커", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.height(10.dp))
            }
            when {
                rows == null -> Text("기록을 읽지 못했어요\n앱에서 확인해주세요", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                rows.isEmpty() -> {
                    Text("SNS를 연결해보세요", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(8.dp))
                    Text("눌러서 앱 열기", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                }
                single -> SingleAccount(rows.first())
                else -> {
                    val limit = if (LocalSize.current.height >= 280.dp) 5 else 3
                    Column { rows.take(limit).forEach { WidgetRow(it); Spacer(GlanceModifier.height(9.dp)) } }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SingleAccount(row: AccountOverview) {
    val count = row.latest?.let { formatCount(it.followers) } ?: "—"
    val countSize = fittedCountSize(LocalContext.current, count, LocalSize.current.width.value - 36)
    Text(row.account.provider.title + " · @" + row.account.username, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
    Spacer(GlanceModifier.height(6.dp))
    Text(count, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = countSize, fontWeight = FontWeight.Bold), maxLines = 1)
    Text("팔로워 · ${formatChange(row.change)}", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp), maxLines = 1)
    Spacer(GlanceModifier.defaultWeight())
    Text(widgetStatus(row), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 2)
}

private fun fittedCountSize(context: Context, value: String, widthDp: Float): TextUnit {
    val density = context.resources.displayMetrics.density
    val fontScale = context.resources.configuration.fontScale
    val paint = Paint().apply { textSize = 30 * density * fontScale; typeface = Typeface.DEFAULT_BOLD }
    val ratio = widthDp * density / paint.measureText(value).coerceAtLeast(1f)
    return min(30f, 30f * ratio).coerceAtLeast(8f).sp
}

@Composable
private fun WidgetRow(row: AccountOverview) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(row.account.provider.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(widgetStatus(row), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 1)
        }
        Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Spacer(GlanceModifier.width(10.dp))
        Text(formatChange(row.change), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp), maxLines = 1)
    }
}
private fun widgetStatus(row: AccountOverview): String = if (row.account.status == SyncStatus.READY) relativeTime(row.latest?.observedAt)
    else row.account.status.label + " · " + relativeTime(row.latest?.observedAt)

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TrackerWidget() }
