package dev.datell.followertracker

import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.WindowManager
import android.webkit.*
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

/** Opt-in inspection of existing sessions. Emits fixed schema flags, never account data or DOM. */
@RunWith(AndroidJUnit4::class)
class FacebookLoginDiagnostic {
    @Test fun verifyStoredFacebookConnection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeFacebookStored") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.appGraph
        val rows = graph.repository.overviews()
        val row = rows.firstOrNull { it.account.provider == Provider.FACEBOOK }
        val result = JSONObject().put("authenticated", graph.sessions.hasAuthentication(Provider.FACEBOOK))
            .put("connected", row != null).put("metricStored", row?.latest != null)
            .put("ownerMatchesSession", row != null && row.account.stableId == graph.sessions.identity(Provider.FACEBOOK))
            .put("status", row?.account?.status?.name ?: "UNCONNECTED")
            .put("source", row?.latest?.source ?: "NONE")
            .put("xPreserved", rows.any { it.account.provider == Provider.X && it.latest != null })
            .put("tikTokPreserved", rows.any { it.account.provider == Provider.TIKTOK && it.latest != null })
        instrumentation.sendStatus(2, Bundle().apply { putString("FACEBOOK_STORED_DIAGNOSTIC", result.toString()) })
        assertTrue("Facebook owner and exact metric must be stored through the actual app connection flow",
            result.getBoolean("authenticated") && result.getBoolean("connected") &&
                result.getBoolean("metricStored") && result.getBoolean("ownerMatchesSession"))
    }

    @Test fun inspectSignedInFacebookCapture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeFacebookCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val authenticated = graph.sessions.hasAuthentication(Provider.FACEBOOK)
        val identity = graph.sessions.identity(Provider.FACEBOOK)
        report("FACEBOOK_SESSION_DIAGNOSTIC", JSONObject().put("authenticated", authenticated).put("identity", identity != null))
        assumeTrue(authenticated && identity != null)
        val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
        val browser = SessionLoginBrowser(Provider.FACEBOOK)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { TrackerTheme {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { browser.createView(it).also {
                        browser.active?.let { web -> web.webViewClient = navigationProbe(web.webViewClient, checkNotNull(identity)) }
                    } })
                } }
            }
            suspend fun inspect(stage: String): JSONObject {
                val result = withTimeout(25_000) {
                    while (true) {
                        val value = CompletableDeferred<String?>()
                        instrumentation.runOnMainSync {
                            val web = browser.active
                            if (web == null || browser.loading || !Provider.FACEBOOK.allows(web.url.orEmpty())) value.complete(null)
                            else web.evaluateJavascript("""$script;(function(){
                                const id=${JSONObject.quote(identity)};
                                let remaining=4*1024*1024;
                                const roots=[];
                                for(const node of document.querySelectorAll('script[type="application/json"],script[data-sjs]')){
                                  const text=node.textContent||'';remaining-=text.length;if(remaining<0)break;
                                  try{roots.push(JSON.parse(text));}catch(_){}
                                }
                                const owners=[];const queue=roots.slice();
                                let followerLabel=false;
                                for(let i=0;i<queue.length&&i<100000;i++){
                                  const o=queue[i];if(!o||typeof o!=='object')continue;
                                  if(String(o.id||o.userID||o.user_id||'')===id)owners.push(o);
                                  const label=typeof o.text==='string'?o.text:typeof o.text?.text==='string'?o.text.text:typeof o.title==='string'?o.title:'';
                                  if(/followers|팔로워/i.test(label))followerLabel=true;
                                  for(const child of Object.values(o))if(child&&typeof child==='object')queue.push(child);
                                }
                                const fields=['name','username','followers_count','follower_count','followers','following_count','following',
                                  'subscribers','subscriber_count','subscribers_count','subscribe_status','friends','timeline_context_items',
                                  'profile_social_context','profile_header_renderer','profile_core','profile_actions','profile_overlay_info'];
                                const url=new URL(location.href);
                                const canonical=document.querySelector('link[rel="canonical"]')?.href;
                                const captured=FollowerTrackerCapture.capture('FACEBOOK',id);
                                let scriptsBudget=4*1024*1024;let serverJson=false,bootOwner=false;
                                for(const s of document.querySelectorAll('script:not([src])')){
                                  const text=s.textContent||'';scriptsBudget-=text.length;if(scriptsBudget<0)break;
                                  serverJson||=/__bbox|ServerJS|RelayPrefetched/.test(text);
                                  bootOwner||=text.includes(id);
                                }
                                const body=(document.body?.innerText||'').slice(0,500000);
                                const profileHeading=document.querySelector('h1');
                                const profileScopes=[];
                                for(let p=profileHeading?.parentElement,d=1;p&&d<=5;p=p.parentElement,d++){
                                  const buttons=Array.from(p.querySelectorAll('a[href],button,[role="button"]'));
                                  profileScopes.push({depth:d,tag:p.tagName,headings:p.querySelectorAll('h1').length,
                                    feed:!!p.querySelector('article,[role="article"],[role="feed"]'),buttons:buttons.length,
                                    followerButton:buttons.some(b=>/팔로워|followers/i.test((b.innerText||b.textContent||'').trim()))});
                                }
                                const followerNodes=Array.from(document.querySelectorAll('a,button,[role="button"],span,div,h1,h2')).slice(0,20000)
                                  .filter(e=>{const t=(e.innerText||e.textContent||'').trim();return t.length<140&&/followers|팔로워/i.test(t);});
                                const nodeShape=e=>{
                                  const t=(e.innerText||e.textContent||'').trim();
                                  const number='[0-9][0-9, \\u00a0\\u202f]*';
                                  const labelFirst=new RegExp('^(?:팔로워|followers)\\s*'+number+'(?:명)?$','i').test(t);
                                  const numberFirst=new RegExp('^'+number+'\\s*(?:명의\\s*)?(?:팔로워|followers)$','i').test(t);
                                  const labelOnly=/^(?:팔로워|followers)$/i.test(t);
                                  const role=e.getAttribute('role');
                                  let scopeDepth=0,scopeHeadings=0,scopeFirstHeadingTag='NONE',scopeFirstHeadingLevel='NONE',headingInTitle=false;
                                  for(let p=e.parentElement,d=1;p&&d<=8;p=p.parentElement,d++){
                                    const headings=p.querySelectorAll('h1,h2,[role="heading"]');
                                    if(headings.length){
                                      scopeDepth=d;scopeHeadings=Math.min(10,headings.length);scopeFirstHeadingTag=headings[0].tagName;
                                      const level=headings[0].getAttribute('aria-level');
                                      scopeFirstHeadingLevel=['1','2','3','4','5','6'].includes(level)?level:'NONE';
                                      const name=headings[0].textContent.trim();headingInTitle=!!name&&document.title.includes(name);break;
                                    }
                                  }
                                  return {tag:e.tagName,role:['button','link','heading'].includes(role)?role:'NONE',
                                    labelFirst,numberFirst,labelOnly,children:Math.min(10,e.childElementCount),
                                    siblingCount:Math.min(10,e.parentElement?.childElementCount||0),
                                    parentTag:e.parentElement?.tagName||'NONE',
                                    inFeed:!!e.closest('article,[role="article"],[role="feed"]'),
                                    scopeDepth,scopeHeadings,scopeFirstHeadingTag,scopeFirstHeadingLevel,headingInTitle,
                                    nextExact:FollowerTrackerCapture.exactCount(e.nextElementSibling?.textContent?.trim())!==null,
                                    previousExact:FollowerTrackerCapture.exactCount(e.previousElementSibling?.textContent?.trim())!==null};
                                };
                                const links=Array.from(document.querySelectorAll('a[href]'));
                                const ownLinks=links.filter(a=>{try{return new URL(a.getAttribute('href'),url).searchParams.get('id')===id;}catch(_){return false;}});
                                const followerLinks=Array.from(document.querySelectorAll('a[href]')).filter(a=>{
                                  try{return /followers|subscribers/i.test(new URL(a.getAttribute('href'),url).pathname+new URL(a.getAttribute('href'),url).search);}catch(_){return false;}
                                });
                                return JSON.stringify({official:url.hostname==='facebook.com'||url.hostname.endsWith('.facebook.com'),
                                  mobile:url.hostname==='m.facebook.com',login:/login|checkpoint|challenge/.test(url.pathname),
                                  home:url.pathname==='/'||url.pathname==='/home.php',profile:url.pathname==='/profile.php',
                                  profileMatchesSession:url.searchParams.get('id')===id,canonicalMatchesSession:!!canonical&&new URL(canonical,url).searchParams.get('id')===id,
                                  jsonRoots:roots.length,ownerRecords:owners.length,ownerFields:fields.filter(k=>owners.some(o=>Object.hasOwn(o,k))),
                                  followerLabel,followerLinks:followerLinks.length,
                                  scripts:document.scripts.length,inlineScripts:document.querySelectorAll('script:not([src])').length,
                                  serverJson,bootOwner,selfProfileLinks:ownLinks.length,
                                  passwordInput:!!document.querySelector('input[type="password"],input[name="pass"]'),
                                  emailInput:!!document.querySelector('input[name="email"]'),
                                  titleLogin:/log ?in|로그인/i.test(document.title),
                                  bodyFollowerLabel:/followers|팔로워/i.test(body),bodyFriendLabel:/friends|친구/i.test(body),
                                  followerNodes:followerNodes.slice(0,20).map(nodeShape),
                                  headingCount:document.querySelectorAll('h1,h2,[role="heading"]').length,
                                  h1Count:document.querySelectorAll('h1').length,profileScopes,
                                  result:captured.error||'SUCCESS',capturedOwner:captured.stableId===id,
                                  capturedExactCount:Number.isSafeInteger(captured.followers),profileAvailable:!!captured.profileURL});
                            })();""".trimIndent()) { value.complete(it) }
                        }
                        val encoded = value.await()
                        if (encoded != null) return@withTimeout JSONObject(JSONArray("[$encoded]").getString(0))
                        delay(500)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
                result.put("stage", stage).put("blockedScheme", browser.blockedDestination?.scheme ?: "NONE")
                    .put("blockedHost", browser.blockedDestination?.host ?: "NONE").put("browserNotice", browser.notice != null)
                report("FACEBOOK_CAPTURE_DIAGNOSTIC", result)
                return result
            }
            try {
                delay(6_000)
                inspect("LOGIN_RETURN")
                instrumentation.runOnMainSync {
                    browser.active?.loadUrl("https://www.facebook.com/profile.php?id=$identity")
                }
                delay(6_000)
                val profile = inspect("OWN_PROFILE")
                assertTrue("The official own profile must expose an exact count for the signed-in owner",
                    profile.getBoolean("capturedOwner") && profile.getBoolean("capturedExactCount"))
                instrumentation.runOnMainSync {
                    browser.active?.loadUrl("https://m.facebook.com/profile.php?id=$identity")
                }
                delay(6_000)
                val mobile = inspect("MOBILE_OWN_PROFILE")
                assertTrue("The mobile own profile must expose the same signed-in owner and an exact count",
                    mobile.getBoolean("capturedOwner") && mobile.getBoolean("capturedExactCount"))
            } finally {
                instrumentation.runOnMainSync { browser.dispose() }
            }
        }
    }

    private fun report(key: String, result: JSONObject) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(2, Bundle().apply { putString(key, result.toString()) })

    private fun navigationProbe(delegate: WebViewClient, identity: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.scheme == "intent") {
                val intent = runCatching { Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                val fallback = intent?.getStringExtra("browser_fallback_url")?.let { runCatching { java.net.URI(it) }.getOrNull() }
                val fields = listOf("sk", "_rdr", "_rdc", "force_web", "refid", "mibextid", "refsrc")
                val parameters = fallback?.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }
                report("FACEBOOK_NAVIGATION_DIAGNOSTIC", JSONObject()
                    .put("facebookScheme", intent?.data?.scheme == "fb")
                    .put("facebookPackage", intent?.`package` in listOf("com.facebook.katana", "com.facebook.lite"))
                    .put("fallback", fallback != null).put("fallbackAllowed", fallback?.let { Provider.FACEBOOK.allows(it.toString()) } ?: false)
                    .put("fallbackHost", fallback?.host ?: "NONE")
                    .put("fallbackOwnProfile", fallback?.path == "/profile.php" && fallback.rawQuery.orEmpty().split('&').any { it == "id=$identity" })
                    .put("fallbackFields", JSONArray(fields.filter { it in parameters })))
            }
            return delegate.shouldOverrideUrlLoading(view, request)
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = delegate.shouldInterceptRequest(view, request)
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = delegate.onPageStarted(view, url, favicon)
        override fun onPageFinished(view: WebView, url: String?) = delegate.onPageFinished(view, url)
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = delegate.doUpdateVisitedHistory(view, url, isReload)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = delegate.onReceivedError(view, request, error)
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = delegate.onReceivedHttpError(view, request, response)
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = delegate.onReceivedSslError(view, handler, error)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = delegate.onRenderProcessGone(view, detail)
    }
}
