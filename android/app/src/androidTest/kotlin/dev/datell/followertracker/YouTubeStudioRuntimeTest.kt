package dev.datell.followertracker

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import dev.datell.followertracker.sync.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offscreen local documents only. No activity, real login, SNS request, or account DB writes. */
@RunWith(AndroidJUnit4::class)
class YouTubeStudioRuntimeTest {
    private suspend fun fixture(block: suspend (SessionStore, ProfilePageCollector, Account, () -> Int) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.datell.followertracker.loginvalidation") { "Requires the isolated empty QA app" }
        check(context.appGraph.repository.accounts().isEmpty())
        val manager = CookieManager.getInstance()
        check(manager.getCookie(Provider.YOUTUBE.loginUrl).orEmpty().isBlank()) { "Existing YouTube cookies must never be replaced" }
        suspend fun cookie(value: String) = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { continuation -> manager.setCookie(Provider.YOUTUBE.loginUrl, value) {
                if (continuation.isActive) continuation.resume(it)
            } }
        }
        val sessions = SessionStore(context, DataCipher(), "youtube-runtime-${UUID.randomUUID()}")
        val id = "UC" + "a".repeat(22)
        val account = Account(Provider.YOUTUBE, id, id, "Fixture Channel", "https://studio.youtube.com/channel/$id",
            connectedAt = 1, countTransport = CountTransport.PROFILE_BROWSER)
        var loads = 0
        var created = 0
        val reader = ProfilePageCollector(context, sessions) { ctx ->
            created++
            object : WebView(ctx) {
                override fun loadUrl(url: String) {
                    if (url == "about:blank") super.loadUrl(url) else document()
                }
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) = document()
                private fun document() {
                    settings.blockNetworkLoads = true
                    val count = loads++
                    val html = """<html><body><ytcp-app><ytcp-navigation-drawer><span id="channel-name">Fixture Channel</span></ytcp-navigation-drawer>
                        <ytcp-channel-analytics><div id="subscriber-count">$count</div></ytcp-channel-analytics></ytcp-app>
                        <input type="password"><script>Object.defineProperty(document.querySelector('input'),'value',{get:function(){throw new Error('Form values are forbidden');}});</script>
                        </body></html>""".trimIndent()
                    loadDataWithBaseURL(account.profileUrl, html, "text/html", "UTF-8", account.profileUrl)
                }
            }
        }
        try {
            assertTrue(cookie("SID=synthetic-studio-runtime; Domain=.youtube.com; Path=/; Secure"))
            assertTrue(cookie("SAPISID=synthetic-studio-runtime; Domain=.youtube.com; Path=/; Secure"))
            sessions.save(Provider.YOUTUBE, SessionMetadata("synthetic-studio-agent", id, 1))
            block(sessions, reader, account) { loads }
            assertTrue(context.appGraph.repository.accounts().isEmpty())
            assertTrue("One inert WebView can be reused without retaining a Studio page", created <= 1)
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { reader.release() }
                sessions.invalidate(Provider.YOUTUBE)
                for (name in listOf("SID", "SAPISID")) {
                    cookie("$name=; Max-Age=0; Domain=.youtube.com; Path=/; Secure")
                    cookie("$name=; Max-Age=0; Path=/; Secure")
                }
                manager.flush()
            }
        }
    }

    @Test fun repeatedReadsRetainTheSessionButNeverReuseTheLastCount() = runBlocking {
        fixture { sessions, reader, account, loads ->
            repeat(3) { index ->
                val payload = reader.read(account, "synthetic-studio-agent", retain = true)
                val (captured, metric) = ResponseParser.youtubeStudioCapture(payload, account, index.toLong() + 100)
                assertEquals(index.toLong(), metric.followers); assertEquals(account.key, captured.key)
                assertTrue(sessions.hasAuthentication(Provider.YOUTUBE)); assertEquals(account.stableId, sessions.identity(Provider.YOUTUBE))
            }
            assertEquals(3, loads())
        }
    }

    @Test fun explicitSessionInvalidationPreventsAnotherBackgroundRead() = runBlocking {
        fixture { sessions, reader, account, loads ->
            reader.read(account, "synthetic-studio-agent", retain = true)
            sessions.invalidate(Provider.YOUTUBE)
            try { reader.read(account, "synthetic-studio-agent", retain = true); fail("Invalidated sessions cannot collect") }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.REAUTH_REQUIRED, failure.status) }
            assertEquals(1, loads())
        }
    }

    @Test fun explicitLogoutAlsoExpiresStudioHostOnlyCookiesWithoutTouchingAnotherProvider() = runBlocking {
        fixture { sessions, _, _, _ ->
            val manager = CookieManager.getInstance()
            withContext(Dispatchers.Main) {
                for (name in listOf("SID", "SAPISID")) suspendCancellableCoroutine<Boolean> { continuation ->
                    manager.setCookie(Provider.YOUTUBE.loginUrl, "$name=synthetic-host-only; Path=/; Secure") {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
            }
            val other = sessions.header(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl)
            assertTrue(sessions.hasAuthentication(Provider.YOUTUBE))
            sessions.disconnect(Provider.YOUTUBE)
            assertFalse(sessions.hasAuthentication(Provider.YOUTUBE)); assertNull(sessions.metadata(Provider.YOUTUBE))
            assertTrue(other == sessions.header(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl))
        }
    }
}
