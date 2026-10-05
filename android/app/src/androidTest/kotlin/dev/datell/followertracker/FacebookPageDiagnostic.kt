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
import dev.datell.followertracker.ui.facebookBrowserFallback
import dev.datell.followertracker.ui.captureConfirmedFacebookPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit Page URL probe. Reads structural flags only and never saves a connection. */
@RunWith(AndroidJUnit4::class)
class FacebookPageDiagnostic {
    @Test fun inspectRequestedFacebookPage() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("probeFacebookPageUrl")
        assumeTrue(url != null && Provider.FACEBOOK.allows(url))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val identity = context.appGraph.sessions.identity(Provider.FACEBOOK)
        assumeTrue(context.appGraph.sessions.hasAuthentication(Provider.FACEBOOK) && identity != null)
        val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
        val desktop = InstrumentationRegistry.getArguments().getString("probeFacebookDesktop") == "true"
        val confirmation = InstrumentationRegistry.getArguments().getString("probeFacebookConfirmation") == "true"
        val browser = SessionLoginBrowser(Provider.FACEBOOK, checkNotNull(url))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { TrackerTheme {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { browser.createView(it).also {
                        browser.active?.let { web ->
                            web.webViewClient = navigationProbe(web.webViewClient, checkNotNull(url))
                            if (desktop) {
                                val version = Regex("Chrome/[0-9.]+").find(web.settings.userAgentString)?.value ?: error("Chrome version required")
                                web.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $version Safari/537.36"
                            }
                        }
                    } })
                } }
            }
            try {
                delay(10_000)
                val confirmedPayload = if (confirmation) withContext(Dispatchers.Main.immediate) {
                    captureConfirmedFacebookPage(checkNotNull(browser.active), identity, script, null) { browser.loading }
                } else null
                val result = withTimeout(25_000) {
                    while (true) {
                        val value = CompletableDeferred<String?>()
                        instrumentation.runOnMainSync {
                            val web = browser.active
                            if (web == null || browser.loading || !Provider.FACEBOOK.allows(web.url.orEmpty())) value.complete(null)
                            else web.evaluateJavascript("""$script;(function(){
                                const id=${JSONObject.quote(identity)};
                                const expected=new URL(${JSONObject.quote(url)}),current=new URL(location.href);
                                const canonical=document.querySelector('link[rel="canonical"]')?.href||document.querySelector('meta[property="og:url"]')?.content;
                                let canonicalURL;try{canonicalURL=new URL(canonical,current);}catch(_){}
                                const roots=[];let remaining=4*1024*1024;
                                for(const s of document.querySelectorAll('script[type="application/json"],script[data-sjs]')){
                                  const t=s.textContent||'';remaining-=t.length;if(remaining<0)break;
                                  try{roots.push(JSON.parse(t));}catch(_){}
                                }
                                const headings=Array.from(document.querySelectorAll('h1,h2,[role="heading"]'));
                                const headingNames=new Set(headings.map(h=>h.textContent?.trim()).filter(Boolean));
                                const expectedPath=expected.pathname.replace(/\/$/,'').toLowerCase();
                                const matchesURL=value=>{try{const u=new URL(value,current);return /(^|\.)facebook\.com$/.test(u.hostname)&&u.pathname.replace(/\/$/,'').toLowerCase()===expectedPath;}catch(_){return false;}};
                                const queue=roots.slice(),records=[],candidates=[],followerFields=[];
                                for(let i=0;i<queue.length&&i<100000;i++){
                                  const r=queue[i];if(!r||typeof r!=='object')continue;
                                  if(!Array.isArray(r)&&(r.__typename==='Page'||r.__isPage==='Page'))records.push(r);
                                  const keys=Object.keys(r).filter(k=>/^[A-Za-z_][A-Za-z0-9_]{0,60}$/.test(k));
                                  const matchedName=typeof r.name==='string'&&headingNames.has(r.name.trim());
                                  const matchedURL=['url','profile_url','profile_uri','page_uri'].some(k=>typeof r[k]==='string'&&matchesURL(r[k]));
                                  const matchedUsername=typeof r.username==='string'&&expectedPath==='/'+r.username.toLowerCase();
                                  const social=Array.isArray(r.profile_social_context?.content)?r.profile_social_context.content.slice(0,32):[];
                                  if((matchedName||matchedURL||matchedUsername)&&candidates.length<16)candidates.push({
                                    type:['User','Page'].includes(r.__typename)?r.__typename:'other',numericID:/^[0-9]+$/.test(String(r.id||'')),
                                    owner:String(r.id||'')===id,matchedName,matchedURL,matchedUsername,fields:keys.slice(0,60),
                                    pageFlags:['is_profile_plus','is_profile_page','is_page','is_business_page'].filter(k=>r[k]===true),
                                    delegatePage:!!r.delegate_page,delegateFields:r.delegate_page?Object.keys(r.delegate_page).filter(k=>/^[A-Za-z_][A-Za-z0-9_]{0,60}$/.test(k)):[],
                                    delegateSameID:!!r.delegate_page&&String(r.delegate_page.id||'')===String(r.id||''),
                                    socialFields:r.profile_social_context?Object.keys(r.profile_social_context):[],
                                    socialContentFields:Array.from(new Set(social.flatMap(s=>Object.keys(s).filter(k=>/^[A-Za-z_][A-Za-z0-9_]{0,60}$/.test(k))))),
                                    socialExactFollowers:social.some(s=>/^(?:[0-9][0-9,\s]*\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*[0-9][0-9,\s]*(?:명)?)$/i.test(typeof s.text==='string'?s.text:s.text?.text||'')),
                                    socialRoundedFollowers:social.some(s=>/[0-9](?:\.[0-9]+)?\s*(?:[kmb]|천|만|억)/i.test(typeof s.text==='string'?s.text:s.text?.text||'')),
                                    exactFollowers:Number.isSafeInteger(r.followers_count??r.follower_count??r.followers?.count)});
                                  for(const k of keys.filter(k=>/follower|subscriber/i.test(k))){
                                    if(followerFields.length<24)followerFields.push({field:k,exact:Number.isSafeInteger(r[k]),object:!!r[k]&&typeof r[k]==='object',matchedName,matchedURL});
                                  }
                                  for(const child of Object.values(r))if(child&&typeof child==='object')queue.push(child);
                                }
                                const shapes=headings.slice(0,16).map(h=>{
                                  const scopes=[];
                                  for(let p=h.parentElement,d=1;p&&d<=5;p=p.parentElement,d++){
                                    const nodes=Array.from(p.querySelectorAll('a[href],button,[role="button"],span'));
                                    scopes.push({depth:d,headings:p.querySelectorAll('h1').length,
                                      feed:!!p.querySelector('article,[role="article"],[role="feed"]'),
                                      pageLabel:/(?:페이지|Page)\s*[·•]/i.test(p.innerText||p.textContent||''),
                                      exactFollower:nodes.some(n=>/^(?:[0-9][0-9,\s]*\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*[0-9][0-9,\s]*(?:명)?)$/i.test((n.innerText||n.textContent||'').trim())),
                                      roundedFollower:nodes.some(n=>/팔로워|followers/i.test(n.textContent||'')&&/[0-9](?:\.[0-9]+)?\s*(?:[kmb]|만|천|억)/i.test(n.textContent||''))});
                                    if(p.tagName==='BODY'||p.tagName==='HTML')break;
                                  }
                                  const level=h.getAttribute('aria-level');
                                  return {tag:h.tagName,level:['1','2','3','4','5','6'].includes(level)?level:'other',nameInTitle:!!h.textContent?.trim()&&document.title.includes(h.textContent.trim()),scopes};
                                });
                                const capture=${confirmedPayload?.let { "JSON.parse(${JSONObject.quote(it)})" }
                                    ?: "FollowerTrackerCapture.captureFacebookPage?.(id,null)||{error:'UNAVAILABLE'}"};
                                const route=u=>!u?'missing':u.pathname==='/'?'home':u.pathname==='/profile.php'?'profile':/login|checkpoint|challenge/.test(u.pathname)?'authentication':u.pathname==='/home.php'?'feed':/\/posts|\/reels|\/photos/.test(u.pathname)?'content':u.pathname===expected.pathname?'requested':'other';
                                return JSON.stringify({authenticated:true,requestedPath:current.pathname.replace(/\/$/,'').toLowerCase()===expected.pathname.replace(/\/$/,'').toLowerCase(),
                                  currentRoute:route(current),canonicalRoute:route(canonicalURL),
                                  login:/login|checkpoint|challenge/.test(current.pathname),profileID:/^[0-9]+$/.test(current.searchParams.get('id')||''),
                                  canonical:!!canonicalURL,canonicalProfileID:!!canonicalURL&&/^[0-9]+$/.test(canonicalURL.searchParams.get('id')||''),
                                  canonicalSamePath:canonicalURL?.pathname===current.pathname,
                                  pageIDMeta:!!document.querySelector('meta[property="fb:page_id"]'),
                                  androidPage:!!document.querySelector('meta[property="al:android:url"]')?.content?.match(/^fb:\/\/page\/[0-9]+/),
                                  androidProfile:!!document.querySelector('meta[property="al:android:url"]')?.content?.match(/^fb:\/\/profile\/[0-9]+/),
                                  desktop:$desktop,confirmation:$confirmation,scripts:document.scripts.length,inlineScripts:document.querySelectorAll('script:not([src])').length,
                                  jsonRoots:roots.length,pageRecords:records.length,candidates,followerFields,
                                  contentUnavailable:/이 콘텐츠는 현재|현재 이 페이지를 이용|콘텐츠를 찾을 수|content (?:isn't|is not) available|page (?:isn't|is not) available/i.test(document.body?.innerText||''),
                                  canonicalRequestedPath:!!canonicalURL&&matchesURL(canonicalURL.href),
                                  canonicalPeople:canonicalURL?.pathname.startsWith('/people/'),canonicalP:canonicalURL?.pathname.startsWith('/p/'),
                                  canonicalNumericSuffix:/[0-9]+\/?$/.test(canonicalURL?.pathname||''),
                                  followerNodes:Array.from(document.querySelectorAll('a[href],button,[role="button"],span')).slice(0,20000).filter(n=>{const t=(n.innerText||n.textContent||'').trim();return t.length<140&&/followers|팔로워/i.test(t);}).slice(0,24).map(n=>({
                                    tag:n.tagName,role:['button','link'].includes(n.getAttribute('role'))?n.getAttribute('role'):'other',inFeed:!!n.closest('article,[role="article"],[role="feed"]'),
                                    exact:/^(?:[0-9][0-9,\s]*\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*[0-9][0-9,\s]*(?:명)?)$/i.test((n.innerText||n.textContent||'').trim()),
                                    titleExact:FollowerTrackerCapture.exactCount(n.getAttribute('title'))!==null,
                                    ariaExact:FollowerTrackerCapture.exactCount(n.getAttribute('aria-label'))!==null,
                                    rounded:/[0-9](?:\.[0-9]+)?\s*(?:[kmb]|만|천|억)/i.test(n.innerText||n.textContent||'')})),
                                  recordFields:['id','name','username','url','followers_count','follower_count','followers','profile_id'].filter(k=>records.some(r=>Object.hasOwn(r,k))),
                                  bodyPageLabel:/(?:페이지|Page)\s*[·•]/i.test(document.body?.innerText||''),headingShapes:shapes,
                                  captureResult:capture.error||'SUCCESS',capturedPage:capture.accountType==='PAGE',capturedOwner:capture.sessionOwnerId===id,
                                  capturedExact:Number.isSafeInteger(capture.followers),browserNotice:${browser.notice != null},
                                  browserNoticeMessage:${browser.notice?.let(JSONObject::quote) ?: "null"},
                                  blockedScheme:${browser.blockedDestination?.scheme?.let(JSONObject::quote) ?: "null"},
                                  blockedHost:${browser.blockedDestination?.host?.let(JSONObject::quote) ?: "null"}});
                            })();""".trimIndent()) { value.complete(it) }
                        }
                        val encoded = value.await()
                        if (encoded != null) return@withTimeout JSONObject(JSONArray("[$encoded]").getString(0))
                        delay(500)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("FACEBOOK_PAGE_DIAGNOSTIC", result.toString()) })
            } finally { instrumentation.runOnMainSync { browser.dispose() } }
        }
    }

    private fun navigationProbe(delegate: WebViewClient, requestedUrl: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.scheme == "intent") {
                val intent = runCatching { Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                val fallback = intent?.getStringExtra("browser_fallback_url")?.let { runCatching { java.net.URI(it) }.getOrNull() }
                val requested = java.net.URI(requestedUrl)
                val metadata = JSONObject().put("facebookScheme", intent?.data?.scheme == "fb")
                    .put("facebookPackage", intent?.`package` in listOf("com.facebook.katana", "com.facebook.lite"))
                    .put("nativeProfile", intent?.data?.host == "profile")
                    .put("fallback", fallback != null)
                    .put("fallbackAllowed", fallback?.let { Provider.FACEBOOK.allows(it.toString()) } ?: false)
                    .put("fallbackProfile", fallback?.path == "/profile.php")
                    .put("fallbackRequestedPath", fallback?.path?.trimEnd('/')?.equals(requested.path.trimEnd('/'), true) == true)
                    .put("accepted", facebookBrowserFallback(Provider.FACEBOOK, view.url.orEmpty(), request.url.toString()) != null)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("FACEBOOK_PAGE_NAVIGATION", metadata.toString())
                })
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
