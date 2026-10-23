package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.*
import dev.datell.followertracker.sync.ServiceConnectionFailure

data class OfficialApiRequest(val provider: Provider, val pageKey: String? = null)

fun officialApiFailureMessage(failure: CollectionFailure): String = when (failure.status) {
    SyncStatus.REAUTH_REQUIRED -> "로그인을 다시 확인해주세요. 기존 기록은 유지해요."
    SyncStatus.CHECK_REQUIRED -> "연결 대상과 조회 권한을 확인하지 못했어요. 다시 연결해주세요."
    SyncStatus.RATE_LIMITED -> "요청이 잠시 제한됐어요. 잠시 뒤 다시 확인해주세요."
    SyncStatus.FORMAT_CHANGED -> "공식 응답에서 수치를 확인하지 못했어요. 기존 기록은 유지해요."
    else -> "연결하지 못했어요. 네트워크를 확인한 뒤 다시 시도해주세요."
}
fun serviceFailureMessage(failure: ServiceConnectionFailure): String = when (failure.code) {
    "SERVICE_NOT_CONFIGURED", "SERVICE_CHANGED" -> "채널·페이지 연결 서비스를 준비하고 있어요. 서비스가 열리면 여기서 바로 연결할 수 있어요."
    "PERMISSION_REQUIRED" -> "페이지 조회 권한을 허용해주세요. Facebook에 다시 로그인해 선택할 수 있어요."
    "REAUTH_REQUIRED", "INSTALLATION_REQUIRED" -> "Facebook 로그인을 다시 확인해주세요. 기존 기록은 유지해요."
    "LOGIN_EXPIRED", "LOGIN_CANCELLED" -> "로그인이 취소되었거나 시간이 지났어요. 닫은 뒤 다시 로그인해주세요."
    "PAGE_NOT_ALLOWED" -> "선택한 페이지의 관리 권한을 확인해주세요."
    "RATE_LIMITED" -> "요청이 잠시 제한됐어요. 잠시 뒤 다시 확인해주세요."
    "BROWSER_UNAVAILABLE" -> "로그인 브라우저를 열지 못했어요. 휴대폰의 기본 브라우저를 확인해주세요."
    "CHECK_REQUIRED", "FORMAT_CHANGED" -> "연결 대상의 수치를 확인하지 못했어요. 기존 기록은 유지해요."
    else -> "연결 서비스에 닿지 못했어요. 잠시 뒤 다시 시도해주세요."
}

@Composable
fun OfficialApiScreen(state: TrackerState, configured: Boolean, onConnect: (OfficialApiRequest) -> Unit) {
    val youtube = state.accounts.firstOrNull { it.account.provider == Provider.YOUTUBE }?.account
    val pages = state.accounts.filter { it.account.accountType == AccountType.PAGE }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { InfoPanel("간단하게 연결하고 가볍게 수집", "YouTube는 채널 링크로, Facebook 페이지는 로그인 후 선택해 연결해요. 공식 데이터로 기록과 홈 위젯을 갱신해요.", icon = Icons.Outlined.DataSaverOn) }
        if (!configured) item { Text("채널·페이지 연결 서비스를 준비하고 있어요. 기존 연결의 기록과 수집은 유지돼요.", style = MaterialTheme.typography.bodyMedium) }
        item { ConnectionCard(Provider.YOUTUBE, youtube?.displayName ?: "YouTube 채널",
            "채널 링크만 입력하세요. 로그인 없이 공개 구독자 수를 확인해요.", "채널 링크로 연결", !state.busy) {
            onConnect(OfficialApiRequest(Provider.YOUTUBE)) } }
        item { ConnectionCard(Provider.FACEBOOK, "Facebook 페이지",
            "Facebook에 로그인하고 직접 관리하는 페이지를 선택하세요.", "Facebook으로 계속", !state.busy && configured) {
            onConnect(OfficialApiRequest(Provider.FACEBOOK)) } }
        items(pages, key = { it.account.key }) { row ->
            ConnectionCard(Provider.FACEBOOK, row.account.displayName,
                if (row.account.countTransport == CountTransport.OFFICIAL_API) "공식 데이터로 수집 중이에요."
                else "로그인 후 이 페이지를 선택하면 기존 기록과 위젯을 이어가요.", "다시 연결", !state.busy && configured) {
                onConnect(OfficialApiRequest(Provider.FACEBOOK, row.account.key)) }
        }
    }
}

@Composable
private fun ConnectionCard(provider: Provider, title: String, description: String, button: String,
    enabled: Boolean, onConnect: () -> Unit) {
    ProviderCard(provider) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(provider)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

@Composable
fun FacebookPageSelectionDialog(connection: FacebookConnectionUi, busy: Boolean, message: String?,
    onDismiss: () -> Unit, onRetry: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Facebook 페이지 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (connection.waiting) "Facebook에서 로그인을 마치면 관리하는 페이지를 자동으로 불러와요."
                    else "추적할 페이지를 선택하세요. 기록과 위젯에 자동으로 연결돼요.")
                if (connection.waiting || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(connection.pages, key = { it.id }) { page ->
                        OutlinedButton(onClick = { onSelect(page.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(page.name)
                        }
                    }
                }
                (message ?: connection.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("선택한 페이지의 조회 권한은 연결 서비스에 암호화해 보관해요. 로그아웃하면 해제해요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            if (!connection.waiting && connection.pages.isEmpty()) TextButton(onClick = onRetry, enabled = !busy) { Text("다시 확인") }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("닫기") } })
}
