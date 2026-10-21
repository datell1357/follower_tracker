package dev.datell.followertracker

import android.view.View
import android.webkit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.captureWebSession
import dev.datell.followertracker.ui.installProfileResponseObserver
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Real WebView transport with locally intercepted HTTPS responses, confined to the validation package. */
@RunWith(AndroidJUnit4::class)
class ProfileResponseObserverRuntimeTest {
    @Test fun documentStartObservationReadsFreshExactTotalsWithoutChangingTheSitesResponse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName in setOf("dev.datell.followertracker.pagevalidation", "dev.datell.followertracker.loginvalidation"))
        withContext(Dispatchers.Main) {
            val web = WebView(context)
            val requests = AtomicInteger()
            var count = 12345
            var loaded = CompletableDeferred<Unit>()
            val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
            web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; blockNetworkImage = true; allowFileAccess = false; allowContentAccess = false }
            web.webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true }
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val allowed = Provider.INSTAGRAM.allows(request.url.toString()) && request.url.path == "/graphql/query"
                    if (allowed) requests.incrementAndGet()
                    val payload = if (allowed) """{"data":{"user":{"id":"42","username":"fixture.owner",
                        "follower_count":$count,"following_count":0,"private_message":"must never be retained"}}}""" else "{}"
                    return WebResourceResponse("application/json", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(payload.toByteArray()))
                }
                override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
            }
            val metrics = context.resources.displayMetrics
            web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            assertTrue("Device WebView must support document-start scripts", installProfileResponseObserver(web, Provider.INSTAGRAM))
            suspend fun evaluate(code: String): String = suspendCancellableCoroutine { continuation ->
                web.evaluateJavascript(code) { if (continuation.isActive) continuation.resume(it) }
            }
            suspend fun document(url: String, html: String) {
                loaded = CompletableDeferred()
                web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                withTimeout(10_000) { loaded.await() }
            }
            suspend fun exact(): JSONObject = withTimeout(10_000) {
                while (true) {
                    val value = JSONObject(captureWebSession(web, Provider.INSTAGRAM, "42", script, allowRequest = false))
                    if (!value.has("error")) return@withTimeout value
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            val html = """<html><body>
                <script type="application/json">{"viewer":{"id":"42","username":"fixture.owner"}}</script>
                <a href="/fixture.owner/followers/">12.3K followers</a>
                <script>globalThis.fixtureReadable=false;
                fetch('/graphql/query',{method:'POST',body:'synthetic-request'}).then(r=>r.json()).then(r=>{
                    globalThis.fixtureReadable=typeof r.data.user.private_message==='string';
                });</script></body></html>"""
            try {
                document("https://www.instagram.com/fixture.owner/", html)
                assertEquals(12345, exact().getInt("followers"))
                assertEquals(1, requests.get())
                assertEquals("true", evaluate("globalThis.fixtureReadable"))
                assertEquals("false", evaluate("JSON.stringify(globalThis.__followerTrackerObservedProfiles.profiles).includes('private_message')"))
                count = 7
                document("https://www.instagram.com/fixture.owner/", html)
                assertEquals("A fresh document cannot reuse the first response", 7, exact().getInt("followers"))
                assertEquals(2, requests.get())
                document("https://accounts.google.com/ServiceLogin", "<html><body>Fixture login</body></html>")
                val state = JSONArray("[${evaluate("JSON.stringify({observer:typeof FollowerTrackerObserver,cache:typeof __followerTrackerObservedProfiles})")}]").getString(0)
                assertEquals("undefined", JSONObject(state).getString("observer"))
                assertEquals("undefined", JSONObject(state).getString("cache"))
            } finally { web.stopLoading(); web.destroy() }
        }
    }
}
