package dev.datell.followertracker

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Uses an existing session read-only with local HTTPS fixtures. No cookies or accounts are written. */
@RunWith(AndroidJUnit4::class)
class FacebookPageConfirmationRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val connections = AtomicInteger()
    private var payload: String? = null
    private val targetId = "990000000000000000"
    private val fixtureUrl = "https://www.facebook.com/profile.php?id=$targetId"

    private fun show(followers: String = "0 followers", page: Boolean = true): WebView {
        val sessions = rule.activity.appGraph.sessions
        assumeTrue("Existing Facebook session required; this test never installs synthetic cookies", sessions.hasAuthentication(Provider.FACEBOOK))
        val owner = checkNotNull(sessions.identity(Provider.FACEBOOK))
        val expected = Account(Provider.FACEBOOK, targetId, "fixture.page", "Fixture Page", fixtureUrl,
            connectedAt = 1, accountType = AccountType.PAGE, sessionOwnerId = owner)
        rule.runOnUiThread { rule.activity.setContent { TrackerTheme {
            SessionLoginDialog(Provider.FACEBOOK, false, null, onDismiss = {}, onConnect = { value, _ ->
                payload = value; connections.incrementAndGet()
            }, facebookPage = true, expectedPage = expected)
        } } }
        rule.waitUntil(10_000) { windows().isNotEmpty() }
        val web = rule.runOnIdle { windows().last() }
        rule.runOnUiThread { loadLoginFixture(web, fixtureUrl, """
            <!doctype html><html><head><link rel="canonical" href="$fixtureUrl">
            <meta property="al:android:url" content="fb://${if (page) "page" else "profile"}/$targetId"></head>
            <body><header><h1>Fixture Page</h1><span>$followers</span>${if (page) "<span>Page · Brand</span>" else ""}</header></body></html>
        """.trimIndent()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("연결 확인").fetchSemanticsNodes().any { SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Disabled).matches(it) } }
        pauseForPolls()
        return web
    }

    @Test fun pageConnectsOnlyWhenTheUserConfirmsAndUsesTheTargetRatherThanTheOwner() {
        show()
        assertEquals(0, connections.get())
        rule.onNodeWithText("연결 확인").performClick()
        rule.waitUntil(8_000) { connections.get() == 1 }
        val captured = JSONObject(checkNotNull(payload))
        assertEquals("PAGE", captured.getString("accountType"))
        assertEquals(targetId, captured.getString("stableId"))
        assertTrue("The payload must retain the existing login owner", captured.getString("sessionOwnerId") == rule.activity.appGraph.sessions.identity(Provider.FACEBOOK))
        assertEquals(0, captured.getInt("followers"))
        assertEquals("facebook-webview-page", captured.getString("source"))
        assertEquals(1, connections.get())
    }

    @Test fun roundedPageCountsKeepTheWindowAndExistingConnectionUntouched() {
        val web = show("1.2K followers")
        rule.onNodeWithText("연결 확인").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithText(facebookPageFailureMessage("exact_count_missing")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, connections.get())
        rule.runOnIdle { assertEquals(fixtureUrl, web.url) }
    }

    @Test fun anOrdinaryPersonalProfileCannotBeSavedThroughThePageButton() {
        val web = show(page = false)
        rule.onNodeWithText("연결 확인").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithText(facebookPageFailureMessage("page_required")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, connections.get())
        rule.runOnIdle { assertEquals(fixtureUrl, web.url) }
    }

    private fun pauseForPolls() {
        val started = android.os.SystemClock.elapsedRealtime()
        rule.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime() - started >= 2_100 }
    }
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).toList() }.filterIsInstance<WebView>()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
