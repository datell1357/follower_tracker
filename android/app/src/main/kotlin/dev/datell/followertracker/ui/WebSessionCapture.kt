package dev.datell.followertracker.ui

import android.webkit.WebView
import dev.datell.followertracker.core.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A manual Page confirmation can request one desktop document; subsequent reads are local DOM polls. */
suspend fun captureConfirmedFacebookPage(web: WebView, identity: String?, script: String,
    expectedPageId: String?, loading: () -> Boolean): String = withContext(Dispatchers.Main.immediate) {
    val selected = web.url.orEmpty()
    var latest = captureWebSession(web, Provider.FACEBOOK, identity, script, allowRequest = false,
        facebookPage = true, expectedPageId = expectedPageId)
    if (JSONObject(latest).optString("error") !in setOf("page_required", "page_identity_missing", "exact_count_missing", "rounded_count_only"))
        return@withContext latest
    val desktopUrl = facebookPageDesktopUrl(selected) ?: return@withContext latest
    val originalAgent = web.settings.userAgentString
    val desktopAgent = facebookPageUserAgent(originalAgent) ?: return@withContext latest
    val originalImages = web.settings.blockNetworkImage
    try {
        web.settings.blockNetworkImage = true
        web.settings.userAgentString = desktopAgent
        web.loadUrl(desktopUrl)
        withTimeoutOrNull(20_000) {
            // Yield to WebView navigation before inspecting the replacement document.
            delay(250)
            repeat(20) {
                currentCoroutineContext().ensureActive()
                if (!sameFacebookPageTarget(selected, web.url.orEmpty()))
                    throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                if (!loading()) {
                    latest = captureWebSession(web, Provider.FACEBOOK, identity, script, allowRequest = false,
                        facebookPage = true, expectedPageId = expectedPageId)
                    val error = JSONObject(latest).optString("error")
                    if (error !in setOf("page_required", "page_identity_missing", "exact_count_missing"))
                        return@withTimeoutOrNull latest
                }
                delay(500)
            }
            latest
        } ?: latest
    } finally {
        // The saved login and personal profile collector keep their original mobile representation.
        if (web.isAttachedToWindow) {
            web.settings.userAgentString = originalAgent
            web.settings.blockNetworkImage = originalImages
        }
    }
}

/** Executes only within the bounded provider WebView; no JavaScript bridge or form access. */
suspend fun captureWebSession(web: WebView, provider: Provider, identity: String?, script: String,
    allowRequest: Boolean = true, facebookPage: Boolean = false, expectedPageId: String? = null): String = withContext(Dispatchers.Main.immediate) {
    if (!provider.allows(web.url.orEmpty())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
    val initialUrl = web.url
    val slot = JSONObject.quote("__followerCapture_" + UUID.randomUUID().toString().replace("-", ""))
    val arguments = JSONObject.quote(provider.name) + "," + (identity?.let(JSONObject::quote) ?: "null")
    val read = if (facebookPage && provider == Provider.FACEBOOK) "Promise.resolve(FollowerTrackerCapture.captureFacebookPage(" +
        (identity?.let(JSONObject::quote) ?: "null") + "," + (expectedPageId?.let(JSONObject::quote) ?: "null") + "))"
        else if (allowRequest) "FollowerTrackerCapture.captureAsync($arguments)"
        else "Promise.resolve(FollowerTrackerCapture.capture($arguments))"
    try {
        if (!allowRequest && !facebookPage) {
            // Profile DOM capture is synchronous. Read it once in this document, without Promise slots/polling.
            val encoded = withTimeoutOrNull(22_000) {
                web.evaluate("$script;JSON.stringify(FollowerTrackerCapture.capture($arguments));")
            } ?: throw CollectionFailure(SyncStatus.OFFLINE)
            if (!provider.allows(web.url.orEmpty())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            // A same-origin route change can be normal hydration. Discard this document's result and re-read locally.
            if (web.url != initialUrl) return@withContext "{\"error\":\"document_changed\"}"
            return@withContext JSONArray("[$encoded]").getString(0)
        }
        withTimeoutOrNull(22_000) {
            // Include the initial JavaScript callback in the same bounded operation.
            web.evaluate("""$script;globalThis[$slot]=null;
            $read.then(function(result) {
              globalThis[$slot]=JSON.stringify(result);
            },function() {globalThis[$slot]=JSON.stringify({error:'offline'});});null;
        """.trimIndent())
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!provider.allows(web.url.orEmpty())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                if (facebookPage && web.url != initialUrl) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                val encoded = web.evaluate("globalThis[$slot]")
                if (encoded != "null" && encoded != "undefined") return@withTimeoutOrNull JSONArray("[$encoded]").getString(0)
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } ?: throw CollectionFailure(SyncStatus.OFFLINE)
    } catch (_: JSONException) {
        throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
    } finally {
        if ((allowRequest || facebookPage) && provider.allows(web.url.orEmpty())) web.evaluateJavascript("delete globalThis[$slot]", null)
    }
}

private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
}

fun webCaptureFailure(result: JSONObject, sessionIdentityAvailable: Boolean = false): CollectionFailure? {
    if (!result.has("error")) return null
    val status = when (result.optString("error")) {
        "http" -> statusForHttp(result.optInt("status")) ?: SyncStatus.FORMAT_CHANGED
        "rate_limited" -> SyncStatus.RATE_LIMITED
        "offline" -> SyncStatus.OFFLINE
        "reauth_required" -> SyncStatus.REAUTH_REQUIRED
        "identity_missing" -> if (sessionIdentityAvailable) SyncStatus.FORMAT_CHANGED else SyncStatus.REAUTH_REQUIRED
        "owner_context_missing" -> if (sessionIdentityAvailable) SyncStatus.FORMAT_CHANGED else SyncStatus.CHECK_REQUIRED
        "own_profile_required", "check_required", "page_required", "page_identity_missing", "page_mismatch" -> SyncStatus.CHECK_REQUIRED
        else -> SyncStatus.FORMAT_CHANGED
    }
    val retry = if (result.isNull("retryAfterSeconds")) null else result.optLong("retryAfterSeconds").coerceIn(60, 86_400)
    return CollectionFailure(status, retry)
}
