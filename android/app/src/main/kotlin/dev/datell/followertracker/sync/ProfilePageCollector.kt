package dev.datell.followertracker.sync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.*
import dev.datell.followertracker.core.*
import dev.datell.followertracker.ui.captureWebSession
import dev.datell.followertracker.ui.webCaptureFailure
import kotlinx.coroutines.*
import org.json.JSONObject

/** A fresh, bounded official profile page. Never reads login fields or exports cookies. */
class ProfilePageCollector(private val context: Context, private val sessions: SessionStore) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(account: Account, userAgent: String): String = withContext(Dispatchers.Main.immediate) {
        if (account.provider != Provider.INSTAGRAM || !account.provider.allows(account.profileUrl))
            throw CollectionFailure(SyncStatus.FOREGROUND_ONLY)
        if (!sessions.hasAuthentication(account.provider) || sessions.identity(account.provider) != account.stableId)
            throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        val loaded = CompletableDeferred<Unit>()
        var failure: CollectionFailure? = null
        val web = WebView(context)
        fun fail(error: CollectionFailure) {
            if (failure != null) return
            failure = error
            loaded.completeExceptionally(error)
            web.stopLoading()
        }
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = userAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setSupportMultipleWindows(false)
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    val path = runCatching { java.net.URI(url.orEmpty()).path.orEmpty() }.getOrDefault("")
                    if (!account.provider.allows(url.orEmpty())) fail(CollectionFailure(SyncStatus.CHECK_REQUIRED))
                    else if (Regex("(^|/)(login|challenge|checkpoint|two_factor)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path))
                        fail(CollectionFailure(if (path.contains("login", true)) SyncStatus.REAUTH_REQUIRED else SyncStatus.CHECK_REQUIRED))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    val path = request.url.path.orEmpty()
                    if (!account.provider.allows(url)) { fail(CollectionFailure(SyncStatus.CHECK_REQUIRED)); return true }
                    if (Regex("(^|/)(login|challenge|checkpoint|two_factor)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
                        fail(CollectionFailure(if (path.contains("login", true)) SyncStatus.REAUTH_REQUIRED else SyncStatus.CHECK_REQUIRED))
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (failure != null) return
                    if (!account.provider.allows(url.orEmpty())) fail(CollectionFailure(SyncStatus.CHECK_REQUIRED))
                    else loaded.complete(Unit)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    // A profile's own JSON requests can be limited even when its document was HTTP 200.
                    if (request.isForMainFrame || response.statusCode == 429 && account.provider.allows(request.url.toString()))
                        fail(CollectionFailure(statusForHttp(response.statusCode) ?: SyncStatus.FORMAT_CHANGED,
                            response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After", true) }
                                ?.value?.toLongOrNull()?.coerceIn(60, 86_400)))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(CollectionFailure(SyncStatus.OFFLINE))
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); fail(CollectionFailure(SyncStatus.CHECK_REQUIRED))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail(CollectionFailure(SyncStatus.OFFLINE)); return true
                }
            }
            val metrics = context.resources.displayMetrics
            web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            web.loadUrl(account.profileUrl, mapOf("Cache-Control" to "no-cache"))
            withTimeout(35_000) { loaded.await() }
            val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
            withTimeout(15_000) {
                while (true) {
                    failure?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    if (sessions.identity(account.provider) != account.stableId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                    val payload = captureWebSession(web, account.provider, account.stableId, script, allowRequest = false)
                    failure?.let { throw it }
                    val result = JSONObject(payload)
                    if (!result.has("error")) return@withTimeout payload
                    if (result.optString("error") != "exact_count_missing")
                        throw webCaptureFailure(result) ?: CollectionFailure(SyncStatus.FORMAT_CHANGED)
                    delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
        } catch (_: TimeoutCancellationException) {
            throw failure ?: CollectionFailure(if (loaded.isCompleted) SyncStatus.FORMAT_CHANGED else SyncStatus.OFFLINE)
        } finally {
            web.stopLoading()
            web.destroy()
            CookieManager.getInstance().flush()
        }
    }
}
