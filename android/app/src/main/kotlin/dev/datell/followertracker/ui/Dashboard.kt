package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.core.*

@Composable
fun Dashboard(state: TrackerState, onConnect: () -> Unit, onDetail: (String) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.accounts.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.Insights, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("내 계정부터 연결해보세요", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("연결한 SNS의 팔로워 수와 변화를 한곳에서 확인하세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(onClick = onConnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("SNS 연결하기")
                        }
                    }
                }
            }
        } else {
            item { SectionHeading("연결된 계정 ${state.accounts.size}개", "새로고침", !state.busy, onRefresh) }
            items(state.accounts, key = { it.account.key }) { row -> AccountCard(row) { onDetail(row.account.key) } }
        }
    }
}

@Composable
private fun AccountCard(row: AccountOverview, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(row.account.provider)
                Column(Modifier.weight(1f)) {
                    Text(row.account.provider.title, style = MaterialTheme.typography.titleMedium)
                    Text("@${row.account.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, "계정 상세", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("팔로워", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = MaterialTheme.typography.displaySmall)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ToneBadge(formatChange(row), positive = row.change?.let { it >= 0 } == true)
                    Text(if (row.comparisonAt != null) "직전 기록 대비" else "기록을 시작해요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.history.size > 1) GrowthChart(row.history, Modifier.fillMaxWidth().height(56.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                StatusLine(row.account, row.latest?.observedAt)
                Text(row.latest?.let { "마지막 수집 ${compactObservationTime(it.observedAt)}" } ?: "첫 수집을 기다리고 있어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountDetail(row: AccountOverview, busy: Boolean, onRefresh: () -> Unit, onReconnect: () -> Unit) {
    var period by rememberSaveable(row.account.key) { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val start = when (period) {
        1 -> now - 3_600_000
        2 -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        else -> Long.MIN_VALUE
    }
    val visible = row.history.filter { it.observedAt >= start }
    val comparison = if (visible.size > 1) MetricComparison.between(visible.first(), visible.last()) else null
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(row.account.provider)
                Column(Modifier.weight(1f)) {
                    Text(row.account.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${row.account.provider.title} · @${row.account.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("현재 팔로워", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(row.latest?.let { "${observationTime(it.observedAt)} 수집" } ?: "아직 수집 기록이 없어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    row.latest?.following?.let { Text("팔로잉 ${formatCount(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        }
        item {
            SectionHeading("팔로워 변화")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("최근 기록", "최근 1시간", "오늘").forEachIndexed { index, label ->
                    FilterChip(selected = period == index, onClick = { period = index }, label = { Text(label) })
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (visible.size < 2) Text("비교할 기록을 모으고 있어요", style = MaterialTheme.typography.bodyMedium)
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("선택한 기록의 변화", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ToneBadge(if (comparison == null) "비교 불가" else formatChange(comparison.change), positive = comparison?.change?.let { it >= 0 } == true)
                        }
                        GrowthChart(visible, Modifier.fillMaxWidth().height(120.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(compactObservationTime(visible.first().observedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(compactObservationTime(visible.last().observedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            StatusLine(row.account, row.latest?.observedAt)
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && row.account.status != SyncStatus.FOREGROUND_ONLY) { Text("지금 갱신") }
                OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("공식 로그인 페이지 열기") }
            }
        }
        item {
            InfoPanel("자동 갱신", when (row.account.capabilities.background) {
                Capability.OBSERVED -> "기기에서 백그라운드 수집에 성공했어요. 네트워크·절전·SNS 요청 제한에 따라 갱신 시각이 달라질 수 있어요."
                Capability.FOREGROUND_ONLY -> "현재는 공식 로그인 페이지에서 본인 프로필을 열어 갱신할 수 있어요."
                Capability.UNAVAILABLE -> "이 계정의 자동 수집 경로를 사용할 수 없어요."
                Capability.UNVERIFIED -> "연결한 뒤 예약 수집을 시도해요. 백그라운드 수집 성공은 아직 확인되지 않았어요."
            }, icon = Icons.Outlined.Schedule)
        }
        item { SectionHeading("최근 수집 기록") }
        items(visible.takeLast(14).asReversed(), key = { it.observedAt }) { metric ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(compactObservationTime(metric.observedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatCount(metric.followers), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
