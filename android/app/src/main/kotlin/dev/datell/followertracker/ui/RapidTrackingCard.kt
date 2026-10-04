package dev.datell.followertracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.datell.followertracker.core.RefreshPolicy
import dev.datell.followertracker.sync.RapidTracking

@Composable
fun RapidTrackingCard(state: TrackerState, compact: Boolean = false) {
    val context = LocalContext.current
    val rapid by RapidTracking.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    fun start() {
        try { RapidTracking.start(context); error = null }
        catch (_: RuntimeException) { error = "빠른 추적을 시작하지 못했어요. 잠시 후 다시 시도해주세요." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "시작·중지 상태를 확인할 수 있도록 알림을 허용해주세요."
    }
    val connected = state.accounts.any { RefreshPolicy.supportsCountRefresh(it.account) && !it.account.status.blocksAutomaticRetry }
    val toggle = {
        if (rapid.running) RapidTracking.stop(context)
        else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start()
    }
    val description = when {
        rapid.running || rapid.message != "중지됨" -> rapid.message
        connected -> "연결된 SNS를 약 1분마다 확인해요."
        else -> "추적할 SNS 계정을 연결하고 로그인 상태를 확인해주세요."
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Text("1분 빠른 추적", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ToneBadge(if (rapid.running) "실행 중" else "꺼짐", positive = rapid.running)
            }
            if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = toggle, enabled = rapid.running || connected && !state.busy, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Text(if (rapid.running) "중지" else "시작")
                }
            } else Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compact) Text("실행 알림 · 한 번에 최대 6시간 · 갱신이 지연될 수 있어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) {
                Text("앱 화면을 열어두지 않아도 실행해요. 실행 중에는 알림이 표시되며, 한 번에 최대 6시간 이용할 수 있어요. 네트워크·절전·SNS 요청 제한에 따라 늦어지거나 멈출 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rapid.lastSuccessAt?.let { Text("마지막 수집 ${compactObservationTime(it)}", style = MaterialTheme.typography.labelMedium) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!compact) Button(onClick = toggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = rapid.running || connected && !state.busy) {
                Text(if (rapid.running) "빠른 추적 중지" else "빠른 추적 시작")
            }
        }
    }
}
