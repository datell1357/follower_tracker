package dev.datell.followertracker

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import java.io.ByteArrayInputStream

/** HTTPS fixture documents exercise the production navigation boundary; no network request escapes. */
fun loadLoginFixture(web: WebView, url: String, html: String, scriptNavigation: Boolean = false,
    desktopHtml: String? = null, onDocument: ((Boolean) -> Unit)? = null) {
    val delegate = (web.webViewClient as? LoginPageFixtureClient)?.delegate ?: web.webViewClient
    web.stopLoading()
    web.webViewClient = LoginPageFixtureClient(delegate, url, html.toByteArray(Charsets.UTF_8),
        desktopHtml?.toByteArray(Charsets.UTF_8), onDocument)
    web.settings.blockNetworkLoads = false
    if (scriptNavigation) web.evaluateJavascript("location.href=${org.json.JSONObject.quote(url)}", null)
    else web.loadUrl(url)
}

private class LoginPageFixtureClient(val delegate: WebViewClient, private val url: String, private val html: ByteArray,
    private val desktopHtml: ByteArray?, private val onDocument: ((Boolean) -> Unit)?) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        delegate.shouldInterceptRequest(view, request)?.let { return it }
        return if (request.isForMainFrame && request.url.toString() == url) {
            val desktop = request.requestHeaders.entries.any { it.key.equals("User-Agent", true) && it.value.contains("X11; Linux x86_64") }
            onDocument?.invoke(desktop)
            WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(if (desktop) desktopHtml ?: html else html))
        } else WebResourceResponse("text/plain", "UTF-8", 404, "No fixture", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()))
    }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = delegate.shouldOverrideUrlLoading(view, request)
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = delegate.onPageStarted(view, url, favicon)
    override fun onPageFinished(view: WebView, url: String?) = delegate.onPageFinished(view, url)
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = delegate.doUpdateVisitedHistory(view, url, isReload)
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = delegate.onReceivedError(view, request, error)
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = delegate.onReceivedHttpError(view, request, response)
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = delegate.onReceivedSslError(view, handler, error)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = delegate.onRenderProcessGone(view, detail)
}
