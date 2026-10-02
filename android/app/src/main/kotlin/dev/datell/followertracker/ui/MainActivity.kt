package dev.datell.followertracker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.datell.followertracker.core.Provider
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    private val model: TrackerViewModel by viewModels()
    private var openTrackingRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_OPEN_TRACKING) openTrackingRequest++
        enableEdgeToEdge()
        setContent { TrackerTheme { TrackerApp(model, openTrackingRequest) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_TRACKING) openTrackingRequest++
    }
    override fun onResume() {
        super.onResume()
        model.refreshWidgets()
    }
    companion object {
        const val ACTION_OPEN_TRACKING = "dev.datell.followertracker.OPEN_TRACKING"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerApp(model: TrackerViewModel, openTrackingRequest: Int = 0) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var morePage by rememberSaveable { mutableStateOf<String?>(null) }
    var returnPage by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<Provider?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val screenStates = rememberSaveableStateHolder()
    val page = MorePage.entries.firstOrNull { it.name == morePage }
    fun openMore(next: MorePage) { tab = 3; morePage = next.name; returnPage = null }
    fun back() { if (morePage != null) { morePage = returnPage; returnPage = null } else tab = 0 }
    BackHandler(enabled = tab != 0 || morePage != null) { back() }
    LaunchedEffect(openTrackingRequest) {
        if (openTrackingRequest > 0) { tab = 0; morePage = null; returnPage = null; detail = null }
    }
    LaunchedEffect(state.message, login) {
        if (login == null) state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("홈" to Icons.Outlined.Home, "관계" to Icons.Outlined.PeopleOutline, "위젯" to Icons.Outlined.Widgets, "더보기" to Icons.Outlined.GridView).forEachIndexed { i, item ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; morePage = null; returnPage = null }, icon = { Icon(item.second, null) }, label = { Text(item.first) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 3 && page != null) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "이전 화면") }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (tab == 3 && page != null) page.title else listOf("팔로워 트래커", "관계 분석", "홈 위젯", "더보기")[tab], style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (tab == 3 && page != null) page.subtitle else listOf("나의 SNS, 작은 변화까지", "맞팔과 미관측 기록을 한눈에", "보고 싶은 기록을 홈 화면에", "내 계정과 이용 안내")[tab],
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tab == 0) FilledTonalIconButton(onClick = { picking = true }, enabled = !state.busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Outlined.Add, "SNS 연결") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            screenStates.SaveableStateProvider(if (tab == 3) "more-${page?.name ?: "root"}" else "tab-$tab") {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                state.storageError -> Box(Modifier.padding(24.dp)) { EmptyCard("기록을 읽지 못했어요", "저장된 데이터를 보존했어요. 앱을 다시 열어 확인해주세요.") }
                tab == 0 -> Dashboard(state, onConnect = { picking = true }, onDetail = { detail = it }, onRefresh = { model.refresh() }, onWidgets = { tab = 2 })
                tab == 1 -> RelationshipScreen(state, onSelect = model::select, onRefresh = model::relationships, onConnect = { picking = true })
                tab == 2 -> WidgetScreen(state, onConnect = { picking = true })
                else -> when (page) {
                    MorePage.SETTINGS -> SettingsScreen(state)
                    MorePage.ACCOUNTS -> AccountManagementScreen(state, onReconnect = { login = it }, onDisconnect = { disconnect = it }, onConnect = { picking = true })
                    MorePage.SUPPORT -> SupportScreen(onHelp = { returnPage = MorePage.SUPPORT.name; morePage = MorePage.HELP.name }, onPrivacy = { returnPage = MorePage.SUPPORT.name; morePage = MorePage.PRIVACY.name })
                    MorePage.PRIVACY -> PrivacyScreen()
                    MorePage.HELP -> HelpScreen()
                    null -> MoreScreen(state, onPage = ::openMore)
                }
            }
            }
        }
    }
    if (picking) ModalBottomSheet(onDismissRequest = { picking = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("어떤 SNS를 연결할까요?", style = MaterialTheme.typography.headlineSmall)
            Text("공식 페이지에서 로그인하면 본인 계정을 확인해 자동으로 연결해요. 세션은 이 기기에 보관돼요.", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(Provider.entries) { provider ->
                val connected = state.accounts.any { it.account.provider == provider }
                ListItem(headlineContent = { Text(provider.title) }, supportingContent = { Text(if (connected) "연결됨 · 로그인 확인" else if (provider == Provider.INSTAGRAM) "팔로워 수 · 위젯 · 빠른 추적" else "로그인 후 수집 가능 여부를 확인해요") },
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
