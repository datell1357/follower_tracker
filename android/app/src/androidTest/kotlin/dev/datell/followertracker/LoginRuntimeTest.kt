package dev.datell.followertracker

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LoginRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun eachProviderOpensAProtectedLoginWindowAndCanBeClosed() {
        Provider.entries.forEach { provider ->
            open(provider)
            rule.runOnIdle {
                val web = browser()
                val attributes = web.rootView.layoutParams as WindowManager.LayoutParams
                assertTrue("Login window must prevent Android screen capture", attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue("Cross-site login cookies must be available in this view", CookieManager.getInstance().acceptThirdPartyCookies(web))
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
            }
            close()
        }
    }

    /** Run explicitly on a networked device. This records form availability, not successful authentication. */
    @Test fun recordOfficialLoginPageAvailability() {
        val observations = JSONObject()
        observations.put("webViewVersion", WebView.getCurrentWebViewPackage()?.versionName)
        observations.put("androidSdk", Build.VERSION.SDK_INT)
        Provider.entries.forEach { provider ->
            open(provider)
            var observation: String? = null
            val web = rule.runOnIdle { browser() }
            rule.waitUntil(30_000) {
                if (observation == null) {
                    rule.runOnUiThread {
                        if (web.progress < 100) return@runOnUiThread
                        web.evaluateJavascript("""
                            (function() {
                              if (document.readyState === 'loading' || !document.body || document.body.innerText.length < 80) return null;
                              var roots=[document], inputs=0, passwords=0, buttons=0;
                              for (var i=0;i<roots.length;i++) {
                                inputs+=roots[i].querySelectorAll('input').length;
                                passwords+=roots[i].querySelectorAll('input[type=password]').length;
                                buttons+=roots[i].querySelectorAll('button').length;
                                roots[i].querySelectorAll('*').forEach(function(node) { if (node.shadowRoot) roots.push(node.shadowRoot); });
                              }
                              return JSON.stringify({host:location.hostname,inputs:inputs,passwordInputs:passwords,buttons:buttons,
                                loginText:/log.?in|sign.?in|로그인/i.test(document.body.innerText),
                                pageError:/ERR_|something went wrong|access denied|page isn.t available/i.test(document.body.innerText)});
                            })()
                        """.trimIndent()) { result -> if (result != "null") observation = result }
                    }
                }
                observation != null
            }
            val encoded = org.json.JSONArray("[$observation]").getString(0)
            val result = JSONObject(encoded)
            result.put("sessionCookiePresent", rule.runOnIdle { rule.activity.appGraph.sessions.hasAuthentication(provider) })
            observations.put(provider.name, result)
            close()
        }
        val directory = File(rule.activity.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "official-login-pages.json").writeText(observations.toString(2))
    }

    private fun open(provider: Provider) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SNS 연결하기").performClick()
        rule.onNodeWithText(provider.title).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("${provider.title} 연결").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("${provider.title} 연결").assertIsDisplayed()
    }
    private fun close() {
        rule.onNodeWithContentDescription("닫기").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun browser(): WebView = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap { descendants(it) }.filterIsInstance<WebView>().first()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
