package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.*

@Composable
fun RelationshipScreen(state: TrackerState, onSelect: (String) -> Unit, onRefresh: () -> Unit, onConnect: () -> Unit) {
    var category by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var reverse by remember { mutableStateOf(false) }
    val row = state.accounts.firstOrNull { it.account.key == state.selectedKey }
    val report = state.report
    val labels = listOf("맞팔 아님", "언팔로우 추정", "맞팔")
    val members = when (category) { 0 -> if (reverse) report?.youDoNotFollowBack else report?.notFollowingBack;
        1 -> emptyList(); else -> report?.mutual }.orEmpty()
        .filter { search.isBlank() || it.username.contains(search, true) || it.displayName.contains(search, true) }
    val changes = state.relationshipChanges.filter { search.isBlank() || it.member.username.contains(search, true) || it.member.displayName.contains(search, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (row == null) {
            item { EmptyCard("연결된 계정이 없어요", "SNS를 연결하면 읽을 수 있는 명단으로 관계를 비교해요.") { Button(onClick = onConnect) { Text("SNS 연결") } } }
        } else {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account -> FilterChip(selected = account.account.key == row.account.key, onClick = { onSelect(account.account.key) }, label = { Text(account.account.provider.title) }) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("@${row.account.username}", style = MaterialTheme.typography.titleMedium)
                        Text(report?.let { relativeTime(it.comparedAt) } ?: "아직 비교 기록 없음", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = onRefresh, enabled = !state.busy && row.account.provider == Provider.INSTAGRAM) { Text("명단 갱신") }
                }
            }
            if (row.account.provider != Provider.INSTAGRAM) item {
                EmptyCard("명단 수집을 확인 중이에요", "이 SNS의 전체 팔로워·팔로잉 명단 접근은 아직 검증되지 않았어요. 팔로워 수 기록은 추적 탭에서 확인해주세요.")
            } else {
                row.account.relationshipStatus?.takeIf { it != SyncStatus.READY }?.let { status -> item {
                    Text(status.label + if (report != null) " · 마지막 완료된 비교를 표시해요." else " · 완료된 명단이 있어야 비교할 수 있어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } }
                item {
                    PrimaryScrollableTabRow(selectedTabIndex = category, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.background) {
                        labels.forEachIndexed { i, label -> Tab(selected = category == i, onClick = { category = i; search = "" }, text = { Text(label) }) }
                    }
                }
                if (report == null) item {
                    EmptyCard("첫 명단을 기다리고 있어요", "전체 명단을 끝까지 읽은 뒤 비교 결과를 표시해요. 일부 명단으로 언팔로우를 판단하지 않아요.") {
                        OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("첫 명단 읽기") }
                    }
                } else {
                    if (category == 0) item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !reverse, onClick = { reverse = false }, label = { Text("나를 팔로우하지 않음") })
                        FilterChip(selected = reverse, onClick = { reverse = true }, label = { Text("내가 팔로우하지 않음") })
                    } }
                    item {
                        Text(when (category) {
                            0 -> if (reverse) "나를 팔로우하지만 내가 팔로우하지 않는 계정이에요." else "내가 팔로우하지만 나를 팔로우하지 않는 계정이에요."
                            1 -> if (report.baseline) "첫 기록은 비교 기준이에요. 다음 완료된 명단부터 변화를 확인할 수 있어요." else "완료된 명단에서 사라진 기록과 이후 확인 결과예요. 다시 보인 기록도 보존해요. 언팔로우·계정 삭제·비활성화의 원인은 구별할 수 없어요."
                            else -> "서로 팔로우하는 계정이에요."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("이름 또는 사용자 이름 검색") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
                    item { Text(if (category == 1) "${changes.size}개 기록" else "${members.size}명", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if ((category == 1 && changes.isEmpty()) || (category != 1 && members.isEmpty())) item {
                        EmptyCard("표시할 계정이 없어요", if (search.isNotEmpty()) "검색어를 바꿔보세요." else "현재 완료된 명단 기준이에요.")
                    }
                    if (category == 1) items(changes, key = { it.id }) { change ->
                        RelationshipChangeRow(change)
                    } else items(members, key = { it.id }) { member ->
                        ListItem(headlineContent = { Text(member.displayName) }, supportingContent = { Text("@${member.username}") })
                    }
                }
            }
        }
    }
}

@Composable
fun RelationshipChangeRow(change: RelationshipChange) {
    ListItem(headlineContent = { Text(change.member.displayName) }, supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("@${change.member.username}")
            Text(change.state.label, color = if (change.state == RelationshipChangeState.REOBSERVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("미관측: ${observationTime(change.detectedAt)}", style = MaterialTheme.typography.labelSmall)
            Text("확인: ${observationTime(change.checkedAt)} · ${change.absenceChecks}회 미관측", style = MaterialTheme.typography.labelSmall)
        }
    })
}
