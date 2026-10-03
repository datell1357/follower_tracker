package dev.datell.followertracker

import android.os.Bundle
import android.view.WindowManager
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
        val browser = SessionLoginBrowser(Provider.FACEBOOK)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { TrackerTheme {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = browser::createView)
                } }
            }
            try {
                delay(3_000)
                instrumentation.runOnMainSync { browser.active?.loadUrl(checkNotNull(url)) }
                delay(7_000)
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
                                const queue=roots.slice(),records=[];
                                for(let i=0;i<queue.length&&i<100000;i++){
                                  const r=queue[i];if(!r||typeof r!=='object')continue;
                                  if(!Array.isArray(r)&&(r.__typename==='Page'||r.__isPage==='Page'))records.push(r);
                                  for(const child of Object.values(r))if(child&&typeof child==='object')queue.push(child);
                                }
                                const headings=Array.from(document.querySelectorAll('h1'));
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
                                  return {nameInTitle:!!h.textContent?.trim()&&document.title.includes(h.textContent.trim()),scopes};
                                });
                                const capture=FollowerTrackerCapture.captureFacebookPage?.(id,null)||{error:'UNAVAILABLE'};
                                return JSON.stringify({authenticated:true,requestedPath:current.pathname.replace(/\/$/,'').toLowerCase()===expected.pathname.replace(/\/$/,'').toLowerCase(),
                                  login:/login|checkpoint|challenge/.test(current.pathname),profileID:/^[0-9]+$/.test(current.searchParams.get('id')||''),
                                  canonical:!!canonicalURL,canonicalProfileID:!!canonicalURL&&/^[0-9]+$/.test(canonicalURL.searchParams.get('id')||''),
                                  canonicalSamePath:canonicalURL?.pathname===current.pathname,
                                  pageIDMeta:!!document.querySelector('meta[property="fb:page_id"]'),
                                  androidPage:!!document.querySelector('meta[property="al:android:url"]')?.content?.match(/^fb:\/\/page\/[0-9]+/),
                                  androidProfile:!!document.querySelector('meta[property="al:android:url"]')?.content?.match(/^fb:\/\/profile\/[0-9]+/),
                                  jsonRoots:roots.length,pageRecords:records.length,
                                  recordFields:['id','name','username','url','followers_count','follower_count','followers','profile_id'].filter(k=>records.some(r=>Object.hasOwn(r,k))),
                                  bodyPageLabel:/(?:페이지|Page)\s*[·•]/i.test(document.body?.innerText||''),headingShapes:shapes,
                                  captureResult:capture.error||'SUCCESS',capturedPage:capture.accountType==='PAGE',capturedOwner:capture.sessionOwnerId===id,
                                  capturedExact:Number.isSafeInteger(capture.followers),browserNotice:${browser.notice != null}});
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
}
