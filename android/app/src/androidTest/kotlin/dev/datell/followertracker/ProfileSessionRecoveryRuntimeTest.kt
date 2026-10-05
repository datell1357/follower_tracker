package dev.datell.followertracker

import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.sync.ProfilePageCollector
import dev.datell.followertracker.ui.webCaptureFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local documents, existing session read only. No fake cookies or observations enter the app DB. */
@RunWith(AndroidJUnit4::class)
class ProfileSessionRecoveryRuntimeTest {
    @Test fun missingBootstrapIsDistinctFromARejectedSessionOrDifferentOwner() {
        fun status(error: String, available: Boolean, code: Int = 0) =
            webCaptureFailure(JSONObject().put("error", error).put("status", code), sessionIdentityAvailable = available)?.status
        assertEquals(SyncStatus.FORMAT_CHANGED, status("identity_missing", true))
        assertEquals(SyncStatus.REAUTH_REQUIRED, status("identity_missing", false))
        assertEquals(SyncStatus.FORMAT_CHANGED, status("owner_context_missing", true))
        assertEquals(SyncStatus.CHECK_REQUIRED, status("owner_context_missing", false))
        assertEquals(SyncStatus.REAUTH_REQUIRED, status("reauth_required", true))
        assertEquals(SyncStatus.REAUTH_REQUIRED, status("http", true, 401))
        assertEquals(SyncStatus.CHECK_REQUIRED, status("http", true, 403))
        assertEquals(SyncStatus.CHECK_REQUIRED, status("own_profile_required", true))
    }

    private suspend fun readFixture(delay: Int?, loginRoute: Boolean = false): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = context.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull { it.account.provider == Provider.TIKTOK }
        assumeTrue(before != null && graph.sessions.hasAuthentication(Provider.TIKTOK))
        val original = checkNotNull(before)
        val metadata = checkNotNull(graph.sessions.metadata(Provider.TIKTOK))
        assumeTrue(graph.sessions.identity(Provider.TIKTOK) == original.account.stableId)
        val user = JSONObject().put("id", original.account.stableId).put("uniqueId", "fixture.owner").put("nickname", "Fixture")
        val payload = JSONObject().put("__DEFAULT_SCOPE__", JSONObject()
            .put("webapp.app-context", JSONObject().put("user", user))
            .put("webapp.user-detail", JSONObject().put("userInfo", JSONObject().put("user", user)
                .put("stats", JSONObject().put("followerCount", 0).put("followingCount", 0)))))
        val browser = ProfilePageCollector(context, graph.sessions) { ctx: Context ->
            object : WebView(ctx) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    val base = if (loginRoute) Provider.TIKTOK.loginUrl else "https://www.tiktok.com/@fixture.owner"
                    val script = delay?.let { """<script>setTimeout(function(){const node=document.createElement('script');
                        node.type='application/json';node.textContent=${JSONObject.quote(payload.toString())};
                        document.body.appendChild(node);},$it);</script>""" } ?: ""
                    loadDataWithBaseURL(base, "<html><body>$script</body></html>", "text/html", "UTF-8", base)
                }
            }
        }
        try { return JSONObject(browser.read(original.account, metadata.userAgent)) }
        finally {
            withContext(Dispatchers.Main) { browser.release() }
            assertTrue("Existing session metadata must remain unchanged", graph.sessions.metadata(Provider.TIKTOK) == metadata)
            val after = graph.repository.widgetOverviews(original.account.key).single()
            assertTrue("Local fixture must not replace the real account or metric", after == original)
        }
    }

    @Test fun delayedTikTokIdentityIsAwaitedInsteadOfRequestingAnotherLogin() = runBlocking {
        val result = readFixture(delay = 1_500)
        assertEquals("TIKTOK", result.getString("provider"))
        assertEquals(0, result.getInt("followers"))
        assertEquals("EXACT", result.getString("precision"))
    }

    @Test fun missingProfileDataKeepsTheSessionAndReportsAFormatFailure() = runBlocking {
        try { readFixture(delay = null); fail("Missing profile data cannot be recorded as success") }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.FORMAT_CHANGED, failure.status) }
    }

    @Test fun anActualLoginRedirectStillRequiresReauthentication() = runBlocking {
        try { readFixture(delay = null, loginRoute = true); fail("A login redirect must stop collection") }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.REAUTH_REQUIRED, failure.status) }
    }
}
