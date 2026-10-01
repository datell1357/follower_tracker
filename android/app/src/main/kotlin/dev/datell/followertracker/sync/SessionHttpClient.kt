package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer

class SessionHttpClient(private val sessions: SessionAccess, private val transport: OkHttpClient = OkHttpClient()) {
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(35, TimeUnit.SECONDS).build()

    suspend fun read(provider: Provider, url: HttpUrl, userAgent: String): String {
        if (!provider.allows(url.toString())) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (!sessions.hasAuthentication(provider)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        val cookie = sessions.header(provider, url.toString())
        val request = Request.Builder().url(url).get().header("Cookie", cookie)
            .header("User-Agent", userAgent).header("Accept", "application/json,text/html")
        if (provider == Provider.INSTAGRAM) {
            // Public first-party web client identifier, not a user/developer API credential.
            request.header("X-IG-App-ID", "936619743392459").header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://www.instagram.com").header("Referer", "https://www.instagram.com/")
            cookie.split(';').firstOrNull { it.trim().startsWith("csrftoken=") }
                ?.substringAfter('=')?.let { request.header("X-CSRFToken", it) }
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(CollectionFailure(SyncStatus.OFFLINE))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val location = it.header("Location").orEmpty()
                            if (it.code in 300..399) throw CollectionFailure(
                                if (location.contains("login", true)) SyncStatus.REAUTH_REQUIRED else SyncStatus.CHECK_REQUIRED)
                            statusForHttp(it.code)?.let { status ->
                                throw CollectionFailure(status, it.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400))
                            }
                            val body = it.body ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            if (body.contentLength() > 8 * 1024 * 1024) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            val buffer = Buffer()
                            val source = body.source()
                            while (!source.exhausted()) {
                                source.read(buffer, 8192)
                                if (buffer.size > 8 * 1024 * 1024) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
                            }
                            if (continuation.isActive) sessions.acceptResponseCookies(provider, url.toString(), it.headers("Set-Cookie"), cookie)
                            if (continuation.isActive) continuation.resume(buffer.readUtf8())
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (failure is CollectionFailure) failure else CollectionFailure(SyncStatus.OFFLINE))
                        }
                    }
                }
            })
        }
    }
}
