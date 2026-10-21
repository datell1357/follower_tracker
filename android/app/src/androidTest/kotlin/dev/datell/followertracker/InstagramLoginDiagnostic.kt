package dev.datell.followertracker

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.ui.captureWebSession
import dev.datell.followertracker.ui.loginUserAgent
import dev.datell.followertracker.ui.installProfileResponseObserver
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.TrackerViewModel
import dev.datell.followertracker.ui.SessionLoginDialog
import dev.datell.followertracker.ui.TrackerTheme
import dev.datell.followertracker.sync.RapidTracking
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in existing-session diagnostics. Inspection is read-only; explicit connection uses the real app flow. */
@RunWith(AndroidJUnit4::class)
class InstagramLoginDiagnostic {
    /** Launches the production login dialog and ViewModel. Never call while a user is signing in or tracking is active. */
    @Test fun connectAuthenticatedInstagramThroughTheAppFlow() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeInstagramConnect") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.appGraph
        assumeTrue(graph.sessions.hasAuthentication(Provider.INSTAGRAM) && !RapidTracking.state.value.running)
        val completed = AtomicBoolean()
        val connection = AtomicReference<Job?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[TrackerViewModel::class.java]
                activity.setContent { TrackerTheme {
                    val state by model.state.collectAsState()
                    SessionLoginDialog(Provider.INSTAGRAM, state.busy, state.message, onDismiss = {}, onConnect = { payload, agent ->
                        connection.set(model.connect(Provider.INSTAGRAM, payload, agent) { completed.set(true) })
                    })
                } }
            }
            withTimeoutOrNull(60_000) {
                while (!completed.get() || connection.get()?.isCompleted != true) delay(250)
            }
            val row = graph.repository.widgetOverviews().firstOrNull { it.account.provider == Provider.INSTAGRAM }
            val result = JSONObject().put("automaticCallback", completed.get())
                .put("sessionRetained", graph.sessions.hasAuthentication(Provider.INSTAGRAM))
                .put("accountStored", row != null).put("metricStored", row?.latest != null)
                .put("ownerMatches", row != null && row.account.stableId == graph.sessions.identity(Provider.INSTAGRAM))
                .put("metadataStored", graph.sessions.metadata(Provider.INSTAGRAM) != null)
                .put("status", row?.account?.status?.name ?: "UNCONNECTED")
                .put("source", row?.latest?.source ?: "NONE")
            instrumentation.sendStatus(2, Bundle().apply { putString("INSTAGRAM_APP_CONNECTION", result.toString()) })
            assertTrue("The real automatic callback must store the signed-in owner's first observation", completed.get() &&
                result.getBoolean("sessionRetained") && result.getBoolean("ownerMatches") && result.getBoolean("metadataStored") && result.getBoolean("metricStored"))
        }
    }

    @Test fun inspectAuthenticatedLocalProfile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeInstagramCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessions = context.appGraph.sessions
        val authenticated = sessions.hasAuthentication(Provider.INSTAGRAM)
        instrumentation.sendStatus(2, Bundle().apply { putBoolean("instagramAuthenticated", authenticated) })
        assumeTrue(authenticated)
        val identity = checkNotNull(sessions.identity(Provider.INSTAGRAM))
        val script = context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() }
        withContext(Dispatchers.Main) {
            val web = WebView(context)
            val observing = installProfileResponseObserver(web, Provider.INSTAGRAM)
            WebView.setWebContentsDebuggingEnabled(false)
            var loading = true
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                userAgentString = loginUserAgent(WebSettings.getDefaultUserAgent(context))
                blockNetworkImage = true; allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true; loadWithOverviewMode = true
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !Provider.INSTAGRAM.allows(request.url.toString())
                override fun onPageFinished(view: WebView, url: String?) { loading = false }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
            }
            val metrics = context.resources.displayMetrics
            web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            try {
                web.loadUrl("https://www.instagram.com/")
                var navigated = false
                var result = JSONObject()
                var profileSeenAt: Long? = null
                withTimeoutOrNull(35_000) {
                    while (true) {
                        if (!loading && Provider.INSTAGRAM.allows(web.url.orEmpty())) {
                            val local = JSONObject(captureWebSession(web, Provider.INSTAGRAM, identity, script, allowRequest = false))
                            val profile = local.optString("profileURL")
                            if (!navigated && local.optString("error") == "own_profile_required" && Provider.INSTAGRAM.allows(profile)) {
                                navigated = true; loading = true; web.loadUrl(profile)
                            } else {
                                val encoded = CompletableDeferred<String>()
                                web.evaluateJavascript("""(function(){
                                    const id=${JSONObject.quote(identity)}, roots=[];let budget=4*1024*1024;
                                    for(const s of document.querySelectorAll('script[type="application/json"],script[data-sjs]')){
                                        const text=s.textContent||'';budget-=text.length;if(budget<0)break;
                                        try{roots.push(JSON.parse(text));}catch(_){}
                                    }
                                    const queue=roots.slice(),owners=[],initial=[];
                                    for(let i=0;i<queue.length&&i<100000;i++){
                                        const v=queue[i];if(!v||typeof v!=='object')continue;
                                        if(String(v.pk??v.id)===id)owners.push(v);
                                        if(String(v.USER_ID)===id)initial.push(v);
                                        for(const x of Object.values(v))if(x&&typeof x==='object')queue.push(x);
                                    }
                                    const description=document.querySelector('meta[property="og:description"]')?.getAttribute('content')||'';
                                    const canonical=document.querySelector('link[rel="canonical"]')?.getAttribute('href')||document.querySelector('meta[property="og:url"]')?.getAttribute('content');
                                    const owner=owners.find(v=>typeof v.username==='string');
                                    const ownerPath=owner?'/'+owner.username.toLowerCase()+'/':null;
                                    const exact=v=>typeof v==='string'&&/^[0-9][0-9,\s\u00a0\u202f]*$/.test(v.trim());
                                    const links=Array.from(document.querySelectorAll('a[href]')).filter(a=>{
                                        try{const u=new URL(a.getAttribute('href'),location.href);return u.origin===location.origin&&
                                            u.pathname.toLowerCase().replace(/\/$/,'')===ownerPath?.slice(0,-1)+'/followers';}catch(_){return false;}
                                    });
                                    const linkText=links.map(a=>(a.textContent||'').trim());
                                    const spans=links.flatMap(a=>Array.from(a.querySelectorAll('span')));
                                    const labeled=v=>typeof v==='string'&&(/^(?:팔로워|followers?)\s*[0-9][0-9,\s\u00a0\u202f]*(?:명)?$/i.test(v.trim())||
                                        /^[0-9][0-9,\s\u00a0\u202f]*(?:명의?\s*)?(?:팔로워|followers?)$/i.test(v.trim()));
                                    const allLinkNodes=links.flatMap(a=>[a,...Array.from(a.querySelectorAll('[title],[aria-label],[data-count],[data-tooltip-content]'))]);
                                    const globals=globalThis._sharedData;
                                    const globalUser=globals?.entry_data?.ProfilePage?.[0]?.graphql?.user;
                                    let canonicalOwner=false;try{const c=new URL(canonical,location.href);canonicalOwner=c.origin===location.origin&&c.pathname.toLowerCase().replace(/\/?$/,'/')===ownerPath;}catch(_){}
                                    return JSON.stringify({
                                        profilePath:/^\/[A-Za-z0-9._]{1,30}\/?$/.test(location.pathname),
                                        login:/\/accounts\/login/.test(location.pathname),challenge:/challenge|checkpoint/.test(location.pathname),
                                        serverJSON:roots.length>0,ownerRecord:owners.length>0,
                                        ownerUsername:owners.some(v=>typeof v.username==='string'),
                                        initialOwner:initial.length>0,initialUsername:initial.some(v=>typeof v.USERNAME==='string'),
                                        ownerFollowerCount:owners.some(v=>Number.isSafeInteger(v.follower_count??v.edge_followed_by?.count)),
                                        observedOwnProfile:globalThis.__followerTrackerObservedProfiles?.profiles?.some(v=>String(v.id)===id)===true,
                                        followerLinks:!!document.querySelector('a[href*="/followers"]'),
                                        boundFollowerLinks:links.length>0,
                                        exactLinkTitle:links.some(a=>exact(a.getAttribute('title'))),
                                        exactSpanTitle:spans.some(a=>exact(a.getAttribute('title'))),
                                        exactSpanText:spans.some(a=>exact(a.textContent)),
                                        exactLinkAttribute:allLinkNodes.some(a=>['title','aria-label','data-count','data-tooltip-content'].some(k=>exact(a.getAttribute(k))||labeled(a.getAttribute(k)))),
                                        alternateOwnerCounter:queue.some(v=>v&&typeof v==='object'&&(String(v.pk_id)===id||String(v.user_id)===id)&&Number.isSafeInteger(v.follower_count??v.followers_count??v.edge_followed_by?.count)),
                                        usernameCounter:queue.some(v=>v&&typeof v==='object'&&owner&&v.username===owner.username&&Number.isSafeInteger(v.follower_count??v.followers_count??v.edge_followed_by?.count)),
                                        sharedData:!!globals,sharedOwner:!!globalUser&&String(globalUser.id)===id,
                                        sharedOwnerCounter:!!globalUser&&String(globalUser.id)===id&&Number.isSafeInteger(globalUser.edge_followed_by?.count??globalUser.follower_count),
                                        inlineSharedData:Array.from(document.querySelectorAll('script:not([src])')).some(s=>(s.textContent||'').includes('window._sharedData')),
                                        prefixLabeledFollowers:linkText.some(t=>/^(?:팔로워|followers?)\s*[0-9][0-9,\s\u00a0\u202f]*(?:명)?$/i.test(t)),
                                        suffixLabeledFollowers:linkText.some(t=>/^[0-9][0-9,\s\u00a0\u202f]*(?:명의?\s*)?(?:팔로워|followers?)$/i.test(t)),
                                        shortenedFollowerText:linkText.some(t=>/[0-9](?:\.[0-9]+)?\s*(?:[km]|만|천|억)/i.test(t)),
                                        followingLinks:!!document.querySelector('a[href*="/following"]'),
                                        header:!!document.querySelector('header'),
                                        description:!!document.querySelector('meta[property="og:description"]'),
                                        canonicalOwner:canonicalOwner,
                                        descriptionFollowerLabel:/followers?|팔로워/i.test(description),
                                        descriptionExactFollowers:/^[0-9][0-9, \u00a0\u202f]*\s+followers?(?:\s|,|$)|^팔로워\s*[0-9][0-9, \u00a0\u202f]*명(?:\s|,|$)/i.test(description),
                                        headings:!!document.querySelector('h1,h2'),
                                        scriptBudgetExceeded:budget<0
                                    });
                                })();""".trimIndent()) { encoded.complete(it) }
                                result = JSONObject(JSONArray("[${encoded.await()}]").getString(0))
                                result.put("result", local.optString("error", "SUCCESS"))
                                    .put("capturedOwner", local.optString("stableId") == identity)
                                    .put("exactCount", !local.has("error") && local.optString("precision") == "EXACT")
                                if (result.optBoolean("profilePath") && profileSeenAt == null) profileSeenAt = android.os.SystemClock.elapsedRealtime()
                                if (!local.has("error") || profileSeenAt?.let { android.os.SystemClock.elapsedRealtime() - it >= 15_000 } == true) return@withTimeoutOrNull
                            }
                        }
                        delay(1_000)
                    }
                }
                result.put("authenticatedAfter", sessions.hasAuthentication(Provider.INSTAGRAM)).put("profileNavigated", navigated).put("observerInstalled", observing)
                instrumentation.sendStatus(2, Bundle().apply { putString("INSTAGRAM_LOCAL_CAPTURE", result.toString()) })
            } finally { web.stopLoading(); web.destroy() }
        }
    }
}
