package dev.datell.followertracker

import android.view.View
import android.os.Bundle
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.SessionLoginDialog
import dev.datell.followertracker.ui.TrackerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in live inspection. Preserves sessions and emits only fixed flags, never account data or DOM. */
@RunWith(AndroidJUnit4::class)
class XLoginStatusDiagnostic {
    @Test fun verifyStoredXConnection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeXStored") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.appGraph
        val row = graph.repository.overviews().firstOrNull { it.account.provider == Provider.X }
        val result = JSONObject().put("authenticated", graph.sessions.hasAuthentication(Provider.X))
            .put("connected", row != null).put("metricStored", row?.latest != null)
            .put("ownerMatchesSession", row != null && row.account.stableId == graph.sessions.identity(Provider.X))
            .put("status", row?.account?.status?.name ?: "UNCONNECTED")
            .put("source", row?.latest?.source ?: "NONE")
        instrumentation.sendStatus(2, Bundle().apply { putString("X_STORED_DIAGNOSTIC", result.toString()) })
        assertTrue("The signed-in owner and metric must be stored through the app connection flow",
            result.getBoolean("authenticated") && result.getBoolean("connected") &&
                result.getBoolean("metricStored") && result.getBoolean("ownerMatchesSession"))
    }

    @Test fun inspectSignedInXCapture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeXCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val authenticated = context.appGraph.sessions.hasAuthentication(Provider.X)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("X_SESSION_DIAGNOSTIC", JSONObject().put("authenticated", authenticated)
                .put("identity", context.appGraph.sessions.identity(Provider.X) != null).toString())
        })
        assumeTrue(authenticated)
        val identity = checkNotNull(context.appGraph.sessions.identity(Provider.X))
        val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
        val callback = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { TrackerTheme {
                    SessionLoginDialog(Provider.X, false, null, onDismiss = {}, onConnect = { _, _ -> callback.set(true) })
                } }
            }
            delay(8_000)
            val result = withTimeout(30_000) {
                while (true) {
                    val value = CompletableDeferred<String?>()
                    instrumentation.runOnMainSync {
                        val web = WindowInspector.getGlobalWindowViews().asSequence()
                            .flatMap(::descendants).filterIsInstance<WebView>().firstOrNull()
                        if (web == null || web.progress < 100 || !Provider.X.allows(web.url.orEmpty())) {
                            value.complete(null)
                        } else web.evaluateJavascript("""$script;(function(){
                            const id=${JSONObject.quote(identity)};
                            const state=window.__INITIAL_STATE__;
                            const user=state?.entities?.users?.entities?.[id];
                            const captured=FollowerTrackerCapture.capture('X',id);
                            return JSON.stringify({
                                official:location.hostname==='x.com'||location.hostname.endsWith('.x.com'),
                                home:location.pathname==='/home',
                                login:/\/login\/?$/.test(location.pathname),
                                challenge:/challenge|checkpoint|two_factor/.test(location.pathname),
                                profileLink:!!document.querySelector('[data-testid="AppTabBar_Profile_Link"]'),
                                initialState:!!state,
                                initialOwner:!!user && String(user.id_str||user.id)===id,
                                initialExactCount:Number.isSafeInteger(user?.followers_count),
                                initialUsername:typeof user?.screen_name==='string',
                                bootstrapScript:Array.from(document.scripts).some(s=>(s.textContent||'').includes('window.__INITIAL_STATE__')),
                                result:captured.error||'SUCCESS',
                                capturedOwner:captured.stableId===id,
                                capturedExactCount:Number.isSafeInteger(captured.followers)
                            });
                        })();""".trimIndent()) { value.complete(it) }
                    }
                    val encoded = value.await()
                    if (encoded != null) return@withTimeout JSONObject(JSONArray("[$encoded]").getString(0))
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            result.put("autoConnected", callback.get())
            instrumentation.sendStatus(2, Bundle().apply { putString("X_CAPTURE_DIAGNOSTIC", result.toString()) })
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
