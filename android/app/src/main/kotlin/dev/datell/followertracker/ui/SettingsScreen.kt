package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.sync.SyncScheduler
import dev.datell.followertracker.sync.RapidTracking
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(state: TrackerState, onReconnect: (Provider) -> Unit, onDisconnect: (String) -> Unit) {
    val context = LocalContext.current
    var interval by remember { mutableIntStateOf(SyncScheduler.interval(context)) }
    val rapid by RapidTracking.state.collectAsStateWithLifecycle()
    var rapidError by remember { mutableStateOf<String?>(null) }
    fun startRapid() {
        try { RapidTracking.start(context); rapidError = null }
        catch (_: RuntimeException) { rapidError = "빠른 추적을 시작하지 못했어요. 앱에서 다시 시도해주세요." }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRapid() else rapidError = "빠른 추적의 시작·중지 상태를 보려면 알림을 허용해주세요."
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1분 빠른 추적", style = MaterialTheme.typography.titleLarge)
                    Text("Instagram의 새 팔로워 수를 수집하면 홈 화면 위젯에 바로 반영해요. 실행 중에는 알림이 표시돼요.", style = MaterialTheme.typography.bodyMedium)
                    Text("한 번에 최대 6시간 이용할 수 있어요. SNS 요청 제한·네트워크·절전 상태에 따라 늦어지거나 중지될 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (rapid.running) rapid.message else rapidError ?: rapid.message, style = MaterialTheme.typography.bodySmall)
                    rapid.lastSuccessAt?.let { Text("마지막 수집 ${compactObservationTime(it)}", style = MaterialTheme.typography.labelSmall) }
                    Button(onClick = {
                        if (rapid.running) RapidTracking.stop(context)
                        else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startRapid()
                    }, enabled = rapid.running || !state.busy && state.accounts.any { it.account.provider == Provider.INSTAGRAM }) {
                        Text(if (rapid.running) "빠른 추적 중지" else "빠른 추적 시작")
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("수집 요청 간격", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 60, 120).forEach { minutes ->
                        FilterChip(selected = interval == minutes, onClick = { SyncScheduler.schedule(context, minutes); interval = minutes }, label = { Text(if (minutes == 120) "2시간" else "${minutes}분") })
                    } }
                    Text("빠른 추적을 끄면 이 간격으로 요청해요. 운영체제가 실행 시각을 정하며 네트워크·절전 상태에 따라 늦어질 수 있어요. 명단은 하루 간격으로 요청해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("계정 관리", style = MaterialTheme.typography.titleLarge) }
        if (state.accounts.isEmpty()) item { Text("추적 탭에서 첫 SNS를 연결해주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.accounts, key = { it.account.key }) { row ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderMark(row.account.provider)
                        Column { Text(row.account.provider.title, style = MaterialTheme.typography.titleMedium); Text("@${row.account.username}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReconnect(row.account.provider) }, enabled = !state.busy) { Text("로그인 확인") }
                        TextButton(onClick = { onDisconnect(row.account.key) }, enabled = !state.busy) { Text("연결 해제", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            EmptyCard("기기 안에서 처리해요", "SNS 로그인은 공식 웹페이지에서 진행해요. 로그인 세션과 명단을 팔로워 트래커 서버로 보내지 않아요. 세션은 WebView 저장소에, 관계 명단은 암호화해 기기에 보관해요. 위젯에는 수치와 마지막 수집 시각을 표시해요.")
        }
        item {
            Text("팔로워 트래커 0.1.0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("계정별 수집 경로를 검증하고 있어요. 서비스의 로그인·응답 방식이 바뀌면 갱신이 멈출 수 있어요.", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
