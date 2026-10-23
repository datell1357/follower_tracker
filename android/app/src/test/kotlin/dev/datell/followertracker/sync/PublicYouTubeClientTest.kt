package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PublicYouTubeClientTest {
    @Test fun anonymousRequestsNeverSendOrPersistSessionCookiesAndKeepServerCooldowns() = runBlocking {
        var cookieReads = 0; var cookieWrites = 0; var requests = 0
        val cookies = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> { cookieReads++; return emptyList() }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieWrites++ }
        }
        val client = PublicYouTubeClient(OkHttpClient.Builder().cookieJar(cookies).addInterceptor { chain ->
            requests++; assertNull(chain.request().header("Cookie")); assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "123").header("Set-Cookie", "synthetic=must-not-be-saved; Secure")
                .body("limited".toResponseBody("text/html".toMediaType())).build()
        }.build())
        try { client.read("https://www.youtube.com/@fixture"); fail() }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.RATE_LIMITED, failure.status); assertEquals(123L, failure.retryAfterSeconds) }
        assertEquals(1, requests); assertEquals(0, cookieReads); assertEquals(0, cookieWrites)
    }
    @Test fun authenticationAndForeignRedirectsStopBeforeAnySecondRequest() = runBlocking {
        for (destination in listOf("https://accounts.google.com/ServiceLogin", "https://youtube.com.evil.test/@fixture")) {
            var requests = 0
            val client = PublicYouTubeClient(OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", destination).body("".toResponseBody()).build()
            }.build())
            try { client.read("https://www.youtube.com/@fixture"); fail() }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
            assertEquals(1, requests)
        }
    }
}
