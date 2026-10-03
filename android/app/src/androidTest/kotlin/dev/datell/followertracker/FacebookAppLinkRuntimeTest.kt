package dev.datell.followertracker

import android.view.WindowManager
import androidx.activity.compose.setContent
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

/** Local HTTPS fixtures only; personal cookies, sessions and stored app data are untouched. */
@RunWith(AndroidJUnit4::class)
class FacebookAppLinkRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val browser = SessionLoginBrowser(Provider.FACEBOOK)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val fixture = "https://www.facebook.com/profile.php?id=42"
    private fun intent(fallback: String = fixture) = "intent://profile/42#Intent;scheme=fb;package=com.facebook.katana;" +
        "S.browser_fallback_url=${java.net.URLEncoder.encode(fallback, "UTF-8")};end"

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
        await { browser.active?.url == fixture && !browser.loading }
        withTimeout(10_000) { while (evaluate("document.readyState==='complete'&&window.fixtureAttempts===0") != "true") delay(50) }
    }

    @After fun closeFixture() {
        instrumentation.runOnMainSync { browser.dispose() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun officialProfileFallbackReloadsInsideTheWebView() = runBlocking {
        evaluate("window.fixtureAttempts=1;location.href=${JSONObject.quote(intent())};null;")
        withTimeout(5_000) { while (evaluate("window.fixtureAttempts") != "0") delay(50) }
        instrumentation.runOnMainSync {
            assertEquals(fixture, browser.active?.url)
            assertNull(browser.notice)
            assertNull(browser.blockedDestination)
        }
    }

    @Test fun repeatedFacebookFallbackStopsInsteadOfLooping() = runBlocking {
        evaluate("window.fixtureAttempts=1;location.href=${JSONObject.quote(intent())};null;")
        withTimeout(5_000) { while (evaluate("window.fixtureAttempts") != "0") delay(50) }
        evaluate("window.fixtureAttempts=1;location.href=${JSONObject.quote(intent())};null;")
        await { browser.notice != null }
        assertEquals("1", evaluate("window.fixtureAttempts"))
        instrumentation.runOnMainSync {
            assertEquals(fixture, browser.active?.url)
            assertNull(browser.blockedDestination)
            assertEquals("페이스북이 프로필을 웹페이지로 열지 못했어요. 잠시 뒤 새로고침해주세요.", browser.notice)
        }
    }

    @Test fun foreignFallbackRemainsBlockedWithoutOpeningAnotherApp() = runBlocking {
        evaluate("window.fixtureAttempts=1;location.href=${JSONObject.quote(intent("https://example.test/profile.php?id=42"))};null;")
        await { browser.blockedDestination != null }
        assertEquals("1", evaluate("window.fixtureAttempts"))
        instrumentation.runOnMainSync {
            assertEquals(fixture, browser.active?.url)
            assertEquals(LoginDestination("intent", "profile"), browser.blockedDestination)
            assertNotNull(browser.notice)
        }
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
