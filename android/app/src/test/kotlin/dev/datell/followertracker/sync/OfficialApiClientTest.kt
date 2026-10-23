package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OfficialApiClientTest {
    private val id = "UC" + "a".repeat(22)
    private val channel = """{"items":[{"id":"$id","snippet":{"title":"Fixture"},"statistics":{"subscriberCount":"12300","hiddenSubscriberCount":false}}]}"""
    private val page = """{"id":"99","name":"Fixture Page","followers_count":12345}"""
    private fun reply(request: Request, body: String, status: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody()).build()

    @Test fun youtubeResolvesHandleThenUsesStableIdAndOnlyStatistics() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); reply(chain.request(), channel)
        }.build(), androidIdentity = { "dev.fixture" to "A".repeat(40) })
        val first = api.youtube("youtube.com/@fixture", "fixture-key-123").first
        api.youtube(first.profileUrl, "fixture-key-123", first)
        assertEquals(2, requests.size)
        val initial = requests[0]; val repeat = requests[1]
        assertEquals("www.googleapis.com", initial.url.host)
        assertEquals("/youtube/v3/channels", initial.url.encodedPath)
        assertEquals("fixture", initial.url.queryParameter("forHandle"))
        assertEquals("snippet,statistics", initial.url.queryParameter("part"))
        assertEquals("fixture-key-123", initial.url.queryParameter("key"))
        assertEquals("dev.fixture", initial.header("X-Android-Package")); assertEquals("A".repeat(40), initial.header("X-Android-Cert"))
        assertEquals(id, repeat.url.queryParameter("id")); assertNull(repeat.url.queryParameter("forHandle"))
        assertEquals("statistics", repeat.url.queryParameter("part"))
        assertEquals("items(id,statistics(subscriberCount,hiddenSubscriberCount))", repeat.url.queryParameter("fields"))
        assertNull(initial.header("Cookie")); assertNull(initial.header("Authorization"))
    }
    @Test fun pageTokenNeverEntersUrlAndEnrollmentChecksTheTokenPrincipal() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); reply(chain.request(), page)
        }.build())
        val account = api.facebookPage("fixture-page-token", connecting = true).first
        api.facebookPage("fixture-page-token", account)
        api.facebookPage("fixture-page-token", account, connecting = true)
        assertEquals(listOf("/v26.0/me", "/v26.0/99", "/v26.0/me"), requests.map { it.url.encodedPath })
        requests.forEach {
            assertEquals("graph.facebook.com", it.url.host)
            assertEquals("id,name,followers_count", it.url.queryParameter("fields"))
            assertNull(it.url.queryParameter("access_token")); assertFalse(it.url.toString().contains("fixture-page-token"))
            assertEquals("Bearer fixture-page-token", it.header("Authorization")); assertNull(it.header("Cookie"))
        }
    }
    @Test fun neitherApiReadsCookiesNorFollowsAnAuthenticationRedirect() = runBlocking {
        var cookies = 0; var requests = 0
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> { cookies++; return emptyList() }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { throw AssertionError("Cookie persistence") }
        }
        val api = OfficialApiClient(OkHttpClient.Builder().cookieJar(jar).addInterceptor { chain ->
            requests++; reply(chain.request(), "", 302).newBuilder()
                .header("Location", "https://elsewhere.invalid/steal").header("Set-Cookie", "fixture=value").build()
        }.build())
        for (provider in listOf(Provider.YOUTUBE, Provider.FACEBOOK)) {
            try {
                if (provider == Provider.YOUTUBE) api.youtube("youtube.com/@fixture", "fixture-key-123")
                else api.facebookPage("fixture-page-token")
                fail()
            } catch (e: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, e.status) }
        }
        assertEquals(2, requests); assertEquals(0, cookies)
    }
    @Test fun quotaOrExpiredTokenDoesNotTriggerAnotherRequest() = runBlocking {
        var requests = 0
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val facebook = chain.request().url.host == "graph.facebook.com"
            reply(chain.request(), if (facebook) """{"error":{"code":190}}"""
                else """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""", if (facebook) 400 else 403)
        }.build())
        try { api.youtube("youtube.com/@fixture", "fixture-key-123"); fail() }
        catch (e: CollectionFailure) { assertEquals(SyncStatus.RATE_LIMITED, e.status); assertTrue(e.retryAfterSeconds!! > 0) }
        try { api.facebookPage("fixture-page-token"); fail() }
        catch (e: CollectionFailure) { assertEquals(SyncStatus.REAUTH_REQUIRED, e.status) }
        assertEquals(2, requests)
    }
    @Test fun malformedOversizedAndHiddenResponsesNeverCreateObservations() = runBlocking {
        for (body in listOf("<html>login</html>", "x".repeat(65_537), channel.replace("false", "true"))) {
            val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request(), body) }.build())
            try { api.youtube("youtube.com/@fixture", "fixture-key-123"); fail() }
            catch (e: CollectionFailure) { assertTrue(e.status in setOf(SyncStatus.FORMAT_CHANGED, SyncStatus.CHECK_REQUIRED)) }
        }
    }
    @Test fun malformedCredentialsAndForeignTargetsAreRejectedBeforeNetwork() = runBlocking {
        var requests = 0
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain -> requests++; reply(chain.request(), page) }.build())
        for (token in listOf("", "short", "token\r\nHeader: value", "app-id|app-secret")) {
            try { api.facebookPage(token); fail() } catch (e: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, e.status) }
        }
        try { api.youtube("https://youtube.com.evil.test/@fixture", "fixture-key-123"); fail() }
        catch (e: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, e.status) }
        assertEquals(0, requests)
    }
    @Test fun retryAfterSurvivesEvenAnOversizedNonJsonRateLimitResponse() = runBlocking {
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            reply(chain.request(), "x".repeat(70_000), 429).newBuilder().header("Retry-After", "321").build()
        }.build())
        try { api.facebookPage("fixture-page-token"); fail() }
        catch (e: CollectionFailure) { assertEquals(SyncStatus.RATE_LIMITED, e.status); assertEquals(321L, e.retryAfterSeconds) }
    }
    @Test fun cancelledRequestsCannotReturnALateObservation() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var delivered = false
        val api = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); reply(chain.request(), page)
        }.build())
        val job = launch(Dispatchers.Default) { api.facebookPage("fixture-page-token"); delivered = true }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancelAndJoin() }
        finally { release.countDown() }
        assertFalse(delivered)
    }
}
