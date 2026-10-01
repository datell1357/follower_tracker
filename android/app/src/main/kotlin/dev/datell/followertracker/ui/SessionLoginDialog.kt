package dev.datell.followertracker.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
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
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionLoginDialog(provider: Provider, busy: Boolean, message: String?, onDismiss: () -> Unit,
    onConnect: (String?, String) -> Unit) {
    val context = LocalContext.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var location by remember { mutableStateOf(provider.loginUrl) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var ownProfile by remember { mutableStateOf<String?>(null) }
    var sessionReady by remember { mutableStateOf(false) }
    val script = remember { context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() } }
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy(); browser = null } }
    LaunchedEffect(provider, browser) {
        while (browser != null) {
            sessionReady = runCatching { context.appGraph.sessions.hasAuthentication(provider) }.getOrDefault(false)
            delay(1_000)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "닫기") }
                    Column(Modifier.weight(1f)) {
                        Text("${provider.title} 연결", style = MaterialTheme.typography.titleMedium)
                        Text(runCatching { java.net.URI(location).host }.getOrNull() ?: provider.domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { notice = null; browser?.reload() }, enabled = !busy && !checking) { Icon(Icons.Outlined.Refresh, "페이지 새로고침") }
                    IconButton(onClick = { if (browser?.canGoBack() == true) browser?.goBack() }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "이전 페이지") }
                }
                Text(if (provider in setOf(Provider.INSTAGRAM, Provider.REDDIT)) "공식 페이지에서 로그인한 뒤 ‘연결 확인’을 눌러주세요."
                    else "공식 페이지에서 로그인한 뒤 내 프로필을 열고 ‘연결 확인’을 눌러주세요.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading || busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { webContext ->
                    WebView(webContext).apply {
                        browser = this
                        WebView.setWebContentsDebuggingEnabled(false)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // Keep user-initiated target=_blank login links in this bounded WebView.
                        settings.setSupportMultipleWindows(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                if (newProgress == 100) loading = false
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame) {
                                    when (loginNavigation(provider, request.url.toString())) {
                                        LoginNavigation.ALLOW -> Unit
                                        LoginNavigation.EXTERNAL_SIGN_IN -> {
                                            notice = "Google·Apple 로그인은 이 앱의 로그인 창에서 지원되지 않아요. SNS의 아이디·비밀번호 또는 이메일 로그인 방식을 선택해주세요."
                                            return true
                                        }
                                        LoginNavigation.BLOCK -> {
                                            notice = "공식 SNS 로그인 주소가 아닌 페이지로의 이동을 중단했어요."
                                            return true
                                        }
                                    }
                                }
                                return false
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true; notice = null; url?.let { location = it } }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                CookieManager.getInstance().flush()
                                url?.let { location = it }
                                sessionReady = runCatching { context.appGraph.sessions.hasAuthentication(provider) }.getOrDefault(false)
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel(); loading = false; notice = "보안 연결을 확인하지 못했어요. 기기 날짜와 네트워크를 확인한 뒤 새로고침해주세요."
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    notice = "SNS에서 페이지 요청을 거절했어요 (HTTP ${response.statusCode}). 잠시 뒤 새로고침해주세요."
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { loading = false; notice = "페이지를 열지 못했어요 (오류 ${error.errorCode}). 연결 상태를 확인한 뒤 새로고침해주세요." }
                            }
                        }
                        loadUrl(provider.loginUrl)
                    }
                })
                if (sessionReady) Text("세션 쿠키가 있어요. ‘연결 확인’으로 로그인 계정을 확인해주세요.",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                (notice ?: message)?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ownProfile?.let { url -> TextButton(onClick = { browser?.loadUrl(url); ownProfile = null; notice = null }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("내 프로필 열기") } }
                Button(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), enabled = !busy && !checking && !loading,
                    onClick = {
                        val web = browser ?: return@Button
                        if (!provider.allows(web.url.orEmpty())) { notice = "공식 SNS 페이지를 열어주세요."; return@Button }
                        if (!runCatching { context.appGraph.sessions.hasAuthentication(provider) }.getOrDefault(false)) {
                            notice = "아직 로그인 세션을 확인하지 못했어요. 공식 페이지에서 로그인을 완료해주세요."
                            return@Button
                        }
                        notice = null
                        if (provider in setOf(Provider.INSTAGRAM, Provider.REDDIT)) onConnect(null, web.settings.userAgentString)
                        else {
                            checking = true
                            val identity = context.appGraph.sessions.identity(provider)
                            val arguments = JSONObject.quote(provider.name) + "," + (identity?.let(JSONObject::quote) ?: "null")
                            web.evaluateJavascript(script + ";JSON.stringify(FollowerTrackerCapture.capture($arguments));") { encoded ->
                                checking = false
                                runCatching {
                                    val payload = JSONArray("[$encoded]").getString(0)
                                    val result = JSONObject(payload)
                                    if (result.has("error")) {
                                        ownProfile = result.optString("profileURL").takeIf { provider.allows(it) }
                                        notice = when (result.optString("error")) {
                                            "own_profile_required" -> "로그인한 내 계정의 프로필 페이지를 열어주세요."
                                            "exact_count_missing" -> "정확한 팔로워 수를 읽지 못했어요. 내 프로필이 열린 상태인지 확인해주세요."
                                            else -> "로그인 계정을 확인하지 못했어요. 직접 로그인한 뒤 내 프로필을 열어주세요."
                                        }
                                    } else onConnect(payload, web.settings.userAgentString)
                                }.onFailure { notice = "페이지의 데이터를 읽지 못했어요. 내 프로필에서 다시 확인해주세요." }
                            }
                        }
                    }) { Text(if (busy || checking) "계정 확인 중…" else "연결 확인") }
            }
        }
    }
}
