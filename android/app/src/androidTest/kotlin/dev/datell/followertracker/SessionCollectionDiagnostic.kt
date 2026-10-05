package dev.datell.followertracker

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.AccountType
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.sync.ProfilePageCollector
import dev.datell.followertracker.sync.MetricsWorker
import dev.datell.followertracker.sync.RapidTracking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in existing-session diagnostics. Reads preserve observations; a dispatched real worker can save new ones.
 * Never creates fixture accounts/cookies or prints account/session values. */
@RunWith(AndroidJUnit4::class)
class SessionCollectionDiagnostic {
    @Test fun cancelQueuedLiveCountWorker() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cancelBackgroundProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(instrumentation.targetContext).cancelUniqueWork("qa-live-metrics-once")
                .result.get(10, TimeUnit.SECONDS)
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("BACKGROUND_WORKER_DIAGNOSTIC", "cancelled=true") })
    }

    /** Delayed until instrumentation exits, so the real worker runs without test foreground priority. */
    @Test fun enqueueOneLiveCountWorkerAfterTheDiagnosticExits() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeBackgroundWorker") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(!RapidTracking.state.value.running)
        val request = OneTimeWorkRequestBuilder<MetricsWorker>().setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).enqueueUniqueWork("qa-live-metrics-once", ExistingWorkPolicy.KEEP, request)
                .result.get(10, TimeUnit.SECONDS)
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("BACKGROUND_WORKER_DIAGNOSTIC", "enqueued=true") })
    }

    @Test fun inspectStoredProfileWithoutChangingItsObservation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("probeSessionCollection") == "true")
        val provider = Provider.valueOf(checkNotNull(args.getString("provider")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == provider && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(before != null)
        val original = checkNotNull(before)
        val metadata = graph.sessions.metadata(provider)
        assumeTrue(metadata != null)
        val reader = ProfilePageCollector(context, graph.sessions)
        val report = JSONObject().put("provider", provider.name)
            .put("sessionBefore", graph.sessions.hasAuthentication(provider))
            .put("ownerBefore", graph.sessions.identity(provider) == original.account.stableId)
            .put("storedStatus", original.account.status.name)
        try {
            try {
                val payload = JSONObject(reader.read(original.account, checkNotNull(metadata).userAgent))
                report.put("result", "SUCCESS")
                    .put("capturedOwner", payload.optString("stableId") == original.account.stableId)
                    .put("capturedExact", payload.optString("precision") == "EXACT")
            } catch (failure: CollectionFailure) { report.put("result", failure.status.name) }
            val metadataPreserved = graph.sessions.metadata(provider) == metadata
            val observationPreserved = graph.repository.widgetOverviews(original.account.key).single() == original
            assertTrue("A read-only diagnostic must preserve session metadata", metadataPreserved)
            assertTrue("A read-only diagnostic must preserve the stored observation", observationPreserved)
            report.put("sessionAfter", graph.sessions.hasAuthentication(provider))
                .put("ownerAfter", graph.sessions.identity(provider) == original.account.stableId)
                .put("metadataPreserved", metadataPreserved)
                .put("observationPreserved", observationPreserved)
            instrumentation.sendStatus(2, Bundle().apply { putString("SESSION_COLLECTION_DIAGNOSTIC", report.toString()) })
        } finally { withContext(Dispatchers.Main) { reader.release() } }
    }

    @Test fun inspectRepeatedProfileReadsWithoutChangingTheStoredObservation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("probeSessionReuse") == "true")
        val provider = Provider.valueOf(checkNotNull(args.getString("provider")))
        val cycles = args.getString("probeCycles")?.toIntOrNull()?.coerceIn(2, 4) ?: 4
        val pauseSeconds = args.getString("probeReuseDelaySeconds")?.toIntOrNull()?.coerceIn(1, 60) ?: 1
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val original = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == provider && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(original != null)
        val account = checkNotNull(original).account
        val metadata = checkNotNull(graph.sessions.metadata(provider))
        val views = mutableListOf<InspectingWebView>()
        val reader = ProfilePageCollector(context, graph.sessions) { InspectingWebView(it).also { view -> views += view } }
        try {
            for (cycle in 0 until cycles) {
                val report = JSONObject().put("provider", provider.name).put("cycle", cycle)
                try {
                    val payload = JSONObject(reader.read(account, metadata.userAgent, retain = true))
                    report.put("result", "SUCCESS").put("ownerMatches", payload.optString("stableId") == account.stableId)
                } catch (failure: CollectionFailure) { report.put("result", failure.status.name) }
                withContext(Dispatchers.Main) {
                    report.put("viewsCreated", views.size)
                    val web = views.lastOrNull() ?: return@withContext
                    report.put("clearedToBlank", web.url == "about:blank")
                    if (provider.allows(web.url.orEmpty())) {
                        val script = """(function(){
                          const expected=${JSONObject.quote(account.stableId)},url=new URL(location.href);
                          let remaining=4*1024*1024;const roots=[];
                          for(const node of document.querySelectorAll('script[type="application/json"],script[data-sjs]')){
                            const text=node.textContent||'';remaining-=text.length;if(remaining<0)break;
                            try{roots.push(JSON.parse(text));}catch(_){}
                          }
                          const queue=roots.slice(),owners=[];
                          const keys=o=>o&&typeof o==='object'?Object.keys(o).filter(k=>/^[A-Za-z_][A-Za-z0-9_.]{0,60}$/.test(k)).slice(0,40):[];
                          for(let i=0;i<queue.length&&i<100000;i++){
                            const r=queue[i];if(!r||typeof r!=='object')continue;
                            if(String(r.id||'')===expected&&owners.length<12)owners.push({fields:keys(r),social:keys(r.profile_social_context),
                              exactFollowers:Number.isSafeInteger(r.followers_count??r.follower_count??r.followers?.count)});
                            for(const child of Object.values(r))if(child&&typeof child==='object')queue.push(child);
                          }
                          const label=n=>(n.innerText||n.textContent||'').trim();
                          const exact=t=>/^(?:[0-9][0-9,\s]*\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*[0-9][0-9,\s]*(?:명)?)$/i.test(t);
                          const followerNodes=Array.from(document.querySelectorAll('a[href],button,[role="button"],span')).slice(0,20000)
                            .filter(n=>label(n).length<140&&/followers|팔로워/i.test(label(n))).slice(0,16)
                            .map(n=>({tag:n.tagName,button:n.matches('a[href],button,[role="button"]'),exact:exact(label(n)),
                              feed:!!n.closest('article,[role="article"],[role="feed"]')}));
                          const headings=Array.from(document.querySelectorAll('h1')).slice(0,16).map(h=>{
                            const scopes=[];for(let p=h.parentElement,d=1;p&&d<=8;p=p.parentElement,d++){
                              scopes.push({depth:d,headings:p.querySelectorAll('h1').length,
                                feed:!!p.querySelector('article,[role="article"],[role="feed"]'),
                                followerButtons:Array.from(p.querySelectorAll('a[href],button,[role="button"]')).filter(n=>/followers|팔로워/i.test(label(n))).length});
                              if(p.tagName==='BODY'||p.tagName==='HTML')break;
                            }return {scopes};
                          });
                          const error=window.FollowerTrackerCapture?.capture(${JSONObject.quote(provider.name)},expected)?.error;
                          return JSON.stringify({captureError:typeof error==='string'&&/^[a-z_]{1,64}$/.test(error)?error:null,
                            ownPath:url.pathname==='/profile.php'&&url.searchParams.get('id')===expected,
                            mobile:url.hostname==='m.facebook.com',ready:document.readyState,bodyLength:(document.body?.innerText||'').length,
                            jsonRoots:roots.length,dataScripts:document.querySelectorAll('script[data-sjs]').length,owners,headings,followerNodes});
                        })();""".trimIndent()
                        val encoded = withTimeout(5_000) { suspendCancellableCoroutine<String> { continuation ->
                            web.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
                        } }
                        report.put("document", JSONObject(JSONArray("[$encoded]").getString(0)))
                    }
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("SESSION_REUSE_DIAGNOSTIC", report.toString()) })
                if (report.optString("result") != "SUCCESS") break
                if (cycle + 1 < cycles) delay(pauseSeconds * 1_000L)
            }
            assertTrue("Repeated reads must preserve session metadata", graph.sessions.metadata(provider) == metadata)
            assertTrue("Repeated reads must preserve stored observations", graph.repository.widgetOverviews(account.key).single() == original)
        } finally { withContext(Dispatchers.Main) { reader.release(); views.forEach { it.dispose() } } }
    }

    private class InspectingWebView(context: Context) : WebView(context) {
        override fun destroy() = Unit
        fun dispose() = super.destroy()
    }

}
