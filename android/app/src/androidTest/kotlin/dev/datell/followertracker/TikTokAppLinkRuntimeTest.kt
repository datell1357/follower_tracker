package dev.datell.followertracker

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.LoginDestination
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.SessionLoginBrowser
import dev.datell.followertracker.ui.TrackerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local HTTPS fixtures only. Does not read, replace, or clear personal sessions or write app data. */
@RunWith(AndroidJUnit4::class)
class TikTokAppLinkRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val browser = SessionLoginBrowser(Provider.TIKTOK)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val fixture = "https://www.tiktok.com/@fixture"

    @Before fun openFixture() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            activity.setContent { TrackerTheme {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    browser.createView(context).also {
                        loadLoginFixture(checkNotNull(browser.active), fixture,
                            "<!doctype html><html><body><script>window.fixtureAttempts=0;</script><p>Fixture</p></body></html>")
                    }
                })
            } }
        }
        await { browser.active != null && browser.active?.url == fixture && !browser.loading }
        // A canceled startup load can finish before the fixture's JavaScript becomes ready.
        withTimeout(10_000) {
            while (evaluate("document.readyState==='complete'&&window.fixtureAttempts===0") != "true") delay(50)
        }
        assertEquals("0", evaluate("window.fixtureAttempts"))
    }

    @After fun closeFixture() {
        instrumentation.runOnMainSync { browser.dispose() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun optionalNativeAppLinksPreserveTheOfficialDocumentWithoutAnError() = runBlocking {
        var attempts = 0
        for (scheme in listOf("snssdk1340", "snssdk1233", "snssdk1180")) {
            for (host in listOf("aweme", "user")) {
                evaluate("location.href=${JSONObject.quote("$scheme://$host/profile/42")};setTimeout(function(){window.fixtureAttempts++;},100);null;")
                attempts++
                withTimeout(5_000) { while (evaluate("window.fixtureAttempts") != attempts.toString()) delay(50) }
                instrumentation.runOnMainSync {
                    assertEquals(fixture, browser.active?.url)
                    assertEquals(fixture, browser.location)
                    assertNull(browser.notice)
                    assertNull(browser.blockedDestination)
                }
            }
        }
    }

    @Test fun unrelatedHttpsPagesRemainBlocked() = runBlocking {
        evaluate("location.href='https://example.test/private?code=private';null;")
        await { browser.blockedDestination != null }
        instrumentation.runOnMainSync {
            assertEquals(fixture, browser.active?.url)
            assertEquals(LoginDestination("https", "example.test"), browser.blockedDestination)
            assertEquals("공식 SNS·인증 서비스 주소가 아닌 페이지로의 이동을 중단했어요.", browser.notice)
        }
    }

    @Test fun spoofedNativeAppLinksRemainBlocked() = runBlocking {
        evaluate("location.href='snssdk1340://user.example.test/private';null;")
        await { browser.blockedDestination != null }
        instrumentation.runOnMainSync {
            assertEquals(fixture, browser.active?.url)
            assertEquals(LoginDestination("snssdk1340", "user.example.test"), browser.blockedDestination)
            assertNotNull(browser.notice)
        }
    }
    @Test fun alternateAuthenticationPagesPassTheProductionNavigationBoundary() = runBlocking {
        for (url in listOf("https://www.facebook.com/dialog/oauth", "https://access.line.me/oauth2/v2.1/authorize",
            "https://kauth.kakao.com/oauth/authorize", "https://accounts.kakao.com/login/", "https://inapp.tiktokv.com/login/",
            "https://api.twitter.com/oauth/authenticate", "https://api.x.com/oauth/authorize", "https://oauth.vk.com/authorize")) {
            instrumentation.runOnMainSync { loadLoginFixture(checkNotNull(browser.active), url, "<html><body>Authentication fixture</body></html>") }
            await { browser.active?.url == url && !browser.loading }
            instrumentation.runOnMainSync { assertNull(browser.notice); assertNull(browser.blockedDestination) }
        }
    }
    @Test fun returningFromAnApprovalPreservesTheBrowserAndUsesOnlyItsVerifiedFallback() = runBlocking {
        val authentication = "https://accounts.google.com/ServiceLogin"
        instrumentation.runOnMainSync { loadLoginFixture(checkNotNull(browser.active), authentication, "<html><body>Approval fixture</body></html>") }
        await { browser.active?.url == authentication && !browser.loading }
        val original = browser.active
        scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
        instrumentation.runOnMainSync { assertSame(original, browser.active) }
        evaluate("location.href='about:blank#fixture';null;")
        await { browser.active?.url == "about:blank#fixture" && !browser.loading }
        val target = "intent://authentication/#Intent;scheme=auth;S.browser_fallback_url=${java.net.URLEncoder.encode(authentication, "UTF-8")};end"
        evaluate("location.href=${JSONObject.quote(target)};null;")
        await { browser.active?.url == authentication && !browser.loading }
        instrumentation.runOnMainSync { assertSame(original, browser.active); assertNull(browser.notice); assertNull(browser.blockedDestination) }
    }

    private suspend fun evaluate(script: String): String {
        val result = CompletableDeferred<String>()
        instrumentation.runOnMainSync { checkNotNull(browser.active).evaluateJavascript(script) { result.complete(it) } }
        return withTimeout(5_000) { result.await() }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) break
            delay(50)
        }
    }
}
