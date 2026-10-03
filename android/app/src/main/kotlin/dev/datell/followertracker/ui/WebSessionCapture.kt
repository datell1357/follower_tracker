package dev.datell.followertracker.ui

import android.webkit.WebView
import dev.datell.followertracker.core.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Executes only within the bounded provider WebView; no JavaScript bridge or form access. */
suspend fun captureWebSession(web: WebView, provider: Provider, identity: String?, script: String,
    allowRequest: Boolean = true): String = withContext(Dispatchers.Main.immediate) {
    if (!provider.allows(web.url.orEmpty())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
    val slot = JSONObject.quote("__followerCapture_" + UUID.randomUUID().toString().replace("-", ""))
    val arguments = JSONObject.quote(provider.name) + "," + (identity?.let(JSONObject::quote) ?: "null")
    val read = if (allowRequest) "FollowerTrackerCapture.captureAsync($arguments)"
        else "Promise.resolve(FollowerTrackerCapture.capture($arguments))"
    try {
        web.evaluate("""$script;globalThis[$slot]=null;
            $read.then(function(result) {
              globalThis[$slot]=JSON.stringify(result);
            },function() {globalThis[$slot]=JSON.stringify({error:'offline'});});null;
        """.trimIndent())
        withTimeout(22_000) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!provider.allows(web.url.orEmpty())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                val encoded = web.evaluate("globalThis[$slot]")
                if (encoded != "null" && encoded != "undefined") return@withTimeout JSONArray("[$encoded]").getString(0)
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    } catch (_: TimeoutCancellationException) {
        throw CollectionFailure(SyncStatus.OFFLINE)
    } finally {
        if (provider.allows(web.url.orEmpty())) web.evaluateJavascript("delete globalThis[$slot]", null)
    }
}

private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
}

fun webCaptureFailure(result: JSONObject): CollectionFailure? {
    if (!result.has("error")) return null
    val status = when (result.optString("error")) {
        "http" -> statusForHttp(result.optInt("status")) ?: SyncStatus.FORMAT_CHANGED
        "rate_limited" -> SyncStatus.RATE_LIMITED
        "offline" -> SyncStatus.OFFLINE
        "reauth_required", "identity_missing" -> SyncStatus.REAUTH_REQUIRED
        "own_profile_required", "owner_context_missing", "check_required" -> SyncStatus.CHECK_REQUIRED
        else -> SyncStatus.FORMAT_CHANGED
    }
    val retry = if (result.isNull("retryAfterSeconds")) null else result.optLong("retryAfterSeconds").coerceIn(60, 86_400)
    return CollectionFailure(status, retry)
}
