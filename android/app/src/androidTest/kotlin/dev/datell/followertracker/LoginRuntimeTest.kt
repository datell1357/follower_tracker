package dev.datell.followertracker

import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
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
                              var roots=[document], inputs=0, passwords=0, buttons=0, touchableInputs=0;
                              function touchable(node, root) {
                                var r=node.getBoundingClientRect(), x=r.x+r.width/2, y=r.y+r.height/2;
                                if (r.width<=0 || r.height<=0 || x<0 || y<0 || x>=innerWidth || y>=innerHeight) return false;
                                var hit=root.elementFromPoint(x,y);
                                return hit===node || node.contains(hit);
                              }
                              for (var i=0;i<roots.length;i++) {
                                var fields=Array.from(roots[i].querySelectorAll('input'));
                                inputs+=fields.length;
                                touchableInputs+=fields.filter(function(node) { return touchable(node,roots[i]); }).length;
                                passwords+=roots[i].querySelectorAll('input[type=password]').length;
                                buttons+=roots[i].querySelectorAll('button').length;
                                roots[i].querySelectorAll('*').forEach(function(node) { if (node.shadowRoot) roots.push(node.shadowRoot); });
                              }
                              return JSON.stringify({host:location.hostname,inputs:inputs,passwordInputs:passwords,buttons:buttons,touchableInputs:touchableInputs,
                                documentHeight:document.documentElement.getBoundingClientRect().height,viewportHeight:innerHeight,
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
            result.put("layoutHeight", rule.runOnIdle { web.layoutParams.height })
            result.put("sessionCookiePresent", rule.runOnIdle { rule.activity.appGraph.sessions.hasAuthentication(provider) })
            observations.put(provider.name, result)
            close()
        }
        val directory = File(rule.activity.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "official-login-pages.json").writeText(observations.toString(2))
    }

    @Test fun percentageHeightLoginFormIsVisibleAndAcceptsTouch() {
        open(Provider.INSTAGRAM)
        val web = rule.runOnIdle { browser() }
        rule.runOnUiThread {
            web.stopLoading()
            web.loadDataWithBaseURL(null, """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body,#login-fixture {height:100%;margin:0}
                #login-fixture {overflow:hidden;background:#117744}
                form {padding:20px} input {display:block;width:200px;height:40px;margin-bottom:12px}</style>
                </head><body><div id="login-fixture"><form>
                <input id="fixture-username" type="text"><input type="password">
                </form></div></body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        var loaded = false
        rule.waitUntil(15_000) {
            if (!loaded) rule.runOnUiThread {
                web.evaluateJavascript("document.readyState==='complete'&&!!document.getElementById('login-fixture')") { loaded = it == "true" }
            }
            loaded
        }
        var encoded: String? = null
        rule.runOnUiThread {
            web.evaluateJavascript("""
                (function() {
                  var field=document.getElementById('fixture-username'), r=field.getBoundingClientRect();
                  var x=r.x+r.width/2, y=r.y+r.height/2;
                  return JSON.stringify({height:document.getElementById('login-fixture').getBoundingClientRect().height,
                    viewportHeight:innerHeight,viewportWidth:innerWidth,x:x,y:y,touchable:document.elementFromPoint(x,y)===field});
                })()
            """.trimIndent()) { encoded = it }
        }
        rule.waitUntil(10_000) { encoded != null }
        val geometry = JSONObject(org.json.JSONArray("[$encoded]").getString(0))
        assertEquals("Percentage-height login content must fill its viewport", geometry.getDouble("viewportHeight"), geometry.getDouble("height"), 1.0)
        assertTrue("Login input must be visible to hit testing", geometry.getBoolean("touchable"))
        var visualReady = false
        rule.runOnUiThread {
            web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { visualReady = true }
            })
        }
        rule.waitUntil(10_000) { visualReady }
        rule.runOnIdle {
            val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bitmap))
            val center = bitmap.getPixel(web.width / 2, web.height / 2) and 0x00ffffff
            bitmap.recycle()
            assertEquals("Login content must be painted", 0x00117744, center)
            val scale = web.width / geometry.getDouble("viewportWidth")
            val x = (geometry.getDouble("x") * scale).toFloat()
            val y = (geometry.getDouble("y") * scale).toFloat()
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                web.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        var focused = false
        rule.waitUntil(10_000) {
            if (!focused) rule.runOnUiThread {
                web.evaluateJavascript("document.activeElement.id==='fixture-username'") { focused = it == "true" }
            }
            focused
        }
        close()
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
