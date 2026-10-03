package dev.datell.followertracker.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.datell.followertracker.widget.TrackerWidget
import dev.datell.followertracker.widget.TrackerWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WidgetScreen(state: TrackerState, onConnect: () -> Unit) {
    val context = LocalContext.current
    val canPin = remember(context) { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var wide by rememberSaveable { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A removed account must not become a hidden, stale widget configuration.
    LaunchedEffect(state.accounts) { if (selected != null && state.accounts.none { it.account.key == selected }) selected = null }
    val rows = state.accounts.filter { selected == null || it.account.key == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("앱을 열지 않아도,\n변화는 바로 눈에.", style = MaterialTheme.typography.headlineMedium)
            Text("홈 화면에서 마지막으로 수집한 기록을 확인하세요.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !wide, onClick = { wide = false }, label = { Text("작게 보기") })
                FilterChip(selected = wide, onClick = { wide = true }, label = { Text("넓게 보기") })
            }
            Text("미리보기 · 실제 크기는 홈 화면에서 조절해요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("widget-preview")) {
                Box(Modifier.padding(22.dp), contentAlignment = Alignment.Center) {
                    Card(Modifier.widthIn(max = if (wide) 420.dp else 220.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (rows.isEmpty()) {
                                Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.primary)
                                Text("첫 계정을 기다려요", style = MaterialTheme.typography.titleMedium)
                                Text("연결 후 실제 수치가 표시돼요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else rows.take(if (wide) 3 else 1).forEachIndexed { index, row ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text(row.account.connectionTitle, style = MaterialTheme.typography.labelLarge)
                                Text(row.account.identityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(row.latest?.let { formatCount(it.followers) } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(formatChange(row), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(row.latest?.let { "${compactObservationTime(it.observedAt)} 수집" } ?: "수집 기록 없음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!wide && rows.size > 1) Text("작은 위젯에는 첫 계정이 표시돼요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            SectionHeading("표시할 계정")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.selectableGroup()) {
                    WidgetAccountOption("모든 계정", "위젯 크기에 맞춰 표시해요", selected == null) { selected = null }
                    state.accounts.forEach { row ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        WidgetAccountOption(row.account.connectionTitle, row.account.identityLabel, selected == row.account.key) { selected = row.account.key }
                    }
                }
            }
        }
        item {
            if (state.accounts.isEmpty()) Button(onClick = onConnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("SNS 연결하기") }
            else Button(onClick = {
                requesting = true; error = false
                scope.launch {
                    try {
                        // The launcher's configuration screen uses the same default selection.
                        context.getSharedPreferences("widget_preferences", Context.MODE_PRIVATE).edit {
                            if (selected == null) remove("accountKey") else putString("accountKey", selected)
                        }
                        val preferences = emptyPreferences().toMutablePreferences().apply { selected?.let { this[stringPreferencesKey("accountKey")] = it } }
                        error = !GlanceAppWidgetManager(context).requestPinGlanceAppWidget(TrackerWidgetReceiver::class.java, preview = TrackerWidget(), previewState = preferences)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { requesting = false }
                }
            }, enabled = canPin && !requesting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (requesting) "확인창 여는 중" else "홈 화면에 위젯 추가")
            }
            if (error) Text("추가 요청을 열지 못했어요. 아래 방법으로 홈 화면에서 직접 추가해주세요.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        item { InfoPanel("홈 화면에서 직접 추가하기", "빈 곳 길게 누르기 → 위젯 → 팔로워 트래커를 선택하세요. 추가 화면에서 표시할 계정을 확정하고, 추가 후 가장자리를 드래그해 크기를 조절할 수 있어요.", icon = Icons.Outlined.TouchApp) }
        item { InfoPanel("마지막 수집 시각을 함께 확인하세요", "수집에 성공하면 위젯도 갱신을 요청해요. 요청 제한이나 절전으로 갱신이 멈추면 마지막 기록이 남아요. 숫자와 함께 표시된 수집 시각을 확인해주세요. 앱을 강제 중지했다면 앱 아이콘으로 한 번 다시 열어주세요.", icon = Icons.Outlined.Schedule) }
    }
}

@Composable
private fun WidgetAccountOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag("widget-option-$title").padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
