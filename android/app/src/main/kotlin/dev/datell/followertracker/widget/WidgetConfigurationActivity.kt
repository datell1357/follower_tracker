package dev.datell.followertracker.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Account
import dev.datell.followertracker.ui.ProviderMark
import dev.datell.followertracker.ui.InfoPanel
import dev.datell.followertracker.ui.TrackerTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent { TrackerTheme {
            var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            var initialized by rememberSaveable { mutableStateOf(false) }
            var loading by remember { mutableStateOf(true) }
            var loadError by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf(false) }
            var loadRequest by remember { mutableIntStateOf(0) }
            var saving by remember { mutableStateOf(false) }
            LaunchedEffect(loadRequest) {
                loading = true; loadError = false
                try {
                    accounts = appGraph.repository.accounts()
                    if (!initialized) selected = getSharedPreferences("widget_preferences", MODE_PRIVATE).getString("accountKey", null)
                    selected = selected?.takeIf { key -> accounts.any { it.key == key } }
                    initialized = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { loadError = true }
                finally { loading = false }
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "위젯 추가 취소") }
                        Text("위젯에 표시할 계정", style = MaterialTheme.typography.titleLarge)
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { InfoPanel("홈 화면에 맞게", "작은 위젯은 한 계정, 넓은 위젯은 여러 계정을 표시해요. 마지막 수집 시각도 함께 볼 수 있어요.", icon = Icons.Outlined.Widgets) }
                        if (loading) item { CircularProgressIndicator() }
                        if (loadError) item {
                            Text("저장된 계정을 읽지 못했어요. 기존 데이터를 보존했어요.", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { loadRequest++ }) { Text("다시 읽기") }
                        }
                        if (!loading && !loadError) {
                            item { ConfigurationOption("모든 계정", "위젯 크기에 맞춰 표시해요", null, selected == null, !saving) { selected = null } }
                            items(accounts, key = { it.key }) { account ->
                                ConfigurationOption(account.provider.title, "@${account.username}", account, selected == account.key, !saving) { selected = account.key }
                            }
                            if (accounts.isEmpty()) item { Text("아직 연결된 계정이 없어요. 위젯을 추가한 뒤 앱에서 SNS를 연결해주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (saveError) item { Text("위젯을 저장하지 못했어요. 다시 시도해주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    }
                    Button(onClick = { saving = true; saveError = false; configure(id, selected) { saving = false; saveError = true } },
                        enabled = !saving && !loading && !loadError, modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp)) {
                        Text(if (saving) "추가하는 중" else "선택한 구성으로 추가")
                    }
                }
            }
        } }
    }
    private fun configure(id: Int, key: String?, failure: () -> Unit) = lifecycleScope.launch {
        try {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(id)
            updateAppWidgetState(this@WidgetConfigurationActivity, glanceId) { preferences ->
                val name = stringPreferencesKey("accountKey")
                if (key == null) preferences.remove(name) else preferences[name] = key
            }
            TrackerWidget().update(this@WidgetConfigurationActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure() }
    }
}

@Composable
private fun ConfigurationOption(title: String, detail: String, account: Account?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (account != null) ProviderMark(account.provider)
            else Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
