package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Provider
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.SyncStatus

@Composable
fun SessionLoginDialog(provider: Provider, busy: Boolean, message: String?, onDismiss: () -> Unit,
    onConnect: (String?, String) -> Unit) {
    val context = LocalContext.current
    val loginBrowser = remember(provider) { SessionLoginBrowser(provider) }
    val browser = loginBrowser.active
    var checking by remember { mutableStateOf(false) }
    val policy = remember(provider) { AutoConnectionPolicy() }
    val navigatedProfiles = remember(provider) { mutableSetOf<String>() }
    val capturedPages = remember(provider) { mutableSetOf<String>() }
    val currentBusy by rememberUpdatedState(busy)
    val currentConnect by rememberUpdatedState(onConnect)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sessionReady by remember { mutableStateOf(false) }
    val script = remember { context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() } }
    DisposableEffect(loginBrowser) { onDispose { loginBrowser.dispose() } }
    LaunchedEffect(provider, browser) {
        while (browser != null) {
            val web = browser ?: break
            now = System.currentTimeMillis()
            sessionReady = runCatching { context.appGraph.sessions.hasAuthentication(provider) }.getOrDefault(false)
            val url = web.url.orEmpty()
            val identity = context.appGraph.sessions.identity(provider)
            val pageKey = "$identity:$url"
            if (canAutoConnect(provider, url, sessionReady, loginBrowser.loading, currentBusy || checking) && pageKey !in capturedPages) {
                val allowRequest = policy.begin(pageKey, now)
                // Hydrated pages can expose exact counts later. Re-read locally without another SNS request.
                if (!allowRequest && provider == Provider.REDDIT) { delay(1_000); continue }
                checking = allowRequest
                if (allowRequest) loginBrowser.notice = null
                try {
                    val payload = captureWebSession(web, provider, identity, script, allowRequest)
                    val result = JSONObject(payload)
                    val failure = webCaptureFailure(result)
                    if (failure == null) {
                        capturedPages.add(pageKey)
                        currentConnect(payload, web.settings.userAgentString)
                    } else {
                        val profile = result.optString("profileURL")
                        if (result.optString("error") == "own_profile_required" && provider.allows(profile) &&
                            profile != web.url && navigatedProfiles.add(profile)) {
                            web.loadUrl(profile)
                        } else if (allowRequest) {
                            policy.failed(failure, System.currentTimeMillis())
                            loginBrowser.notice = webCaptureFailureMessage(result.optString("error"), failure)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CollectionFailure) {
                    if (allowRequest) {
                        policy.failed(failure, System.currentTimeMillis())
                        loginBrowser.notice = connectionFailureMessage(failure)
                    }
                } catch (_: Exception) {
                    if (allowRequest) loginBrowser.notice = connectionFailureMessage(CollectionFailure(SyncStatus.FORMAT_CHANGED))
                } finally { checking = false }
            }
            delay(1_000)
        }
    }
    Dialog(onDismissRequest = { if (!loginBrowser.closeActivePopup()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "닫기") }
                    Column(Modifier.weight(1f)) {
                        Text("${provider.title} 연결", style = MaterialTheme.typography.titleMedium)
                        Text(runCatching { java.net.URI(loginBrowser.location).host }.getOrNull() ?: provider.domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = loginBrowser::reload, enabled = !busy && !checking) { Icon(Icons.Outlined.Refresh, "페이지 새로고침") }
                    IconButton(onClick = loginBrowser::goBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "이전 페이지") }
                }
                Text("공식 페이지에서 로그인하면 내 계정을 자동으로 연결해요.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loginBrowser.loading || busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = loginBrowser::createView)
                if (busy || checking) Text("로그인한 내 계정과 팔로워 수를 확인하고 있어요.",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                (loginBrowser.notice ?: message)?.let {
                    Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { loginBrowser.notice = null; capturedPages.clear(); policy.requestRetry() },
                        enabled = !busy && !checking && now >= policy.nextAllowedAt,
                        modifier = Modifier.padding(horizontal = 12.dp)) { Text("다시 시도") }
                }
            }
        }
    }
}
