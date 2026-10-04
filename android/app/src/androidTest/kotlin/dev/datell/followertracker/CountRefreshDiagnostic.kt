package dev.datell.followertracker

import android.os.Bundle
import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.sync.ProfilePageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live count refresh only. Never creates fixture accounts/cookies or prints identities/counts. */
@RunWith(AndroidJUnit4::class)
class CountRefreshDiagnostic {
    /** Local HTML only, using an existing session without replacing cookies or saving fixture counts. */
    @Test fun awaitsProfileHydrationWithoutChangingStoredConnection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeProfileHydration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = context.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == Provider.FACEBOOK && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(before != null)
        val original = checkNotNull(before)
        val metadata = checkNotNull(graph.sessions.metadata(Provider.FACEBOOK))
        val fixture = JSONObject().put("id", original.account.stableId).put("name", "Fixture").put("followers_count", 0)
        val browser = ProfilePageCollector(context, graph.sessions) { ctx: Context ->
            object : WebView(ctx) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    // The official initial document is hydrated after its load callback, without a network request.
                    loadDataWithBaseURL("https://www.facebook.com/", """<html><body><script>
                        setTimeout(function(){const node=document.createElement('script');node.type='application/json';
                          node.textContent=${JSONObject.quote(fixture.toString())};document.body.appendChild(node);},1500);
                        </script></body></html>""", "text/html", "UTF-8", "https://www.facebook.com/")
                }
            }
        }
        try {
            val payload = JSONObject(browser.read(original.account, metadata.userAgent))
            assertEquals(0L, payload.getLong("followers"))
            assertTrue("The hydrated count must belong to the existing session owner", payload.getString("stableId") == original.account.stableId)
            val after = graph.repository.widgetOverviews(original.account.key).single()
            assertTrue("Local capture must not change the stored account or last real observation", after == original)
            assertTrue("The real session must remain unchanged", graph.sessions.metadata(Provider.FACEBOOK) == metadata)
        } finally { withContext(Dispatchers.Main) { browser.release() } }
    }

    @Test fun refreshStoredPersonalProfileAndVerifyFreshObservation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("probeCountRefresh") == "true")
        val provider = Provider.valueOf(checkNotNull(args.getString("provider")))
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == provider && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(before != null)
        val original = checkNotNull(before)
        val background = args.getString("manualRefresh") != "true"
        graph.coordinator.refresh(original.account.key, background = background)
        val after = graph.repository.widgetOverviews(original.account.key).single()
        val fresh = after.latest != null && after.latest!!.observedAt > (original.latest?.observedAt ?: 0)
        val preserved = after.account.key == original.account.key && after.account.connectedAt == original.account.connectedAt
        val result = JSONObject().put("provider", provider.name)
            .put("backgroundRequest", background)
            .put("freshObservation", fresh).put("connectionPreserved", preserved)
            .put("status", after.account.status.name).put("source", after.latest?.source ?: "NONE")
            .put("backgroundCapability", after.account.capabilities.background.name)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("COUNT_REFRESH_DIAGNOSTIC", result.toString())
        })
        assertTrue("The stored connection must be preserved", preserved)
        assertTrue("A new count observation was not stored; see the redacted provider status", fresh)
        if (background) assertTrue("A successful background read must be recorded", after.account.capabilities.background == Capability.OBSERVED)
    }
}
