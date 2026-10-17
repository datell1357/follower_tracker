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

/** Local documents only. Never changes real cookies or saves synthetic observations. */
@RunWith(AndroidJUnit4::class)
class TikTokProfileContextRuntimeTest {
    @Test fun connectedProfileCountsSurviveAnOmittedViewerContextIncludingExactZero() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeRepeatFixtures") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = context.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == Provider.TIKTOK && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(before != null && graph.sessions.hasAuthentication(Provider.TIKTOK))
        val original = checkNotNull(before)
        val metadata = checkNotNull(graph.sessions.metadata(Provider.TIKTOK))
        val counts = listOf(3L, 0L, 4L)
        var loads = 0
        var views = 0
        val reader = ProfilePageCollector(context, graph.sessions) { ctx: Context ->
            views++
            object : WebView(ctx) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    val index = loads++
                    val owner = JSONObject().put("id", original.account.stableId).put("uniqueId", "fixture")
                    val appContext = if (index == 1) JSONObject() else JSONObject().put("user", owner)
                    val scope = JSONObject().put("webapp.app-context", appContext)
                        .put("webapp.user-detail", JSONObject().put("userInfo", JSONObject().put("user", owner)
                            .put("stats", JSONObject().put("followerCount", counts[index]).put("followingCount", 4))))
                    val data = JSONObject().put("__DEFAULT_SCOPE__", scope).toString()
                    val html = "<html><body><div hidden><input type=\"password\"></div>" +
                        "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">$data</script></body></html>"
                    loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                }
            }
        }
        try {
            for (count in counts) {
                val result = JSONObject(reader.read(original.account, metadata.userAgent, retain = true))
                assertEquals(count, result.getLong("followers"))
                assertEquals(4L, result.getLong("following"))
                assertEquals("EXACT", result.getString("precision"))
                assertTrue("Only the connected profile may be captured", result.getString("stableId") == original.account.stableId)
            }
            assertEquals(3, loads)
            assertEquals(1, views)
        } finally {
            withContext(Dispatchers.Main) { reader.release() }
            assertTrue("The real session must be preserved", graph.sessions.metadata(Provider.TIKTOK) == metadata)
            assertTrue("Synthetic counts must never be stored", graph.repository.widgetOverviews(original.account.key).single() == original)
        }
    }
}
