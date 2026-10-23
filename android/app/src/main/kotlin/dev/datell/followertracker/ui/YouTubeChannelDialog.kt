package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.core.YouTubePublicChannel

@Composable
fun YouTubeChannelDialog(initialAddress: String, busy: Boolean, message: String?, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var address by remember { mutableStateOf(initialAddress) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("YouTube 채널 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("채널 링크만 입력하면 로그인 없이 공개 구독자 수를 추적해요.")
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("YouTube 채널 링크") }, placeholder = { Text("youtube.com/@채널이름") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                Text("구독자 1,000명 이상은 유튜브의 축약 수치예요. 앱과 위젯에 ‘약’으로 표시하며 1명 단위 변화는 확인할 수 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(address) }, enabled = !busy && YouTubePublicChannel.target(address) != null) { Text("연결") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}
