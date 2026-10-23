package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PublicFacebookPageDocumentTest {
    @Test fun anonymousRequestsNeverReadOrPersistCookiesAndHonorCooldowns() = runBlocking {
        var reads = 0; var writes = 0; var requests = 0
        val cookies = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> { reads++; return emptyList() }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { writes++ }
        }
        val client = PublicFacebookPageDocument(OkHttpClient.Builder().cookieJar(cookies).addInterceptor { chain ->
            requests++; assertNull(chain.request().header("Cookie")); assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "123").header("Set-Cookie", "synthetic=not-saved; Secure")
                .body("limited".toResponseBody("text/html".toMediaType())).build()
        }.build())
        try { client.read("https://www.facebook.com/fixture.page"); fail() }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.RATE_LIMITED, failure.status); assertEquals(123L, failure.retryAfterSeconds) }
        assertEquals(1, requests); assertEquals(0, reads); assertEquals(0, writes)
    }
    @Test fun unsafeInputsAndForeignLoginOrDifferentPageRedirectsNeverReachTheirDestinations() = runBlocking {
        for (destination in listOf("https://www.facebook.com/login/", "https://facebook.com.evil.test/fixture.page", "https://www.facebook.com/another.page")) {
            var requests = 0
            val client = PublicFacebookPageDocument(OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", destination).body("".toResponseBody()).build()
            }.build())
            try { client.read("https://www.facebook.com/fixture.page"); fail() }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
            assertEquals(1, requests)
        }
    }
    @Test fun onlyABoundedHeadIsPassedToTheInertParser() = runBlocking {
        val head = "<!doctype html><html><head><meta property=\"og:title\" content=\"Fixture Page\"></head>"
        val client = PublicFacebookPageDocument(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((head + "<body><script>mustNotExecute()</script></body></html>").toResponseBody()).build()
        }.build())
        assertEquals(head, client.read("https://www.facebook.com/fixture.page").head)
        for (body in listOf("<html>missing head</html>", "<head>" + "x".repeat(256 * 1024) + "</head>", "x".repeat(4 * 1024 * 1024 + 1))) {
            val oversized = PublicFacebookPageDocument(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }.build())
            try { oversized.read("https://www.facebook.com/fixture.page"); fail() }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.FORMAT_CHANGED, failure.status) }
        }
    }
}
