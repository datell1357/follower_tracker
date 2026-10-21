package dev.datell.followertracker

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import dev.datell.followertracker.sync.ProfilePageCollector
import dev.datell.followertracker.sync.SessionCollector
import dev.datell.followertracker.sync.SessionMetadata
import dev.datell.followertracker.sync.SessionStore
import dev.datell.followertracker.ui.loginUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic cookies and HTTPS documents run only in the separate validation app's WebView store. */
@RunWith(AndroidJUnit4::class)
class FacebookPageRefreshRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sessions: SessionStore
    private lateinit var agent: String
    private fun page(id: String = "99") = Account(Provider.FACEBOOK, id, "fixture.page", "Fixture Page",
        "https://www.facebook.com/profile.php?id=$id", connectedAt = 1, status = SyncStatus.FOREGROUND_ONLY,
        countTransport = CountTransport.PROFILE_BROWSER, accountType = AccountType.PAGE, sessionOwnerId = "42")

    @Before fun isolatedSession() = runBlocking {
        assumeTrue("Never installs synthetic login cookies in the user's app", context.packageName == "dev.datell.followertracker.pagevalidation")
        withContext(Dispatchers.Main) {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            for (value in listOf("c_user=42", "xs=synthetic-validation-session")) {
                val accepted = suspendCancellableCoroutine { continuation ->
                    manager.setCookie(Provider.FACEBOOK.loginUrl, "$value; Path=/; Domain=.facebook.com; Secure") {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
                assertTrue("Fixture cookie must be installed before collection begins", accepted)
            }
            manager.flush()
            agent = loginUserAgent(WebSettings.getDefaultUserAgent(context))
            sessions = SessionStore(context, DataCipher(), "page_refresh_fixture")
            sessions.save(Provider.FACEBOOK, SessionMetadata(agent, "42", 1))
            assertTrue(sessions.hasAuthentication(Provider.FACEBOOK))
        }
    }

    private class Documents {
        var requests = 0
        var created = 0
        var latestAgent: String? = null
        var overrideTarget: String? = null
        var loginPath: String? = null
        var count = 0
        var delayMillis = 0
        fun create(context: Context): WebView {
            created++
            return object : WebView(context) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    settings.blockNetworkLoads = true
                    requests++
                    latestAgent = settings.userAgentString
                    val target = overrideTarget ?: url.substringAfter("id=")
                    val selected = "https://www.facebook.com/profile.php?id=$target"
                    val document = """<link rel="canonical" href="$selected">
                        <meta property="al:android:url" content="fb://page/$target">
                        <header><h1>Fixture Page</h1><span>$count followers</span><span>Page · Brand</span></header>"""
                    val body = if (delayMillis == 0) document else """<script>setTimeout(function(){
                        document.body.innerHTML=${org.json.JSONObject.quote(document)};},$delayMillis);</script>"""
                    val base = loginPath?.let { "https://www.facebook.com/$it" } ?: selected
                    loadDataWithBaseURL(base, "<html><body>$body</body></html>", "text/html", "UTF-8", base)
                }
            }
        }
    }

    @Test fun repeatedPageDocumentsKeepTheLoginOwnerAndSelectedPageSeparate() = runBlocking {
        val documents = Documents()
        val browser = ProfilePageCollector(context, sessions, documents::create)
        try {
            val selected = page()
            val first = browser.read(selected, agent, retain = true)
            val firstResult = SessionCollector(sessions).captured(Provider.FACEBOOK, first, selected, facebookPage = true)
            assertEquals(selected.key, firstResult.first.key)
            assertEquals("42", firstResult.first.sessionOwnerId)
            assertEquals(0L, firstResult.second.followers)
            assertNull(firstResult.second.following)
            documents.count = 7
            val next = browser.read(selected, agent, retain = true)
            assertEquals(7L, SessionCollector(sessions).captured(Provider.FACEBOOK, next, selected, facebookPage = true).second.followers)
            assertEquals("One fresh document per read; local capture must not add a request", 2, documents.requests)
            assertEquals("Same selected Page can reuse its cleared browser", 1, documents.created)
            assertFalse(documents.latestAgent.orEmpty().contains("Android"))
            assertEquals(agent, sessions.metadata(Provider.FACEBOOK)?.userAgent)
            val other = page("88")
            val another = browser.read(other, agent, retain = true)
            assertEquals(other.key, SessionCollector(sessions).captured(Provider.FACEBOOK, another, other, facebookPage = true).first.key)
            assertEquals("Switching Page cannot reuse a differently bound document", 2, documents.created)
        } finally { withContext(Dispatchers.Main) { browser.release() } }
    }

    @Test fun delayedPageHydrationPollsLocallyWithoutAnotherDocumentRequest() = runBlocking {
        val documents = Documents().apply { delayMillis = 1_500 }
        val browser = ProfilePageCollector(context, sessions, documents::create)
        try {
            val selected = page()
            val payload = browser.read(selected, agent)
            assertEquals(0L, SessionCollector(sessions).captured(Provider.FACEBOOK, payload, selected, facebookPage = true).second.followers)
            assertEquals(1, documents.requests)
        } finally { withContext(Dispatchers.Main) { browser.release() } }
    }

    @Test fun anotherLoginOwnerIsRejectedBeforeAnyPageRequest() = runBlocking {
        val documents = Documents()
        val browser = ProfilePageCollector(context, sessions, documents::create)
        try {
            browser.read(page().copy(sessionOwnerId = "77"), agent)
            fail("A Page ID must never substitute for the authenticated owner")
        } catch (failure: CollectionFailure) {
            assertEquals(SyncStatus.REAUTH_REQUIRED, failure.status)
            assertEquals(0, documents.requests)
            assertEquals(0, documents.created)
        } finally { withContext(Dispatchers.Main) { browser.release() } }
    }

    @Test fun aDifferentPageCannotReplaceTheSelectedPagesObservation() = runBlocking {
        val documents = Documents().apply { overrideTarget = "88" }
        val browser = ProfilePageCollector(context, sessions, documents::create)
        try { browser.read(page(), agent); fail("Different Page must not be accepted") }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
        finally { withContext(Dispatchers.Main) { browser.release() } }
        assertEquals(1, documents.requests)
    }

    @Test fun facebookLoginPhpAndAdditionalAuthenticationStopCollection() = runBlocking {
        for ((path, status) in listOf("login.php" to SyncStatus.REAUTH_REQUIRED,
            "two_step_verification/" to SyncStatus.CHECK_REQUIRED, "auth_platform/" to SyncStatus.CHECK_REQUIRED)) {
            val documents = Documents().apply { loginPath = path }
            val browser = ProfilePageCollector(context, sessions, documents::create)
            try { browser.read(page(), agent); fail("Authentication route cannot produce an observation") }
            catch (failure: CollectionFailure) { assertEquals(status, failure.status) }
            finally { withContext(Dispatchers.Main) { browser.release() } }
            assertEquals(1, documents.requests)
        }
    }
}
