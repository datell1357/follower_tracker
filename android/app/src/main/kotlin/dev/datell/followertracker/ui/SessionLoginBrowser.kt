package dev.datell.followertracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datell.followertracker.core.Provider
import java.io.ByteArrayInputStream

/** Interactive login windows share cookies and preserve window.opener for SNS OAuth callbacks. */
class SessionLoginBrowser(private val provider: Provider) {
    var active by mutableStateOf<WebView?>(null)
        private set
    var location by mutableStateOf(provider.loginUrl)
        private set
    var loading by mutableStateOf(true)
        private set
    var notice by mutableStateOf<String?>(null)
    var blockedDestination: LoginDestination? = null
        private set
    private var container: FrameLayout? = null
    private var main: WebView? = null
    private val popups = mutableListOf<WebView>()
    private val facebookFallbacks = mutableMapOf<WebView, MutableSet<String>>()
    private var disposed = false

    fun createView(context: Context): FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }.also {
        check(container == null && !disposed)
        container = it
        openMain()
    }

    fun reload() {
        notice = null
        active?.let { facebookFallbacks.remove(it) }
        if (active == null) openMain() else active?.reload()
    }

    fun goBack() {
        val web = active ?: return
        if (web.canGoBack()) web.goBack() else closeActivePopup()
    }

    fun closeActivePopup(): Boolean {
        val web = active ?: return false
        if (web !in popups) return false
        closePopup(web)
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        active = null
        val windows = popups.toList() + listOfNotNull(main)
        popups.clear(); main = null
        windows.forEach(::destroy)
        container = null
    }

    private fun openMain() {
        val root = container ?: return
        if (disposed || main != null) return
        main = configuredWebView(root.context).also { web ->
            root.addView(web)
            active = web
            loading = true
            web.loadUrl(provider.loginUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configuredWebView(context: Context): WebView = WebView(context).apply {
        // Percentage-height login pages need a real MATCH_PARENT viewport.
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WebView.setWebContentsDebuggingEnabled(false)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = loginUserAgent(WebSettings.getDefaultUserAgent(context))
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (!disposed && view === active && progress == 100) loading = false
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val root = container ?: return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                if (disposed || view !== active || popups.size >= 3 || !allowsPage(view.url.orEmpty())) return false
                val popup = configuredWebView(root.context)
                popups.add(popup)
                root.addView(popup)
                active = popup; loading = true; notice = null
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { closePopup(window) }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isOptionalTikTokAppLink(provider, view.url.orEmpty(), request.url.toString())) return true
                if (request.isForMainFrame && followFacebookFallback(view, request.url.toString())) return true
                if (request.isForMainFrame && !allowsPage(request.url.toString())) {
                    blocked(view, request.url.toString())
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // A popup's initial request and POST navigation may bypass shouldOverrideUrlLoading.
                if (request.isForMainFrame && !allowsPage(request.url.toString())) {
                    view.post {
                        if (!isOptionalTikTokAppLink(provider, view.url.orEmpty(), request.url.toString()) &&
                            !followFacebookFallback(view, request.url.toString()))
                            blocked(view, request.url.toString())
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(byteArrayOf()))
                }
                return null
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!allowsPage(url.orEmpty())) { view.stopLoading(); blocked(view, url.orEmpty()); return }
                if (!disposed && view === active) { loading = true; notice = null; location = url.orEmpty() }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (disposed) return
                CookieManager.getInstance().flush()
                if (view === active) { loading = false; location = url ?: provider.loginUrl }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!disposed && view === active && url != null) location = url
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showError(view, "보안 연결을 확인하지 못했어요. 기기 날짜와 네트워크를 확인한 뒤 새로고침해주세요.")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) showError(view, "로그인 페이지가 요청을 거절했어요 (HTTP ${response.statusCode}). 잠시 뒤 새로고침해주세요.")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(view, "페이지를 열지 못했어요 (오류 ${error.errorCode}). 연결 상태를 확인한 뒤 새로고침해주세요.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view in popups) closePopup(view)
                else if (view === main) {
                    active = null
                    val windows = popups.toList() + view
                    popups.clear(); main = null
                    windows.forEach(::destroy)
                }
                if (!disposed) { loading = false; notice = "로그인 창의 연결이 끊어졌어요. 페이지를 새로고침해주세요." }
                return true
            }
        }
    }

    private fun allowsPage(url: String) = url == "about:blank" || loginNavigation(provider, url) != LoginNavigation.BLOCK

    private fun followFacebookFallback(view: WebView, target: String): Boolean {
        if (disposed) return false
        val fallback = facebookBrowserFallback(provider, view.url.orEmpty(), target) ?: return false
        val visited = facebookFallbacks.getOrPut(view) { mutableSetOf() }
        if (fallback in visited || visited.size >= 2) {
            showError(view, "페이스북이 프로필을 웹페이지로 열지 못했어요. 잠시 뒤 새로고침해주세요.")
        } else {
            visited.add(fallback)
            notice = null
            view.loadUrl(fallback)
        }
        return true
    }

    private fun blocked(view: WebView, url: String) {
        if (!disposed) blockedDestination = loginDestination(url)
        showError(view, "공식 SNS·인증 서비스 주소가 아닌 페이지로의 이동을 중단했어요.")
    }

    private fun showError(view: WebView, text: String) {
        if (!disposed && view === active) { loading = false; notice = text }
    }

    private fun closePopup(view: WebView) {
        val index = popups.indexOf(view)
        if (index < 0) return
        val closing = popups.subList(index, popups.size).toList()
        popups.subList(index, popups.size).clear()
        active = popups.lastOrNull() ?: main
        closing.forEach(::destroy)
        if (!disposed) {
            location = active?.url ?: provider.loginUrl
            loading = active?.progress?.let { it < 100 } ?: false
            CookieManager.getInstance().flush()
        }
    }

    private fun destroy(web: WebView) {
        facebookFallbacks.remove(web)
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.webViewClient = WebViewClient()
        web.webChromeClient = null
        web.settings.javaScriptEnabled = false
        web.destroy()
    }
}
