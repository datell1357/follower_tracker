package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.datell.followertracker.core.*

data class OfficialApiRequest(val provider: Provider, val pageKey: String? = null)

fun officialApiFailureMessage(failure: CollectionFailure): String = when (failure.status) {
    SyncStatus.REAUTH_REQUIRED -> "API 키·페이지 토큰이 만료되었거나 유효하지 않아요. 새로 발급한 정보로 연결해주세요."
    SyncStatus.CHECK_REQUIRED -> "API 활성화·조회 권한과 연결 대상을 확인해주세요. Facebook은 해당 페이지의 액세스 토큰이 필요해요."
    SyncStatus.RATE_LIMITED -> "공식 API의 호출 한도에 도달했어요. ${failure.retryAfterSeconds?.let { "약 ${(it + 59) / 60}분 뒤 다시 확인해주세요." } ?: "잠시 뒤 다시 확인해주세요."}"
    SyncStatus.FORMAT_CHANGED -> "공식 API 응답에서 수치를 확인하지 못했어요. 이전 기록은 유지해요."
    else -> "공식 API에 연결하지 못했어요. 네트워크를 확인한 뒤 다시 시도해주세요."
}

@Composable
fun OfficialApiScreen(state: TrackerState, onConnect: (OfficialApiRequest) -> Unit) {
    val youtube = state.accounts.firstOrNull { it.account.provider == Provider.YOUTUBE }?.account
    val pages = state.accounts.filter { it.account.accountType == AccountType.PAGE }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { InfoPanel("필요한 숫자만 가볍게", "공식 API로 연결하면 웹페이지를 열지 않고 팔로워·구독자 수만 받아요. 일반 15분 수집과 빠른 추적, 기존 홈 위젯에 적용돼요.", icon = Icons.Outlined.DataSaverOn) }
        item { ApiConnectionCard(Provider.YOUTUBE, youtube?.displayName ?: "YouTube 채널",
            youtube?.countTransport == CountTransport.OFFICIAL_API,
            "Google Cloud에서 발급한 YouTube Data API 키가 필요해요. 구독자 1,000명 이상은 공식 API도 축약 수치예요.",
            state.busy) { onConnect(OfficialApiRequest(Provider.YOUTUBE)) } }
        items(pages, key = { it.account.key }) { row ->
            ApiConnectionCard(Provider.FACEBOOK, row.account.displayName,
                row.account.countTransport == CountTransport.OFFICIAL_API,
                "이 페이지의 조회 권한이 있는 페이지 액세스 토큰으로 전환해요. 기존 기록과 위젯을 이어가요.",
                state.busy) { onConnect(OfficialApiRequest(Provider.FACEBOOK, row.account.key)) }
        }
        item { OutlinedButton(onClick = { onConnect(OfficialApiRequest(Provider.FACEBOOK)) },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Facebook 페이지 API 연결 추가") } }
        item { Text("개발자 설정 · Google Cloud와 Meta 개발자 앱에서 발급한 키·페이지 토큰을 이 기기에 암호화해 저장해요. Facebook 로그인 쿠키를 API 토큰으로 사용하지 않아요. 권한이 만료되면 여기서 다시 연결해주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ApiConnectionCard(provider: Provider, title: String, connected: Boolean, description: String,
    busy: Boolean, onConnect: () -> Unit) {
    ProviderCard(provider) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(provider)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(if (connected) "공식 API 사용 중" else "API 연결 준비", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "API 연결 확인·변경" else "공식 API로 연결")
            }
        }
    }
}

@Composable
fun OfficialApiDialog(request: OfficialApiRequest, account: Account?, busy: Boolean, message: String?,
    onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    val youtube = request.provider == Provider.YOUTUBE
    var address by remember(request) { mutableStateOf(if (youtube) account?.profileUrl.orEmpty() else "") }
    // Secrets must not survive activity state saving, screenshots or a dismissed dialog.
    var credential by remember(request) { mutableStateOf("") }
    val alreadyConfigured = account?.countTransport == CountTransport.OFFICIAL_API
    AlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (youtube) "YouTube 공식 API 연결" else "Facebook 페이지 API 연결") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (youtube) OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("YouTube 채널 링크") }, placeholder = { Text("youtube.com/@채널이름") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                else Text(account?.let { "${it.displayName} 페이지의 토큰을 입력해주세요." }
                    ?: "본인이 관리하는 페이지의 액세스 토큰을 입력하면 해당 페이지를 확인해 연결해요.")
                OutlinedTextField(value = credential, onValueChange = { credential = it }, singleLine = true,
                    label = { Text(if (youtube) "YouTube Data API 키" else "페이지 액세스 토큰") },
                    supportingText = { Text(if (alreadyConfigured) "비워두면 저장된 키·토큰으로 다시 확인해요." else "검증에 성공한 키·토큰만 이 기기에 저장해요.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                Text(if (youtube) "YouTube Data API v3를 활성화한 키를 사용해주세요. API 제한과 Android 앱 제한을 설정할 수 있어요."
                    else "Meta 개발자 앱의 pages_read_engagement 권한이 필요해요. 사용자 토큰·앱 시크릿은 입력하지 마세요. 페이지 토큰은 만료되거나 권한이 철회될 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(address, credential) }, enabled = !busy &&
            (credential.isNotBlank() || alreadyConfigured) && (!youtube || YouTubePublicChannel.target(address) != null)) { Text("확인하고 연결") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}
