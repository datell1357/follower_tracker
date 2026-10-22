package dev.datell.followertracker

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Empty QA package only; all documents are local and no account observation is saved. */
@RunWith(AndroidJUnit4::class)
class FacebookPageLinkRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val target = "https://www.facebook.com/profile.php?id=99"
    private val share = "https://www.facebook.com/share/fixture123/"
    private val named = "https://www.facebook.com/fixture.page/"
    private val connections = AtomicInteger()
    private val targetLoads = AtomicInteger()
    private lateinit var web: WebView
    private var payload: String? = null
    private var connectedAgent: String? = null
    private var originalAgent: String? = null
    private var fixtureSessionInstalled = false

    @Before fun requireEmptyQAStore() {
        rule.runOnIdle {
            check(rule.activity.packageName == "dev.datell.followertracker.loginvalidation") { "Requires the isolated login validation package" }
            check(Provider.entries.none { rule.activity.appGraph.sessions.hasAuthentication(it) }) { "Requires an empty QA session store" }
        }
    }

    private fun session(present: Boolean) {
        val remaining = AtomicInteger(2)
        rule.runOnUiThread {
            CookieManager.getInstance().setAcceptCookie(true)
            for ((name, value) in listOf("c_user" to "42", "xs" to "synthetic-page-link-session")) {
                CookieManager.getInstance().setCookie(Provider.FACEBOOK.loginUrl,
                    "$name=${if (present) value else ""}; ${if (present) "" else "Max-Age=0; "}Path=/; Domain=.facebook.com; Secure") { remaining.decrementAndGet() }
            }
        }
        rule.waitUntil(5_000) { remaining.get() == 0 }
        rule.runOnIdle { CookieManager.getInstance().flush() }
        fixtureSessionInstalled = present
    }

    @After fun expireOnlyTheFixtureSession() {
        if (fixtureSessionInstalled) session(false)
    }

    private fun show(authenticated: Boolean = true, delayedExact: Boolean = false, redirectID: String? = null,
        staleCookieLogin: Boolean = false, publicBeforeLogin: Boolean = false, namedProof: String? = null,
        delayedNameProof: Boolean = false) {
        if (authenticated) session(true)
        val factory: (Context) -> WebView = { context ->
            object : WebView(context) {
                override fun loadUrl(url: String) {
                    settings.blockNetworkLoads = true
                    if (originalAgent == null) originalAgent = settings.userAgentString
                    if (url in setOf(target, share, named)) targetLoads.incrementAndGet()
                    val authenticatedNow = rule.activity.appGraph.sessions.hasAuthentication(Provider.FACEBOOK)
                    val page = (authenticatedNow || publicBeforeLogin) && url in setOf(target, share, named) && !(staleCookieLogin && targetLoads.get() == 1)
                    val id = redirectID ?: "99"
                    val location = if (page) "https://www.facebook.com/profile.php?id=$id" else "https://www.facebook.com/login/"
                    val desktop = settings.userAgentString != originalAgent
                    val count = if (delayedExact) "1.2K followers" else if (publicBeforeLogin && !authenticatedNow) "7 followers" else "0 followers"
                    val html = if (!page) "<html><body>Fixture login</body></html>" else """
                        <html><head><link rel="canonical" href="$location"><meta property="al:android:url" content="fb://page/$id"></head>
                        <body><header><h1>Fixture Page</h1><span id="followers">$count</span><span>Page · Brand</span></header>
                        ${if (namedProof != null) "<script type=\"application/json\">" + JSONObject(mapOf("records" to listOf(
                            mapOf("id" to id, "follower_count" to 0),
                            mapOf("__typename" to "Page", "id" to id, "username" to if (delayedNameProof) "" else namedProof, "name" to "Fixture Page")))) + "</script>" else ""}
                        ${if (delayedNameProof) "<script>setTimeout(function(){const node=document.querySelector('script[type=\"application/json\"]');const data=JSON.parse(node.textContent);data.records[1].username='fixture.page';node.textContent=JSON.stringify(data);},2200);</script>" else ""}
                        ${if (delayedExact && desktop) "<script>setTimeout(function(){document.getElementById('followers').textContent='12,345 followers';},900);</script>" else ""}
                        </body></html>
                    """.trimIndent()
                    loadDataWithBaseURL(location, html, "text/html", "UTF-8", location)
                }
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) = loadUrl(url)
            }.also { web = it }
        }
        rule.runOnUiThread { rule.activity.setContent { TrackerTheme {
            SessionLoginDialog(Provider.FACEBOOK, false, null, onDismiss = {}, onConnect = { value, agent ->
                payload = value; connectedAgent = agent; connections.incrementAndGet()
            }, facebookPage = true, createBrowserView = factory)
        } } }
        rule.waitUntil(8_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
    }

    private fun submitLink(link: String = target) {
        rule.onNode(hasSetTextAction()).performTextReplacement(link)
        rule.onNodeWithText("연결", substring = false).performClick()
    }

    @Test fun oneLinkSubmissionConnectsExactZeroWithoutTheSeparateConfirmation() {
        show(); assertEquals(0, connections.get()); submitLink()
        rule.waitUntil(12_000) { connections.get() == 1 }
        val result = JSONObject(checkNotNull(payload))
        assertEquals("99", result.getString("stableId")); assertEquals("42", result.getString("sessionOwnerId"))
        assertEquals(0L, result.getLong("followers")); assertEquals(1, targetLoads.get())
        val until = System.currentTimeMillis() + 1_500
        rule.waitUntil(3_000) { System.currentTimeMillis() >= until }
        assertEquals(1, connections.get())
    }

    @Test fun loginAndChallengeReturnToTheSubmittedPageOnlyAfterAuthenticationCompletes() {
        show(authenticated = false); submitLink()
        rule.waitUntil(8_000) { targetLoads.get() == 1 }
        assertEquals(0, connections.get())
        session(true)
        rule.runOnUiThread { web.loadDataWithBaseURL("https://www.facebook.com/checkpoint/", "<html><body>Fixture challenge</body></html>", "text/html", "UTF-8", "https://www.facebook.com/checkpoint/") }
        val until = System.currentTimeMillis() + 1_500
        rule.waitUntil(3_000) { System.currentTimeMillis() >= until }
        assertEquals(0, connections.get()); assertEquals(1, targetLoads.get())
        rule.runOnUiThread { web.loadDataWithBaseURL("https://www.facebook.com/", "<html><body>Fixture signed in</body></html>", "text/html", "UTF-8", "https://www.facebook.com/") }
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals(2, targetLoads.get()); assertEquals("99", JSONObject(checkNotNull(payload)).getString("stableId"))
    }

    @Test fun aDifferentRedirectedPageDoesNotConnectAutomatically() {
        show(redirectID = "88"); submitLink()
        rule.waitUntil(8_000) { targetLoads.get() == 1 }
        val until = System.currentTimeMillis() + 1_500
        rule.waitUntil(3_000) { System.currentTimeMillis() >= until }
        assertEquals(0, connections.get())
    }

    @Test fun anOpaqueShareLinkShowsItsDestinationAndRequiresOnlyTheFinalConfirmation() {
        show(); submitLink(share)
        rule.waitUntil(8_000) { targetLoads.get() == 1 }
        val until = System.currentTimeMillis() + 1_500
        rule.waitUntil(3_000) { System.currentTimeMillis() >= until }
        assertEquals(0, connections.get())
        rule.onNodeWithText("이 페이지 연결").performClick()
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals("99", JSONObject(checkNotNull(payload)).getString("stableId"))
        assertEquals(1, targetLoads.get())
    }

    @Test fun aLoginRedirectWithStaleCookieNamesStillReturnsAfterAuthentication() {
        show(staleCookieLogin = true); submitLink()
        rule.waitUntil(8_000) { targetLoads.get() == 1 }
        val until = System.currentTimeMillis() + 1_500
        rule.waitUntil(3_000) { System.currentTimeMillis() >= until }
        assertEquals(0, connections.get())
        rule.runOnUiThread { web.loadDataWithBaseURL("https://www.facebook.com/", "<html><body>Fixture signed in</body></html>", "text/html", "UTF-8", "https://www.facebook.com/") }
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals(2, targetLoads.get())
    }

    @Test fun aPageOpenedBeforeLoginIsReloadedBeforeSavingThePostLoginObservation() {
        show(authenticated = false, publicBeforeLogin = true); submitLink()
        rule.waitUntil(8_000) { targetLoads.get() == 1 }
        assertEquals(0, connections.get())
        session(true)
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals(2, targetLoads.get())
        assertEquals(0L, JSONObject(checkNotNull(payload)).getLong("followers"))
    }

    @Test fun roundedDesktopTextWaitsForTheSameDocumentsExactHydration() {
        show(delayedExact = true); submitLink()
        rule.waitUntil(25_000) { connections.get() == 1 }
        assertEquals(12_345L, JSONObject(checkNotNull(payload)).getLong("followers"))
        assertEquals(2, targetLoads.get()); assertEquals(originalAgent, connectedAgent)
    }

    @Test fun aNamedPageRedirectConnectsOnlyAfterItsDocumentProvesTheSubmittedName() {
        show(namedProof = "fixture.page"); submitLink(named)
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals("99", JSONObject(checkNotNull(payload)).getString("stableId"))
        assertEquals(0L, JSONObject(checkNotNull(payload)).getLong("followers"))
        assertEquals(1, targetLoads.get())
    }

    @Test fun aRedirectWithoutTheSubmittedNameNeverSavesUntilTheUserSelectsTheVisiblePage() {
        show(namedProof = "other.page"); submitLink(named)
        rule.waitUntil(12_000) { rule.onAllNodesWithText(facebookPageFailureMessage("page_mismatch")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, connections.get())
        rule.onNodeWithText("이 페이지 연결").performClick()
        rule.waitUntil(12_000) { connections.get() == 1 }
        assertEquals("99", JSONObject(checkNotNull(payload)).getString("stableId"))
    }

    @Test fun aDelayedRedirectNameIsReadLocallyBeforeConnectingTheSubmittedPage() {
        show(namedProof = "fixture.page", delayedNameProof = true); submitLink(named)
        rule.waitUntil(20_000) { connections.get() == 1 }
        assertEquals("99", JSONObject(checkNotNull(payload)).getString("stableId"))
        assertEquals(0L, JSONObject(checkNotNull(payload)).getLong("followers"))
        assertTrue(targetLoads.get() in 1..2)
    }
}
