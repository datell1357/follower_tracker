package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/** Small, cookie-free JSON requests. Credentials never enter a browser or a redirect destination. */
class OfficialApiClient(transport: OkHttpClient = OkHttpClient(),
    private val androidIdentity: (() -> Pair<String, String>)? = null,
    private val now: () -> Long = System::currentTimeMillis) {
    private val client = transport.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun youtube(address: String, apiKey: String, expected: Account? = null): Pair<Account, MetricSnapshot> {
        if (!apiKey.matches(Regex("[A-Za-z0-9_-]{10,256}"))) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val target = YouTubePublicChannel.target(address) ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val url = "https://www.googleapis.com/youtube/v3/channels".toHttpUrl().newBuilder()
            .addQueryParameter("part", if (expected == null) "snippet,statistics" else "statistics")
            .addQueryParameter("fields", if (expected == null) "items(id,snippet/title,statistics(subscriberCount,hiddenSubscriberCount))"
                else "items(id,statistics(subscriberCount,hiddenSubscriberCount))")
            .addQueryParameter(if (target.id != null) "id" else "forHandle", target.id ?: target.handle)
            .addQueryParameter("key", apiKey).build()
        val request = request(url)
        androidIdentity?.invoke()?.let { (packageName, fingerprint) ->
            request.header("X-Android-Package", packageName).header("X-Android-Cert", fingerprint)
        }
        return OfficialApiResponse.youtube(read(Provider.YOUTUBE, request.build()), target, expected, now())
    }

    suspend fun facebookPage(pageToken: String, expected: Account? = null, connecting: Boolean = false): Pair<Account, MetricSnapshot> {
        if (pageToken.length !in 10..8192 || pageToken.any { it.code !in 33..126 } || '|' in pageToken)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (expected != null && (expected.provider != Provider.FACEBOOK || expected.accountType != AccountType.PAGE || expected.loggedOut))
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        // Enrollment uses /me to prove the token belongs to this Page, not to a person or another Page.
        val target = if (connecting || expected == null) "me" else expected.stableId
        val url = "https://graph.facebook.com/v26.0/".toHttpUrl().newBuilder().addPathSegment(target)
            .addQueryParameter("fields", "id,name,followers_count").build()
        return OfficialApiResponse.facebookPage(read(Provider.FACEBOOK,
            request(url).header("Authorization", "Bearer $pageToken").build()), expected, now())
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).get()
        .header("Accept", "application/json").header("Cache-Control", "no-cache")
        .header("User-Agent", "FollowerTracker/0.1 official-api")

    private suspend fun read(provider: Provider, request: Request): String {
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(CollectionFailure(SyncStatus.OFFLINE))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            // Never log URLs, response text, error messages or authentication headers.
                            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.HTTP_RESPONSE, provider, attempt = attempt,
                                stage = DiagnosticStage.OFFICIAL_API, httpStatus = it.code))
                            if (it.code in 300..399) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                            if (it.code == 401 || it.code == 429 || it.code >= 500)
                                throw checkNotNull(OfficialApiResponse.failure(provider, it.code, "", it.header("Retry-After"), now()))
                            val body = it.body ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            if (body.contentLength() > LIMIT) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            val buffer = Buffer(); val source = body.source()
                            while (!source.exhausted()) {
                                source.read(buffer, 8192)
                                if (buffer.size > LIMIT) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            }
                            val payload = buffer.readUtf8()
                            OfficialApiResponse.failure(provider, it.code, payload, it.header("Retry-After"), now())?.let { failure -> throw failure }
                            if (continuation.isActive) continuation.resume(payload)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (failure is CollectionFailure) failure else CollectionFailure(SyncStatus.OFFLINE))
                        }
                    }
                }
            })
        }
    }
    private companion object { const val LIMIT = 65_536L }
}
