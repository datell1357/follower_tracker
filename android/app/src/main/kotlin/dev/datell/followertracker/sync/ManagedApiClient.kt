package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class ServiceConnectionFailure(val code: String, val retryAfterSeconds: Long? = null) : Exception(code) {
    fun collectionFailure() = CollectionFailure(when (code) {
        "RATE_LIMITED" -> SyncStatus.RATE_LIMITED
        "REAUTH_REQUIRED", "INSTALLATION_REQUIRED", "PERMISSION_REQUIRED" -> SyncStatus.REAUTH_REQUIRED
        "FORMAT_CHANGED" -> SyncStatus.FORMAT_CHANGED
        "CHECK_REQUIRED", "PAGE_NOT_ALLOWED" -> SyncStatus.CHECK_REQUIRED
        else -> SyncStatus.OFFLINE
    }, retryAfterSeconds)
}
data class FacebookPageChoice(val id: String, val name: String)
data class FacebookLoginStatus(val status: String, val pages: List<FacebookPageChoice>, val error: String?, val selectedId: String? = null)

class ManagedApiClient(baseUrl: String, private val store: ManagedApiStore,
    transport: OkHttpClient = OkHttpClient(), private val now: () -> Long = System::currentTimeMillis) {
    private val base = baseUrl.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() &&
        it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null }
    val configured: Boolean get() = base != null
    private val origin get() = base?.toString()?.trimEnd('/') ?: throw ServiceConnectionFailure("SERVICE_NOT_CONFIGURED")
    private val setup = Mutex()
    private val cleanup = Mutex()
    private val client = transport.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun youtube(address: String, expected: Account?, reconnect: Boolean = true): Pair<Account, MetricSnapshot> {
        val target = YouTubePublicChannel.target(address) ?: throw ServiceConnectionFailure("CHECK_REQUIRED")
        val root = request("youtube", buildJsonObject { put("address", address) }, reconnect)
        val (payload, timestamp) = observation(root)
        return OfficialApiResponse.youtube(payload, target, expected?.copy(loggedOut = false), timestamp)
    }
    suspend fun read(account: Account): Pair<Account, MetricSnapshot> {
        if (account.loggedOut || !store.owns(account)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        try {
            return when {
                account.provider == Provider.YOUTUBE -> youtube(account.profileUrl, account, reconnect = false)
                account.provider == Provider.FACEBOOK && account.accountType == AccountType.PAGE -> {
                    val (payload, time) = observation(request("facebook/count", buildJsonObject { put("pageId", account.stableId) }))
                    OfficialApiResponse.facebookPage(payload, account, time)
                }
                else -> throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            }
        } catch (failure: ServiceConnectionFailure) { throw failure.collectionFailure() }
    }
    suspend fun startFacebook(expected: Account?): String {
        try { installation(); flushRevocations() } catch (failure: ServiceConnectionFailure) {
            if (failure.code != "INSTALLATION_REQUIRED") throw failure
            withContext(Dispatchers.IO) { store.forgetInstallation() }
        }
        val root = request("facebook/login", buildJsonObject { expected?.let { put("expectedId", it.stableId) } }, reconnect = true)
        val id = text(root, "flowId").takeIf { opaque(it) } ?: malformed()
        val loginUrl = text(root, "loginUrl").toHttpUrlOrNull() ?: malformed()
        if (loginUrl.scheme != base?.scheme || loginUrl.host != base.host || loginUrl.port != base.port ||
            loginUrl.username.isNotEmpty() || loginUrl.password.isNotEmpty() || loginUrl.encodedPath != "/oauth/facebook/start" ||
            loginUrl.queryParameter("flow") != id || loginUrl.queryParameterNames != setOf("flow") || loginUrl.fragment != null) malformed()
        withContext(NonCancellable + Dispatchers.IO) { store.pending(PendingFacebookLogin(id, expected?.key)) }
        return loginUrl.toString()
    }
    suspend fun facebookStatus(pending: PendingFacebookLogin): FacebookLoginStatus {
        val root = request("facebook/status", buildJsonObject { put("flowId", pending.id) })
        val status = text(root, "status")
        if (status !in setOf("waiting", "processing", "ready", "selecting", "selected", "cancelled", "failed")) malformed()
        val pages = (root["pages"] as? JsonArray ?: malformed()).map { element ->
            val page = element as? JsonObject ?: malformed()
            val id = text(page, "id").takeIf { it.matches(Regex("[0-9]{1,32}")) } ?: malformed()
            val name = text(page, "name").takeIf { it.isNotBlank() && it.length <= 256 } ?: malformed()
            FacebookPageChoice(id, name)
        }
        if (pages.size > 1000 || pages.map { it.id }.distinct().size != pages.size) malformed()
        val selectedId = (root["selectedId"] as? JsonPrimitive)?.contentOrNull
        if (status == "selected" && selectedId?.matches(Regex("[0-9]{1,32}")) != true) malformed()
        return FacebookLoginStatus(status, pages, (root["error"] as? JsonPrimitive)?.contentOrNull, selectedId)
    }
    suspend fun selectPage(pending: PendingFacebookLogin, id: String, expected: Account?): Pair<Account, MetricSnapshot> {
        if (!id.matches(Regex("[0-9]{1,32}"))) malformed()
        val (payload, time) = observation(request("facebook/select", buildJsonObject {
            put("flowId", pending.id); put("pageId", id)
        }))
        val observation = OfficialApiResponse.facebookPage(payload, expected?.copy(loggedOut = false), time)
        if (observation.first.stableId != id) malformed()
        return observation
    }
    suspend fun flushRevocations(): Unit = cleanup.withLock {
        if (store.state().installationToken.isEmpty()) return@withLock
        for (id in store.state().revocations) {
            request("facebook/disconnect", buildJsonObject { if (id != "*") put("pageId", id) })
            withContext(Dispatchers.IO) { store.acknowledgeRevocation(id) }
        }
        for (id in store.state().cancelledFlows) {
            request("facebook/cancel", buildJsonObject { put("flowId", id) })
            withContext(Dispatchers.IO) { store.acknowledgeCancel(id) }
        }
    }
    private suspend fun installation(): String = setup.withLock {
        val state = withContext(Dispatchers.IO) { store.state() }
        if (state.installationToken.isNotEmpty()) {
            if (state.origin != origin) throw ServiceConnectionFailure("SERVICE_CHANGED")
            return@withLock state.installationToken
        }
        val token = text(post("installations", buildJsonObject {}, null), "installationToken")
        if (!opaque(token)) malformed()
        withContext(NonCancellable + Dispatchers.IO) { store.installation(origin, token) }
        token
    }
    private suspend fun request(path: String, body: JsonObject, reconnect: Boolean = false): JsonObject {
        val token = installation()
        try { return post(path, body, token) }
        catch (failure: ServiceConnectionFailure) {
            if (!reconnect || failure.code != "INSTALLATION_REQUIRED") throw failure
            setup.withLock {
                if (store.state().installationToken == token) withContext(Dispatchers.IO) { store.forgetInstallation() }
            }
            return post(path, body, installation())
        }
    }
    private suspend fun post(path: String, body: JsonObject, token: String?): JsonObject {
        val url = base?.newBuilder()?.encodedPath("/v1/$path")?.build()
            ?: throw ServiceConnectionFailure("SERVICE_NOT_CONFIGURED")
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) request.header("Authorization", "Bearer $token")
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        val provider = when {
            path.startsWith("youtube") -> Provider.YOUTUBE
            path.startsWith("facebook") -> Provider.FACEBOOK
            else -> null
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ServiceConnectionFailure("OFFLINE"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            provider?.let { provider -> DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.HTTP_RESPONSE,
                                provider, attempt = attempt, stage = DiagnosticStage.OFFICIAL_API, httpStatus = response.code)) }
                            if (it.code in 300..399) throw ServiceConnectionFailure("SERVICE_UNAVAILABLE")
                            val source = it.body?.source() ?: malformed()
                            val buffer = Buffer()
                            while (!source.exhausted()) {
                                source.read(buffer, 8192)
                                if (buffer.size > if (path == "facebook/status") 2_097_152 else 65_536) malformed()
                            }
                            val root = Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject ?: malformed()
                            if (!it.isSuccessful || root.containsKey("error")) {
                                val error = root["error"] as? JsonObject
                                val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: "SERVICE_UNAVAILABLE"
                                val retry = (error?.get("retryAfterSeconds") as? JsonPrimitive)?.longOrNull?.takeIf { seconds -> seconds > 0 }
                                throw ServiceConnectionFailure(code, retry)
                            }
                            if (continuation.isActive) continuation.resume(root)
                        } catch (error: Exception) {
                            val failure = if (error is ServiceConnectionFailure && error.code != "FORMAT_CHANGED") error else when {
                                it.code == 429 -> ServiceConnectionFailure("RATE_LIMITED", it.header("Retry-After")?.toLongOrNull()?.takeIf { seconds -> seconds > 0 } ?: 900)
                                it.code >= 500 -> ServiceConnectionFailure("SERVICE_UNAVAILABLE")
                                it.code == 401 -> ServiceConnectionFailure("INSTALLATION_REQUIRED")
                                else -> ServiceConnectionFailure("FORMAT_CHANGED")
                            }
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }
            })
        }
    }
    private fun observation(root: JsonObject): Pair<String, Long> {
        val timestamp = (root["observedAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && it <= now() + 30000 } ?: malformed()
        return (root["payload"] as? JsonObject ?: malformed()).toString() to timestamp
    }
    private fun text(root: JsonObject, key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    private fun opaque(value: String) = value.matches(Regex("[A-Za-z0-9_-]{43}"))
    private fun malformed(): Nothing = throw ServiceConnectionFailure("FORMAT_CHANGED")
}
