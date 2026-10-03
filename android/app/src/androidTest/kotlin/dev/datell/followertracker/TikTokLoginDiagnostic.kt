package dev.datell.followertracker

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.SessionLoginBrowser
import dev.datell.followertracker.ui.TrackerTheme
import dev.datell.followertracker.ui.loginDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** User completes login on the phone. Records only destination hosts and session/status flags. */
@RunWith(AndroidJUnit4::class)
class TikTokLoginDiagnostic {
    @Test fun verifyStoredTikTokConnection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeTikTokStored") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.appGraph
        val rows = graph.repository.overviews()
        val row = rows.firstOrNull { it.account.provider == Provider.TIKTOK }
        val result = JSONObject().put("authenticated", graph.sessions.hasAuthentication(Provider.TIKTOK))
            .put("connected", row != null).put("metricStored", row?.latest != null)
            .put("ownerMatchesSession", row != null && row.account.stableId == graph.sessions.identity(Provider.TIKTOK))
            .put("status", row?.account?.status?.name ?: "UNCONNECTED")
            .put("source", row?.latest?.source ?: "NONE")
            .put("xConnectionPreserved", rows.any { it.account.provider == Provider.X && it.latest != null })
        instrumentation.sendStatus(2, Bundle().apply { putString("TIKTOK_STORED_DIAGNOSTIC", result.toString()) })
        assertTrue("TikTok owner and metric must be stored through the app connection flow",
            result.getBoolean("authenticated") && result.getBoolean("connected") &&
                result.getBoolean("metricStored") && result.getBoolean("ownerMatchesSession"))
    }

    @Test fun inspectLoginNavigation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeTikTokLogin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val browser = SessionLoginBrowser(Provider.TIKTOK)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { TrackerTheme {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = browser::createView)
                } }
            }
            var previous: String? = null
            try {
                repeat(120) {
                    var result: String? = null
                    instrumentation.runOnMainSync {
                        val page = loginDestination(browser.active?.url.orEmpty())
                        val blocked = browser.blockedDestination
                        result = JSONObject().put("pageScheme", page.scheme ?: "NONE")
                            .put("pageHost", page.host ?: "NONE").put("blockedScheme", blocked?.scheme ?: "NONE")
                            .put("blockedHost", blocked?.host ?: "NONE").put("loading", browser.loading)
                            .put("authenticated", instrumentation.targetContext.appGraph.sessions.hasAuthentication(Provider.TIKTOK))
                            .toString()
                    }
                    if (result != previous) {
                        instrumentation.sendStatus(2, Bundle().apply { putString("TIKTOK_LOGIN_DIAGNOSTIC", result) })
                        previous = result
                    }
                    delay(1_000)
                }
            } finally { instrumentation.runOnMainSync { browser.dispose() } }
        }
    }

}
