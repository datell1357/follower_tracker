package dev.datell.followertracker

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic sessions only. Run on a separate, empty test device. No account is saved. */
@RunWith(AndroidJUnit4::class)
class AutoConnectionRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val connections = AtomicInteger()
    private var payload: String? = null
    private val syntheticCookies = mutableListOf<Pair<String, String>>()

    @Before fun requireEmptyDevice() {
        rule.runOnIdle {
            for (provider in listOf(Provider.INSTAGRAM, Provider.TIKTOK, Provider.X, Provider.FACEBOOK))
                check(!rule.activity.appGraph.sessions.hasAuthentication(provider)) { "Requires a device without personal SNS sessions" }
        }
    }
    @After fun expireSyntheticCookies() {
        rule.runOnIdle {
            syntheticCookies.forEach { (url, name) -> CookieManager.getInstance().setCookie(url, "$name=; Max-Age=0; Path=/; Secure") }
            CookieManager.getInstance().flush()
        }
    }
    private fun show(provider: Provider): WebView {
        rule.runOnUiThread {
            rule.activity.setContent { TrackerTheme {
                SessionLoginDialog(provider, false, null, onDismiss = {}, onConnect = { value, _ -> payload = value; connections.incrementAndGet() })
            } }
        }
        rule.waitUntil(10_000) { WindowInspector.getGlobalWindowViews().any { descendants(it).any { child -> child is WebView } } }
        return rule.runOnIdle { browser().apply { stopLoading(); settings.blockNetworkLoads = true } }
    }
    private fun cookie(provider: Provider, name: String, value: String) {
        var ready = false
        syntheticCookies += provider.loginUrl to name
        rule.runOnUiThread { CookieManager.getInstance().setCookie(provider.loginUrl, "$name=$value; Path=/; Secure") { ready = it } }
        rule.waitUntil(5_000) { ready }
    }
    private fun document(web: WebView, base: String, html: String) = rule.runOnUiThread {
        web.loadDataWithBaseURL(base, "<!doctype html><html><body>$html</body></html>", "text/html", "UTF-8", base)
    }
    private fun waitForPolls() {
        val started = android.os.SystemClock.elapsedRealtime()
        rule.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime() - started >= 2_100 }
    }
    @Test fun loggedInOwnerConnectsAutomaticallyOnceWithoutAConfirmationButton() {
        val web = show(Provider.TIKTOK)
        cookie(Provider.TIKTOK, "sessionid", "synthetic-auto-session")
        document(web, "https://www.tiktok.com/@self", """
            <script type="application/json">{"__DEFAULT_SCOPE__":{
              "webapp.app-context":{"user":{"id":"42","uniqueId":"self"}},
              "webapp.user-detail":{"userInfo":{"user":{"id":"42","uniqueId":"self"},"stats":{"followerCount":0,"followingCount":4}}}
            }}</script><p>Synthetic signed-in profile</p>
        """.trimIndent())
        waitWithMetadata(web) { connections.get() == 1 }
        rule.onNodeWithText("연결 확인").assertDoesNotExist()
        assertEquals(0, JSONObject(payload!!).getInt("followers"))
        waitForPolls()
        assertEquals(1, connections.get())
    }
    @Test fun profileDataWithoutASessionNeverConnects() {
        val web = show(Provider.TIKTOK)
        document(web, "https://www.tiktok.com/@self", """
            <script type="application/json">{"__DEFAULT_SCOPE__":{
              "webapp.app-context":{"user":{"id":"42"}},
              "webapp.user-detail":{"userInfo":{"user":{"id":"42","uniqueId":"self"},"stats":{"followerCount":9,"followingCount":4}}}
            }}</script>
        """.trimIndent())
        waitForPolls()
        assertEquals(0, connections.get())
    }
    @Test fun instagramOwnProfileConnectsWithoutSendingAnApiRequest() {
        val web = show(Provider.INSTAGRAM)
        cookie(Provider.INSTAGRAM, "sessionid", "synthetic-auto-session")
        cookie(Provider.INSTAGRAM, "ds_user_id", "42")
        document(web, "https://www.instagram.com/self/", """
            <script type="application/json">{"viewer":{"id":"42","username":"self"}}</script>
            <script>window.fixtureRequests=0;window.fetch=function(){window.fixtureRequests++;return Promise.reject(new Error('Unexpected request'));};</script>
            <a href="/self/followers/"><span title="12345">12.3K</span> followers</a>
            <a href="/self/following/">0 following</a>
        """.trimIndent())
        waitWithMetadata(web) { connections.get() == 1 }
        val result = JSONObject(payload!!)
        assertEquals(12345, result.getInt("followers"))
        assertEquals(0, result.getInt("following"))
        assertEquals("instagram-webview-dom", result.getString("source"))
        var count: String? = null
        rule.runOnUiThread { web.evaluateJavascript("window.fixtureRequests") { count = it } }
        rule.waitUntil(5_000) { count != null }
        assertEquals("0", count)
    }
    @Test fun rateLimitWaitsWithoutRequestsAndConnectsWhenExactPageCountsAppear() {
        val web = show(Provider.INSTAGRAM)
        cookie(Provider.INSTAGRAM, "sessionid", "synthetic-auto-session")
        cookie(Provider.INSTAGRAM, "ds_user_id", "42")
        document(web, "https://www.instagram.com/self/", """
            <script type="application/json">{"viewer":{"id":"42","username":"self"}}</script>
            <script>window.fixtureRequests=0;window.fetch=function(){window.fixtureRequests++;
              return Promise.resolve(new Response('',{status:429,headers:{'Retry-After':'60'}}));};</script>
            <p>Synthetic request limit</p>
        """.trimIndent())
        waitWithMetadata(web) { rule.onAllNodesWithText("SNS가 데이터 요청을 잠시 제한했어요. 로그인 창을 유지하면 대기 시간이 지난 뒤 다시 확인해요.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("다시 시도").assertIsNotEnabled()
        waitForPolls()
        var count: String? = null
        rule.runOnUiThread { web.evaluateJavascript("window.fixtureRequests") { count = it } }
        rule.waitUntil(5_000) { count != null }
        assertEquals("1", count)
        assertEquals(0, connections.get())
        rule.runOnUiThread { web.evaluateJavascript("document.body.insertAdjacentHTML('beforeend','<a href=\"/self/followers/\">7 followers</a>')", null) }
        waitWithMetadata(web) { connections.get() == 1 }
        assertEquals(7, JSONObject(payload!!).getInt("followers"))
        count = null
        rule.runOnUiThread { web.evaluateJavascript("window.fixtureRequests") { count = it } }
        rule.waitUntil(5_000) { count != null }
        assertEquals("1", count)
    }
    @Test fun collectedInstagramDataMustMatchTheSessionOwner() {
        show(Provider.INSTAGRAM)
        cookie(Provider.INSTAGRAM, "sessionid", "synthetic-auto-session")
        cookie(Provider.INSTAGRAM, "ds_user_id", "42")
        rule.runOnIdle {
            val value = """{"provider":"INSTAGRAM","stableId":"99","username":"other","profileURL":"https://www.instagram.com/other/","followers":5,"source":"instagram-webview-session"}"""
            try { rule.activity.appGraph.collector.captured(Provider.INSTAGRAM, value, null); fail("Foreign account must be rejected") }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
        }
    }
    private fun waitWithMetadata(web: WebView, condition: () -> Boolean) {
        try { rule.waitUntil(10_000, condition) }
        catch (failure: Throwable) {
            rule.runOnIdle { println("SYNTHETIC_WEB_STATE progress=${web.progress} official=${Provider.entries.any { it.allows(web.url.orEmpty()) }} authenticated=${Provider.entries.any { rule.activity.appGraph.sessions.hasAuthentication(it) }}") }
            var value: String? = null
            rule.runOnUiThread { web.evaluateJavascript("JSON.stringify({ready:document.readyState,host:location.hostname,fixture:!!document.querySelector('script[type=application/json]'),api:typeof FollowerTrackerCapture,slots:Object.keys(globalThis).filter(function(k){return k.indexOf('__followerCapture_')===0}).length})") { value = it } }
            rule.waitUntil(5_000) { value != null }
            println("SYNTHETIC_DOCUMENT_STATE=$value")
            println("SYNTHETIC_FORMAT_NOTICE=" + rule.onAllNodesWithText("로그인 페이지에서 정확한 팔로워 수를 읽지 못했어요. 내 프로필에서 다시 확인해주세요.").fetchSemanticsNodes().isNotEmpty())
            throw failure
        }
    }
    private fun browser(): WebView = WindowInspector.getGlobalWindowViews().asSequence().flatMap { descendants(it) }.filterIsInstance<WebView>().first()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
