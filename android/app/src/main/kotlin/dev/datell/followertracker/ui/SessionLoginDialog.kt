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
import android.view.ViewGroup
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.Provider
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.SyncStatus

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
    val policy = remember(provider) { AutoConnectionPolicy() }
    val navigatedProfiles = remember(provider) { mutableSetOf<String>() }
    val capturedPages = remember(provider) { mutableSetOf<String>() }
    val currentBusy by rememberUpdatedState(busy)
    val currentConnect by rememberUpdatedState(onConnect)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sessionReady by remember { mutableStateOf(false) }
    val script = remember { context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() } }
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy(); browser = null } }
    LaunchedEffect(provider, browser) {
        while (browser != null) {
            val web = browser ?: break
            now = System.currentTimeMillis()
            sessionReady = runCatching { context.appGraph.sessions.hasAuthentication(provider) }.getOrDefault(false)
            val url = web.url.orEmpty()
            val identity = context.appGraph.sessions.identity(provider)
            val pageKey = "$identity:$url"
            if (canAutoConnect(provider, url, sessionReady, loading, currentBusy || checking) && pageKey !in capturedPages) {
                val allowRequest = policy.begin(pageKey, now)
                // Hydrated pages can expose exact counts later. Re-read locally without another SNS request.
                if (!allowRequest && provider == Provider.REDDIT) { delay(1_000); continue }
                checking = allowRequest
                if (allowRequest) notice = null
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
                            notice = connectionFailureMessage(failure)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CollectionFailure) {
                    if (allowRequest) {
                        policy.failed(failure, System.currentTimeMillis())
                        notice = connectionFailureMessage(failure)
                    }
                } catch (_: Exception) {
                    if (allowRequest) notice = connectionFailureMessage(CollectionFailure(SyncStatus.FORMAT_CHANGED))
                } finally { checking = false }
            }
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
                Text("공식 페이지에서 로그인하면 내 계정을 자동으로 연결해요.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading || busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { webContext ->
                    WebView(webContext).apply {
                        // Compose's default WRAP_CONTENT height collapses percentage-height pages.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                if (busy || checking) Text("로그인한 내 계정과 팔로워 수를 확인하고 있어요.",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                (notice ?: message)?.let {
                    Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { notice = null; capturedPages.clear(); policy.requestRetry() },
                        enabled = !busy && !checking && now >= policy.nextAllowedAt,
                        modifier = Modifier.padding(horizontal = 12.dp)) { Text("다시 시도") }
                }
            }
        }
    }
}
