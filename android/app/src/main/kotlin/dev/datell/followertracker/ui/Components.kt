package dev.datell.followertracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatCount(count: Long) = NumberFormat.getIntegerInstance().format(count)
fun observationTime(at: Long): String = DateTimeFormatter.ofPattern("yyyy.M.d HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
fun compactObservationTime(at: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(at).atZone(zone)
    val pattern = if (date.year == java.time.Year.now(zone).value) "M/d HH:mm" else "yy/M/d HH:mm"
    return DateTimeFormatter.ofPattern(pattern).format(date)
}
fun formatChange(change: Long?) = when { change == null -> "첫 기록"; change > 0 -> "+${formatCount(change)}"; else -> formatCount(change) }
fun formatChange(row: AccountOverview): String = if (row.previous != null && row.comparison == null) "비교 불가" else formatChange(row.change)
fun relativeTime(at: Long?): String {
    if (at == null) return "아직 기록이 없어요"
    val minutes = ((System.currentTimeMillis() - at).coerceAtLeast(0) / 60_000)
    return when { minutes < 1 -> "방금 갱신"; minutes < 60 -> "${minutes}분 전 갱신"; minutes < 1440 -> "${minutes / 60}시간 전 갱신";
        else -> DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at)) }
}
fun providerColor(provider: Provider): Color = when (provider) {
    Provider.INSTAGRAM -> Color(0xFFAB467B); Provider.TIKTOK -> Color(0xFF226A70)
    Provider.X -> Color(0xFF25354D); Provider.FACEBOOK -> Color(0xFF3768D2); Provider.REDDIT -> Color(0xFFD86532)
}
@Composable
fun ProviderMark(provider: Provider, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).background(providerColor(provider).copy(alpha = .11f), RoundedCornerShape(14.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(when (provider) { Provider.INSTAGRAM -> "IG"; Provider.TIKTOK -> "Tk"; Provider.X -> "X"; Provider.FACEBOOK -> "f"; Provider.REDDIT -> "r" },
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
    }
}
@Composable
fun GrowthChart(history: List<MetricSnapshot>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "팔로워 수 변화 그래프, ${history.size}개 기록" }) {
        if (history.isEmpty()) return@Canvas
        val min = history.minOf { it.followers }.toDouble()
        val max = history.maxOf { it.followers }.toDouble()
        val span = (max - min).coerceAtLeast(1.0)
        val firstTime = history.first().observedAt
        val duration = (history.last().observedAt - firstTime).coerceAtLeast(1L).toDouble()
        val padding = 6.dp.toPx()
        for (level in 1..3) drawLine(color.copy(alpha = .08f), Offset(padding, size.height * level / 4), Offset(size.width - padding, size.height * level / 4), strokeWidth = 1.dp.toPx())
        fun point(metric: MetricSnapshot) = Offset(
            padding + ((metric.observedAt - firstTime) / duration).toFloat() * (size.width - 2 * padding),
            if (max == min) size.height / 2 else size.height - padding - ((metric.followers - min) / span).toFloat() * (size.height - 2 * padding))
        val points = history.map(::point)
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        if (points.size > 1) {
            val fill = Path().apply { addPath(path); lineTo(points.last().x, size.height); lineTo(points.first().x, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .16f), Color.Transparent)))
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(color, 3.5.dp.toPx(), points.last())
    }
}
@Composable
fun StatusLine(account: Account, at: Long?) {
    val text = if (account.status == SyncStatus.READY) relativeTime(at) else account.status.label
    val tone = if (account.status in setOf(SyncStatus.READY, SyncStatus.FOREGROUND_ONLY, SyncStatus.REFRESHING, SyncStatus.RATE_LIMITED, SyncStatus.OFFLINE)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Text(text, color = tone, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun SectionHeading(title: String, action: String? = null, enabled: Boolean = true, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

@Composable
fun ToneBadge(text: String, modifier: Modifier = Modifier, positive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = if (positive) colors.secondaryContainer else colors.primaryContainer) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
            color = if (positive) colors.onSecondaryContainer else colors.onPrimaryContainer)
    }
}

@Composable
fun InfoPanel(title: String, detail: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MenuRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 76.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun EmptyCard(title: String, detail: String, action: @Composable (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
