package dev.datell.followertracker

import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.sync.ProfilePageCollector
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local documents with an existing Facebook session. No network, new cookies or saved fixture observations. */
@RunWith(AndroidJUnit4::class)
class ProfileRepeatCollectionRuntimeTest {
    private suspend fun fixture(create: (Context, Account) -> WebView,
        check: suspend (ProfilePageCollector, Account, String) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeRepeatFixtures") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = context.appGraph
        val original = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == Provider.FACEBOOK && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(original != null && graph.sessions.hasAuthentication(Provider.FACEBOOK))
        val account = checkNotNull(original).account
        val metadata = checkNotNull(graph.sessions.metadata(Provider.FACEBOOK))
        assumeTrue(graph.sessions.identity(Provider.FACEBOOK) == account.stableId)
        val reader = ProfilePageCollector(context, graph.sessions) { create(it, account) }
        try { check(reader, account, metadata.userAgent) }
        finally {
            withContext(Dispatchers.Main) { reader.release() }
            assertTrue("Fixture reads must preserve session metadata", graph.sessions.metadata(Provider.FACEBOOK) == metadata)
            assertTrue("Fixture reads must preserve stored observations", graph.repository.widgetOverviews(account.key).single() == original)
        }
    }

    @Test fun repeatedFacebookDocumentsReadNewJsonAndTextCountsIncludingZero() = runBlocking {
        var loads = 0
        val views = mutableListOf<WebView>()
        val expected = listOf(7L, 0L, 9L)
        fixture({ context, account ->
            object : WebView(context) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    assertEquals("no-cache", additionalHttpHeaders["Cache-Control"])
                    val count = expected[loads++]
                    val data = JSONObject().put("id", account.stableId).put("name", "Fixture").put("followers_count", count)
                    val document = if (loads % 2 == 1) "<script data-sjs>${data}</script>" else
                        "<div><h1>Fixture</h1><span>followers</span><span>$count followers</span></div>"
                    loadDataWithBaseURL(url, "<html><body>$document</body></html>", "text/html", "UTF-8", url)
                }
            }.also { views += it }
        }) { reader, account, agent ->
            for (count in expected) {
                val payload = JSONObject(reader.read(account, agent, retain = true))
                assertEquals(count, payload.getLong("followers"))
                assertEquals("EXACT", payload.getString("precision"))
                assertTrue(payload.getString("stableId") == account.stableId)
                withContext(Dispatchers.Main) { assertEquals("about:blank", views.single().url) }
            }
            assertEquals(3, loads)
            assertEquals(1, views.size)
        }
    }

    @Test fun aCallerDeadlineCancelsTheReadAndReleasesTheBrowser() = runBlocking {
        var destroyed = false
        fixture({ context, _ ->
            object : WebView(context) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) = Unit
                override fun destroy() { destroyed = true; super.destroy() }
            }
        }) { reader, account, agent ->
            var cancelled = false
            try { withTimeout(500) { reader.read(account, agent, retain = true) } }
            catch (_: TimeoutCancellationException) { cancelled = true }
            assertTrue("A caller timeout must remain cancellation", cancelled)
            assertTrue("Cancelled reads must destroy the browser", destroyed)
        }
    }

    @Test fun aSameProviderRouteChangeDiscardsTheOldCountAndReadsTheNewDocument() = runBlocking {
        var evaluations = 0
        fixture({ context, account ->
            object : WebView(context) {
                private var documentUrl: String? = null
                override fun getUrl(): String? = documentUrl ?: super.getUrl()
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    documentUrl = url
                    loadDataWithBaseURL(url, "<html><body></body></html>", "text/html", "UTF-8", url)
                }
                override fun evaluateJavascript(script: String, callback: android.webkit.ValueCallback<String>?) {
                    if (!script.contains("JSON.stringify(FollowerTrackerCapture.capture(")) {
                        super.evaluateJavascript(script, callback); return
                    }
                    evaluations++
                    if (evaluations == 1) documentUrl = "https://www.facebook.com/fixture.alias/"
                    val result = JSONObject().put("provider", "FACEBOOK").put("stableId", account.stableId)
                        .put("username", "fixture").put("displayName", "Fixture").put("profileURL", account.profileUrl)
                        .put("followers", if (evaluations == 1) 7 else 9).put("following", 0)
                        .put("source", "facebook-webview-profile").put("precision", "EXACT")
                    callback?.onReceiveValue(JSONObject.quote(result.toString()))
                }
            }
        }) { reader, account, agent ->
            val result = JSONObject(reader.read(account, agent))
            assertEquals(9L, result.getLong("followers"))
            assertEquals(2, evaluations)
        }
    }

    @Test fun aMissingJavaScriptCallbackCannotOutliveTheCallerDeadline() = runBlocking {
        fixture({ context, account ->
            object : WebView(context) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    val data = JSONObject().put("id", account.stableId).put("name", "Fixture").put("followers_count", 7)
                    loadDataWithBaseURL(url, "<html><script type='application/json'>$data</script></html>", "text/html", "UTF-8", url)
                }
                override fun evaluateJavascript(script: String, callback: android.webkit.ValueCallback<String>?) = Unit
            }
        }) { reader, account, agent ->
            var cancelled = false
            try { withTimeout(2_000) { reader.read(account, agent) } }
            catch (_: TimeoutCancellationException) { cancelled = true }
            assertTrue("A missing callback must not hide the caller's cancellation", cancelled)
        }
    }
}
