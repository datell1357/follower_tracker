package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import dev.datell.followertracker.ui.canInspectFacebookPageRedirect
import dev.datell.followertracker.ui.facebookPageDesktopUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/** Anonymous HTML only. This client never accesses or changes the user's Facebook login. */
class PublicFacebookPageDocument(transport: OkHttpClient = OkHttpClient()) {
    data class Document(val url: String, val head: String)
    private val client = transport.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun read(address: String): Document = withTimeoutOrNull(25_000) {
        val target = facebookPageDesktopUrl(address) ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        var url = target.toHttpUrl()
        repeat(3) { index ->
            currentCoroutineContext().ensureActive()
            val (body, redirect) = document(url)
            if (redirect == null) {
                val end = body.indexOf("</head>", ignoreCase = true)
                if (end < 0 || end > 256 * 1024) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                return@withTimeoutOrNull Document(url.toString(), body.substring(0, end + 7))
            }
            val next = url.resolve(redirect)?.toString()?.let(::facebookPageDesktopUrl)
                ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            if (index == 2 || !canInspectFacebookPageRedirect(target, next)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            url = next.toHttpUrl()
        }
        throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
    } ?: throw CollectionFailure(SyncStatus.OFFLINE)

    private suspend fun document(url: HttpUrl): Pair<String, String?> {
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        val request = Request.Builder().url(url).get().header("User-Agent", "FollowerTracker/0.1 public-page")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8").header("Accept", "text/html")
            .header("Cache-Control", "no-cache").build()
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
                            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.HTTP_RESPONSE, Provider.FACEBOOK, AccountType.PAGE,
                                attempt = attempt, httpStatus = it.code.takeIf { value -> value in 100..599 }))
                            if (it.code in 300..399) {
                                val location = it.header("Location") ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                                if (continuation.isActive) continuation.resume("" to location)
                                return
                            }
                            statusForHttp(it.code)?.let { status -> throw CollectionFailure(
                                if (status == SyncStatus.REAUTH_REQUIRED) SyncStatus.CHECK_REQUIRED else status,
                                it.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400)) }
                            val body = it.body ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            if (body.contentLength() > LIMIT) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            val buffer = Buffer(); val source = body.source()
                            while (!source.exhausted()) {
                                source.read(buffer, 8192)
                                if (buffer.size > LIMIT) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            }
                            if (continuation.isActive) continuation.resume(buffer.readUtf8() to null)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (failure is CollectionFailure) failure else CollectionFailure(SyncStatus.OFFLINE))
                        }
                    }
                }
            })
        }
    }
    private companion object { const val LIMIT = 4L * 1024 * 1024 }
}
