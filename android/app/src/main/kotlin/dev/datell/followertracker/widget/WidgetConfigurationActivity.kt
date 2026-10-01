package dev.datell.followertracker.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Account
import dev.datell.followertracker.ui.ProviderMark
import dev.datell.followertracker.ui.TrackerTheme
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent { TrackerTheme {
            var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
            var error by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { runCatching { appGraph.repository.accounts() }.onSuccess { accounts = it }.onFailure { error = true } }
            Surface(Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text("위젯에 표시할 계정", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("작은 위젯은 한 계정, 넓은 위젯은 여러 계정을 표시해요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (error) item { Text("저장된 계정을 읽지 못했어요. 앱에서 확인해주세요.", color = MaterialTheme.colorScheme.error) }
                    item { Button(onClick = { saving = true; configure(id, null) { saving = false; error = true } }, enabled = !saving && !error) { Text("모든 계정 표시") } }
                    items(accounts, key = { it.key }) { account ->
                        OutlinedCard(onClick = { if (!saving) { saving = true; configure(id, account.key) { saving = false; error = true } } }) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProviderMark(account.provider)
                                Column { Text(account.provider.title, style = MaterialTheme.typography.titleMedium); Text("@${account.username}") }
                            }
                        }
                    }
                    if (accounts.isEmpty() && !error) item { Text("아직 연결된 계정이 없어요. 위젯을 추가한 뒤 앱에서 SNS를 연결해주세요.") }
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
        } catch (_: Exception) { failure() }
    }
}
