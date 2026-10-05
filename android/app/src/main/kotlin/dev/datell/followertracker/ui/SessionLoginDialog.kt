package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.Account
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.SyncStatus

@Composable
fun SessionLoginDialog(provider: Provider, busy: Boolean, message: String?, onDismiss: () -> Unit,
    onConnect: (String?, String) -> Unit, facebookPage: Boolean = false, expectedPage: Account? = null) {
    val context = LocalContext.current
    val loginBrowser = remember(provider, facebookPage, expectedPage?.key) {
        SessionLoginBrowser(provider, expectedPage?.profileUrl ?: provider.loginUrl)
    }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var pageAddress by remember(expectedPage?.key) { mutableStateOf(expectedPage?.profileUrl.orEmpty()) }
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
            if (!facebookPage && canAutoConnect(provider, url, sessionReady, loginBrowser.loading, currentBusy || checking) && pageKey !in capturedPages) {
                val allowRequest = policy.begin(pageKey, now)
                // Hydrated pages can expose exact counts later. Re-read locally without another SNS request.
                if (!allowRequest && provider == Provider.REDDIT) { delay(1_000); continue }
                checking = allowRequest
                if (allowRequest) loginBrowser.notice = null
                try {
                    val payload = captureWebSession(web, provider, identity, script, allowRequest)
                    val result = JSONObject(payload)
                    val failure = webCaptureFailure(result, sessionIdentityAvailable = identity != null &&
                        sessionReady && identity == context.appGraph.sessions.identity(provider))
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
                        Text(if (facebookPage) "Facebook 페이지 연결" else "${provider.title} 연결", style = MaterialTheme.typography.titleMedium)
                        Text(runCatching { java.net.URI(loginBrowser.location).host }.getOrNull() ?: provider.domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = loginBrowser::reload, enabled = !busy && !checking) { Icon(Icons.Outlined.Refresh, "페이지 새로고침") }
                    IconButton(onClick = loginBrowser::goBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "이전 페이지") }
                }
                Text(if (facebookPage) "로그인 후 추적할 페이지로 이동하고 연결 확인을 눌러주세요." else "공식 페이지에서 로그인하면 내 계정을 자동으로 연결해요.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (facebookPage) OutlinedTextField(value = pageAddress, onValueChange = { pageAddress = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    singleLine = true, label = { Text("페이스북 페이지 링크") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = { IconButton(onClick = {
                        facebookPageUrl(pageAddress)?.let { url -> focus.clearFocus(); loginBrowser.notice = null; browser?.loadUrl(url) }
                    }, enabled = sessionReady && !busy && !checking && facebookPageUrl(pageAddress) != null) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "페이지 열기")
                    } })
                if (loginBrowser.loading || busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = loginBrowser::createView)
                if (busy || checking) Text(if (facebookPage) "선택한 페이지와 팔로워 수를 확인하고 있어요." else "로그인한 내 계정과 팔로워 수를 확인하고 있어요.",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                (loginBrowser.notice ?: message)?.let {
                    Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!facebookPage) TextButton(onClick = { loginBrowser.notice = null; capturedPages.clear(); navigatedProfiles.clear(); policy.requestRetry() },
                        enabled = !busy && !checking && now >= policy.nextAllowedAt,
                        modifier = Modifier.padding(horizontal = 12.dp)) { Text("다시 시도") }
                }
                if (facebookPage) Button(onClick = {
                    val web = browser ?: return@Button
                    checking = true; loginBrowser.notice = null; focus.clearFocus()
                    scope.launch {
                        try {
                            val identity = context.appGraph.sessions.identity(provider)
                            val agent = web.settings.userAgentString
                            val payload = captureConfirmedFacebookPage(web, identity, script, expectedPage?.stableId) { loginBrowser.loading }
                            val result = JSONObject(payload)
                            val failure = webCaptureFailure(result)
                            if (identity != context.appGraph.sessions.identity(provider)) loginBrowser.notice = facebookPageFailureMessage("identity_missing")
                            else if (failure == null) currentConnect(payload, agent)
                            else if (loginBrowser.notice == null) loginBrowser.notice = facebookPageFailureMessage(result.optString("error"))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: CollectionFailure) { loginBrowser.notice = loginBrowser.notice ?: when (failure.status) {
                            SyncStatus.OFFLINE -> "페이지를 읽지 못했어요. 인터넷 연결을 확인한 뒤 다시 눌러주세요."
                            else -> facebookPageFailureMessage("page_required")
                        } }
                        catch (_: Exception) { loginBrowser.notice = facebookPageFailureMessage("exact_count_missing") }
                        finally { checking = false }
                    }
                }, enabled = canAutoConnect(provider, loginBrowser.location, sessionReady, loginBrowser.loading, busy || checking),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) { Text("연결 확인") }
            }
        }
    }
}

fun facebookPageFailureMessage(error: String): String = when (error) {
    "identity_missing", "reauth_required" -> "페이스북 로그인을 완료한 뒤 페이지에서 연결 확인을 눌러주세요."
    "page_mismatch" -> "기존에 연결한 페이지를 열어주세요. 다른 페이지는 SNS 연결에서 새로 추가할 수 있어요."
    "page_identity_missing" -> "페이지 식별 정보를 읽지 못했어요. 페이지를 새로고침한 뒤 다시 확인해주세요."
    "rounded_count_only" -> "Facebook이 팔로워 수를 축약해서 표시하고 있어요. 이 화면에서는 정확한 수를 확보하지 못했어요."
    "page_required", "own_profile_required" -> "추적할 페이스북 페이지의 첫 화면을 열고 연결 확인을 눌러주세요."
    else -> "이 페이지에서 정확한 팔로워 수를 읽지 못했어요. 팔로워 수가 보이는 페이지 첫 화면에서 다시 확인해주세요."
}
