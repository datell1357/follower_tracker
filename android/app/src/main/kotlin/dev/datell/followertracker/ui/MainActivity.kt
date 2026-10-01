package dev.datell.followertracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.datell.followertracker.core.Provider
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    private val model: TrackerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrackerTheme { TrackerApp(model) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerApp(model: TrackerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<Provider?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, login) {
        if (login == null) state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("추적" to Icons.Outlined.Insights, "관계" to Icons.Outlined.PeopleOutline, "설정" to Icons.Outlined.Tune).forEachIndexed { i, item ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(listOf("팔로워 트래커", "나의 연결", "내 기기에서 관리")[tab], style = MaterialTheme.typography.headlineSmall)
                    Text(listOf("작은 변화도 한눈에", "완료된 명단으로 비교해요", "모든 기능을 무료로 이용해요")[tab],
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tab == 0) FilledIconButton(onClick = { picking = true }, enabled = !state.busy) { Icon(Icons.Outlined.Add, "SNS 연결") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                state.storageError -> Box(Modifier.padding(24.dp)) { EmptyCard("기록을 읽지 못했어요", "저장된 데이터를 보존했어요. 앱을 다시 열어 확인해주세요.") }
                tab == 0 -> Dashboard(state, onConnect = { picking = true }, onDetail = { detail = it }, onRefresh = { model.refresh() })
                tab == 1 -> RelationshipScreen(state, onSelect = model::select, onRefresh = model::relationships, onConnect = { picking = true })
                else -> SettingsScreen(state, onReconnect = { login = it }, onDisconnect = { disconnect = it })
            }
        }
    }
    if (picking) ModalBottomSheet(onDismissRequest = { picking = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("SNS 연결", style = MaterialTheme.typography.headlineSmall)
            Text("공식 페이지에서 직접 로그인해주세요. 세션은 이 기기에 보관돼요.", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(Provider.entries) { provider ->
                val connected = state.accounts.any { it.account.provider == provider }
                ListItem(headlineContent = { Text(provider.title) }, supportingContent = { Text(if (connected) "연결됨 · 로그인 확인" else "내 계정 연결") },
                    leadingContent = { ProviderMark(provider) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    modifier = Modifier.clickableSafe { picking = false; model.dismissMessage(); login = provider })
            }
        }
    }
    login?.let { provider ->
        SessionLoginDialog(provider, state.busy, state.message,
            onDismiss = { loginJob?.cancel(); loginJob = null; login = null; model.dismissMessage() },
            onConnect = { payload, agent -> loginJob = model.connect(provider, payload, agent) { login = null; loginJob = null } })
    }
    detail?.let { key -> state.accounts.firstOrNull { it.account.key == key }?.let { row ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            AccountDetail(row, state.busy, onRefresh = { model.refresh(key) }, onReconnect = { detail = null; login = row.account.provider })
        }
    } }
    disconnect?.let { key ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("연결을 해제할까요?") },
            text = { Text("이 SNS의 로그인 세션과 기기에 저장한 추적·관계 기록을 삭제해요. 다시 연결하면 새 기록부터 시작해요.") },
            confirmButton = { TextButton(onClick = { disconnect = null; model.disconnect(key) }) { Text("연결 해제") } },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text("유지하기") } })
    }
}

fun Modifier.clickableSafe(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
