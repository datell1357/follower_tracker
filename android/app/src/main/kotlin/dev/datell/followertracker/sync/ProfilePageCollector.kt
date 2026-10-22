package dev.datell.followertracker.sync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.util.Log
import android.webkit.*
import dev.datell.followertracker.BuildConfig
import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import dev.datell.followertracker.ui.captureWebSession
import dev.datell.followertracker.ui.facebookCollectionFallback
import dev.datell.followertracker.ui.facebookPageDesktopUrl
import dev.datell.followertracker.ui.facebookPageUserAgent
import dev.datell.followertracker.ui.isOptionalTikTokAppLink
import dev.datell.followertracker.ui.installProfileResponseObserver
import dev.datell.followertracker.ui.webCaptureFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A fresh, bounded official profile page. Never reads login fields or exports cookies. */
class ProfilePageCollector(private val context: Context, private val sessions: SessionStore,
    private val createWebView: (Context) -> WebView = { WebView(it) }) {
    private data class Binding(val provider: Provider, val accountKey: String, val connectedAt: Long,
        val sessionVersion: Long, val userAgent: String, val profileUrl: String)
    private data class IdleBrowser(val binding: Binding, val web: WebView)
    private val idle = mutableMapOf<Provider, IdleBrowser>()
    private val mutex = Mutex()
    private var generation = 0L
    private var active: Pair<Provider, Job>? = null
    private val script by lazy { context.assets.open("web-session-capture.js").bufferedReader().use { it.readText() } }

    /** Called on Main when tracking stops, a session changes, or an account disconnects. */
    fun release(provider: Provider? = null) {
        generation++
        val keys = idle.keys.filter { provider == null || provider == it }
        for (key in keys) idle.remove(key)?.web?.destroy()
    }
    suspend fun cancelSession(provider: Provider) = withContext(Dispatchers.Main.immediate) {
        active?.takeIf { it.first == provider }?.second?.cancelAndJoin()
        release(provider)
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(account: Account, userAgent: String, retain: Boolean = false): String = mutex.withLock {
      withContext(Dispatchers.Main.immediate) {
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        fun rejectSession(stage: DiagnosticStage, status: SyncStatus): Nothing {
            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.PROFILE_FAILURE, account.provider, account.accountType,
                attempt = attempt, stage = stage, status = status))
            throw CollectionFailure(status)
        }
        if (!account.provider.allows(account.profileUrl))
            rejectSession(DiagnosticStage.START_ORIGIN, SyncStatus.FOREGROUND_ONLY)
        if (!sessions.hasAuthentication(account.provider)) rejectSession(DiagnosticStage.SESSION_AUTH, SyncStatus.REAUTH_REQUIRED)
        if (sessions.identity(account.provider) != account.authenticationOwnerId) rejectSession(DiagnosticStage.SESSION_OWNER, SyncStatus.REAUTH_REQUIRED)
        val facebookPage = account.accountType == AccountType.PAGE
        // Use one fresh Page document per cycle, with the same representation as manual confirmation.
        // Its URL and captured Page ID remain bound to the user's original explicit selection.
        val profileUrl = if (facebookPage) facebookPageDesktopUrl(account.profileUrl)
            ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED) else account.profileUrl
        val collectionAgent = if (facebookPage) facebookPageUserAgent(userAgent) ?: userAgent else userAgent
        val loaded = CompletableDeferred<Unit>()
        var failure: CollectionFailure? = null
        var pendingCaptureFailure: CollectionFailure? = null
        var pendingCaptureError: DiagnosticCaptureError? = null
        val binding = Binding(account.provider, account.key, account.connectedAt,
            sessions.metadata(account.provider)?.savedAt ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED),
            collectionAgent, profileUrl)
        val previous = idle.remove(account.provider)
        if (BuildConfig.DEBUG) Log.d("FollowerCollection", "provider=${account.provider.name} " +
            "profileBrowser=${if (previous?.binding == binding) "reused" else "created"}")
        DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.PROFILE_BROWSER, account.provider, account.accountType,
            attempt = attempt, stage = if (previous?.binding == binding) DiagnosticStage.REUSED else DiagnosticStage.CREATED))
        val web = if (previous?.binding == binding) previous.web else {
            previous?.web?.destroy()
            createWebView(context).also { installProfileResponseObserver(it, account.provider) }
        }
        val acquiredGeneration = generation
        val facebookFallbacks = mutableSetOf<String>()
        var succeeded = false
        fun fail(error: CollectionFailure, stage: String) {
            if (failure != null) return
            if (BuildConfig.DEBUG) Log.d("FollowerCollection", "provider=${account.provider.name} profileFailure=$stage status=${error.status.name}")
            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.PROFILE_FAILURE, account.provider, account.accountType,
                attempt = attempt, stage = DiagnosticStage.profile(stage), status = error.status,
                httpStatus = stage.removePrefix("HTTP_").toIntOrNull()?.takeIf { it in 100..599 }, captureError = pendingCaptureError))
            failure = error
            loaded.completeExceptionally(error)
            web.stopLoading()
        }
        val operation = currentCoroutineContext()[Job]!!
        active = account.provider to operation
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = collectionAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                // Revalidate the document below; immutable scripts/fonts can use the HTTP cache.
                cacheMode = WebSettings.LOAD_DEFAULT
                blockNetworkImage = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setSupportMultipleWindows(false)
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    val path = runCatching { java.net.URI(url.orEmpty()).path.orEmpty() }.getOrDefault("")
                    if (!account.provider.allows(url.orEmpty())) fail(CollectionFailure(SyncStatus.CHECK_REQUIRED),
                        if (url == "about:blank") "START_BLANK" else "START_ORIGIN")
                    else if (AUTH_PATH.containsMatchIn(path))
                        fail(CollectionFailure(if (path.contains("login", true)) SyncStatus.REAUTH_REQUIRED else SyncStatus.CHECK_REQUIRED), "START_AUTH")
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    val path = request.url.path.orEmpty()
                    if (isOptionalTikTokAppLink(account.provider, view.url.orEmpty(), url)) return true
                    val fallback = facebookCollectionFallback(account.provider, view.url.orEmpty(), url, account.stableId)
                    if (fallback != null) {
                        if (fallback in facebookFallbacks || facebookFallbacks.size >= 2)
                            fail(CollectionFailure(SyncStatus.CHECK_REQUIRED), "FALLBACK_LOOP")
                        else {
                            facebookFallbacks.add(fallback)
                            view.loadUrl(fallback, mapOf("Cache-Control" to "no-cache"))
                        }
                        return true
                    }
                    if (!account.provider.allows(url)) { fail(CollectionFailure(SyncStatus.CHECK_REQUIRED), "NAVIGATION_ORIGIN"); return true }
                    if (AUTH_PATH.containsMatchIn(path)) {
                        fail(CollectionFailure(if (path.contains("login", true)) SyncStatus.REAUTH_REQUIRED else SyncStatus.CHECK_REQUIRED), "NAVIGATION_AUTH")
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (failure != null) return
                    if (!account.provider.allows(url.orEmpty())) fail(CollectionFailure(SyncStatus.CHECK_REQUIRED),
                        if (url == "about:blank") "FINISH_BLANK" else "FINISH_ORIGIN")
                    else loaded.complete(Unit)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    // A profile's own JSON requests can be limited even when its document was HTTP 200.
                    if (request.isForMainFrame || response.statusCode == 429 && account.provider.allows(request.url.toString()))
                        fail(CollectionFailure(statusForHttp(response.statusCode) ?: SyncStatus.FORMAT_CHANGED,
                            response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After", true) }
                                ?.value?.toLongOrNull()?.coerceIn(60, 86_400)), "HTTP_${response.statusCode}")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(CollectionFailure(SyncStatus.OFFLINE), "NETWORK")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); fail(CollectionFailure(SyncStatus.CHECK_REQUIRED), "SSL")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail(CollectionFailure(SyncStatus.OFFLINE), "RENDERER"); return true
                }
            }
            val metrics = context.resources.displayMetrics
            web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            web.onResume()
            web.loadUrl(profileUrl, mapOf("Cache-Control" to "no-cache"))
            withTimeout(35_000) { loaded.await() }
            val payload = withTimeout(15_000) {
                while (true) {
                    failure?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    if (!sessions.hasAuthentication(account.provider)) rejectSession(DiagnosticStage.SESSION_AUTH, SyncStatus.REAUTH_REQUIRED)
                    if (sessions.identity(account.provider) != account.authenticationOwnerId) rejectSession(DiagnosticStage.SESSION_OWNER, SyncStatus.CHECK_REQUIRED)
                    val payload = captureWebSession(web, account.provider, account.authenticationOwnerId, script,
                        allowRequest = false, facebookPage = facebookPage, expectedPageId = account.stableId.takeIf { facebookPage })
                    failure?.let { throw it }
                    val result = JSONObject(payload)
                    if (!result.has("error")) return@withTimeout payload
                    pendingCaptureError = DiagnosticCaptureError.from(result.optString("error"))
                    val retryable = result.optString("error") in setOf("exact_count_missing", "own_profile_required",
                        "owner_context_missing", "identity_missing", "document_changed") ||
                        facebookPage && result.optString("error") in setOf("page_required", "page_identity_missing", "rounded_count_only")
                    val error = if (facebookPage && retryable) CollectionFailure(SyncStatus.FORMAT_CHANGED)
                        else webCaptureFailure(result, sessionIdentityAvailable = true) ?: CollectionFailure(SyncStatus.FORMAT_CHANGED)
                    pendingCaptureFailure = error
                    // onPageFinished can precede client-side routing/profile hydration. Re-read the fresh
                    // document locally; never load alternate endpoints or turn missing data into zero.
                    if (!retryable) {
                        fail(error, "CAPTURE"); throw error
                    }
                    delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            succeeded = true
            payload
        } catch (_: TimeoutCancellationException) {
            // A caller's timeout/stop must remain cancellation, not a new collection failure.
            currentCoroutineContext().ensureActive()
            val error = failure ?: pendingCaptureFailure ?: CollectionFailure(if (loaded.isCompleted) SyncStatus.FORMAT_CHANGED else SyncStatus.OFFLINE)
            if (failure == null) DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.PROFILE_FAILURE,
                account.provider, account.accountType, attempt = attempt, status = error.status,
                stage = if (loaded.isCompleted) DiagnosticStage.CAPTURE_TIMEOUT else DiagnosticStage.LOAD_TIMEOUT,
                captureError = pendingCaptureError))
            throw error
        } finally {
            if (active?.second === operation) active = null
            web.stopLoading()
            val keep = retain && succeeded && currentCoroutineContext().isActive && generation == acquiredGeneration &&
                runCatching { sessions.metadata(account.provider)?.savedAt == binding.sessionVersion &&
                    sessions.identity(account.provider) == account.authenticationOwnerId }.getOrDefault(false)
            if (keep) {
                // Keep the instance, never an active SNS page or an old count. No global pauseTimers().
                val blank = CompletableDeferred<Boolean>()
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url == "about:blank") blank.complete(true)
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        blank.complete(false)
                        if (idle[account.provider]?.web === view) {
                            idle.remove(account.provider)
                            view.destroy()
                        }
                        return true
                    }
                }
                web.webChromeClient = WebChromeClient()
                web.settings.javaScriptEnabled = false
                web.loadUrl("about:blank")
                val cleared = withContext(NonCancellable) { withTimeoutOrNull(1_000) { blank.await() } } == true
                if (cleared && generation == acquiredGeneration) {
                    web.onPause()
                    idle[account.provider] = IdleBrowser(binding, web)
                } else web.destroy()
            } else web.destroy()
            CookieManager.getInstance().flush()
        }
      }
    }

    private companion object {
        val AUTH_PATH = Regex("(^|/)(login(?:\\.php)?|challenge|checkpoint|two_factor|two-factor|twofactor|two_step_verification|auth_platform)(/|$)", RegexOption.IGNORE_CASE)
    }
}
