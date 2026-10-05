package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.Account
import dev.datell.followertracker.core.AccountType

@Composable
fun SettingsScreen(state: TrackerState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { RapidTrackingCard(state) }
        item { InfoPanel("관계 명단은 하루 간격으로", "전체 명단을 읽을 수 있는 계정에서 하루 간격으로 요청해요. 빠른 추적은 팔로워 수를 확인하며, 관계 명단을 매분 요청하지 않아요.", icon = Icons.Outlined.PeopleOutline) }
        item { InfoPanel("읽지 못하면 마지막 기록을 유지해요", "페이지에서 팔로워 정보를 읽지 못하면 15분을 기다린 뒤 자동 수집으로 다시 확인해요. 요청 제한은 안내된 대기를 지키며, 실제 로그인 만료나 추가 인증이 필요할 때 계정 상태에 표시해요.", icon = Icons.Outlined.Info) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountManagementScreen(state: TrackerState, onReconnect: (Account) -> Unit, onDisconnect: (String) -> Unit, onConnect: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("SNS 연결하기")
        } }
        if (state.accounts.isEmpty()) item { EmptyCard("연결된 계정이 없어요", "공식 SNS 페이지에서 로그인하면 확인 가능한 본인 계정의 수치를 자동으로 연결해요.") }
        items(state.accounts, key = { it.account.key }) { row ->
            val palette = providerPalette(row.account.provider)
            ProviderCard(row.account.provider) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderMark(row.account.provider)
                        Column(Modifier.weight(1f)) {
                            Text(row.account.connectionTitle, style = MaterialTheme.typography.titleMedium)
                            Text(row.account.identityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    StatusLine(row.account, row.latest?.observedAt)
                    row.latest?.let { Text("마지막 수집 ${observationTime(it.observedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider(color = palette.accent.copy(alpha = .16f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onReconnect(row.account) }, enabled = !state.busy,
                            border = BorderStroke(1.dp, palette.accent.copy(alpha = if (state.busy) .12f else .45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.accent)) { Text(if (row.account.accountType == AccountType.PAGE) "페이지 갱신" else "로그인 확인") }
                        TextButton(onClick = { onDisconnect(row.account.key) }, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("연결 해제") }
                    }
                }
            }
        }
        item { InfoPanel("공식 페이지에서 직접 로그인", "SNS 세션은 이 기기에 보관해요. SNS마다 개인 계정 하나를 연결하고, Facebook 페이지는 여러 개를 추가할 수 있어요. 개인 계정을 바꾸려면 기존 개인 연결을 해제해주세요.", icon = Icons.Outlined.Shield) }
    }
}
