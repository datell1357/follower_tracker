package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.core.*

@Composable
fun Dashboard(state: TrackerState, onConnect: () -> Unit, onDetail: (String) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.accounts.isEmpty()) item {
            EmptyCard("내 계정부터 연결해보세요", "팔로워 수와 변화를 기록하고 홈 화면 위젯에서 확인할 수 있어요.") {
                Button(onClick = onConnect) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("SNS 연결하기") }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("연결된 계정 ${state.accounts.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onRefresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("갱신") }
                }
            }
            items(state.accounts, key = { it.account.key }) { row ->
                AccountCard(row, onClick = { onDetail(row.account.key) })
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("홈 화면에서 바로 확인", style = MaterialTheme.typography.titleSmall)
                        Text("홈 화면을 길게 눌러 위젯 → 팔로워 트래커를 선택해주세요. 마지막으로 읽은 수와 시각을 표시해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(row: AccountOverview, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(row.account.provider)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(row.account.provider.title, style = MaterialTheme.typography.titleMedium)
                    Text("@${row.account.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.Outlined.ChevronRight, "계정 상세", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("팔로워", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(formatChange(row.change), Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (row.history.size > 1) GrowthChart(row.history, Modifier.fillMaxWidth().height(64.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusLine(row.account, row.latest?.observedAt)
                Text(if (row.previous != null) "이전 기록 대비" else "변화 기록을 시작해요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AccountDetail(row: AccountOverview, busy: Boolean, onRefresh: () -> Unit, onReconnect: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(row.account.provider)
                Column { Text(row.account.displayName, style = MaterialTheme.typography.headlineSmall); Text("@${row.account.username}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = MaterialTheme.typography.displayMedium)
            Text("팔로워 · ${row.latest?.let { relativeTime(it.observedAt) } ?: "아직 기록 없음"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.latest?.following?.let { Text("팔로잉 ${formatCount(it)}", Modifier.padding(top = 8.dp)) }
        }
        item { GrowthChart(row.history, Modifier.fillMaxWidth().height(150.dp)) }
        item {
            StatusLine(row.account, row.latest?.observedAt)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = !busy && row.account.status != SyncStatus.FOREGROUND_ONLY) { Text("지금 갱신") }
                OutlinedButton(onClick = onReconnect, enabled = !busy) { Text("로그인 페이지 열기") }
            }
        }
        item {
            Text("자동 갱신", style = MaterialTheme.typography.titleMedium)
            Text(when (row.account.capabilities.background) {
                Capability.OBSERVED -> "기기에서 백그라운드 수집이 실행됐어요. 실제 갱신 시각은 운영체제와 연결 상태에 따라 달라져요."
                Capability.FOREGROUND_ONLY -> "현재 이 SNS는 로그인 페이지에서 프로필을 열어 갱신해요."
                Capability.UNAVAILABLE -> "이 계정의 자동 수집 경로를 사용할 수 없어요."
                Capability.UNVERIFIED -> "연결 후 예약된 수집을 시도해요. 백그라운드 수집 성공은 아직 확인되지 않았어요."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item { Text("최근 기록", style = MaterialTheme.typography.titleMedium) }
        items(row.history.takeLast(14).asReversed(), key = { it.observedAt }) { metric ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(relativeTime(metric.observedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCount(metric.followers), fontWeight = FontWeight.Medium)
            }
        }
    }
}
