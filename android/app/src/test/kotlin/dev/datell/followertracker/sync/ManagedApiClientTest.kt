package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ManagedApiClientTest {
    private val id = "UC" + "a".repeat(22)
    private val token = "t".repeat(43)
    private val flow = "f".repeat(43)
    private val now = 1_000_000L
    private fun store(): ManagedApiStore {
        var value: String? = null
        return ManagedApiStore({ value }, { value = it }, { it.reversedArray() }, { it.reversedArray() })
    }
    private fun channel() = """{"items":[{"id":"$id","snippet":{"title":"Fixture"},"statistics":{"subscriberCount":"12300","hiddenSubscriberCount":false}}]}"""
    private fun page(id: String = "99") = """{"id":"$id","name":"Fixture Page","followers_count":42}"""
    private fun observation(payload: String, time: Long = now) = """{"payload":$payload,"observedAt":$time}"""
    private fun client(store: ManagedApiStore, respond: (Request) -> Pair<Int, String>) =
        ManagedApiClient("https://service.example", store, OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build(), now = { now })
    private fun body(request: Request): JsonObject {
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }
    @Test fun linkOnlyRegistersOnceAndUsesTheServerTimestampForCachedObservations() = runBlocking {
        val store = store(); val calls = mutableListOf<Request>()
        val client = client(store) { request ->
            calls += request
            if (request.url.encodedPath.endsWith("installations")) 201 to """{"installationToken":"$token"}"""
            else {
                assertEquals("Bearer $token", request.header("Authorization"))
                assertEquals(setOf("address"), body(request).keys)
                200 to observation(channel(), now - 45000)
            }
        }
        val first = client.youtube("youtube.com/@fixture", null); store.bind(first.first)
        val next = client.read(first.first)
        assertEquals(3, calls.size); assertEquals(now - 45000, next.second.observedAt)
        assertEquals(Precision.ROUNDED, next.second.precision)
        assertEquals(CountTransport.OFFICIAL_API, first.first.countTransport)
        calls.forEach { assertNull(it.header("Cookie")); assertFalse(it.url.toString().contains("key=")) }
    }
    @Test fun facebookBrowserReturnRestoresPendingLoginAndSelectsWithoutReceivingAMetaToken() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val client = client(store) { request ->
            200 to when (request.url.encodedPath) {
                "/v1/facebook/login" -> """{"flowId":"$flow","loginUrl":"https://service.example/oauth/facebook/start?flow=$flow"}"""
                "/v1/facebook/status" -> """{"status":"ready","pages":[{"id":"99","name":"Fixture Page"}]}"""
                "/v1/facebook/select", "/v1/facebook/count" -> observation(page())
                else -> error("Unexpected request")
            }
        }
        assertTrue(client.startFacebook(null).startsWith("https://service.example/"))
        val pending = checkNotNull(store.pending())
        val status = client.facebookStatus(pending); assertEquals("99", status.pages.single().id)
        val (account, snapshot) = client.selectPage(pending, "99", null)
        store.bind(account); store.pending(null)
        assertEquals(42L, snapshot.followers)
        assertEquals(account.key, client.read(account).first.key)
        assertNull(store.pending()); assertEquals(token, store.state().installationToken)
    }
    @Test fun callbackLaunchUrlsMustRemainOnTheConfiguredOrigin() = runBlocking {
        for (url in listOf("https://evil.example/oauth/facebook/start?flow=$flow",
            "http://service.example/oauth/facebook/start?flow=$flow",
            "https://service.example/oauth/facebook/start?flow=$flow&token=unexpected",
            "https://service.example/other?flow=$flow")) {
            val store = store(); store.installation("https://service.example", token)
            try {
                client(store) { 200 to """{"flowId":"$flow","loginUrl":"$url"}""" }.startFacebook(null)
                fail("Untrusted login URL")
            } catch (failure: ServiceConnectionFailure) { assertEquals("FORMAT_CHANGED", failure.code) }
            assertNull(store.pending())
        }
    }
    @Test fun selectedPageMustMatchTheRequestedIdAndExistingAccount() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val pending = PendingFacebookLogin(flow)
        try {
            client(store) { 200 to observation(page("100")) }.selectPage(pending, "99", null)
            fail("Other Page must be rejected")
        } catch (failure: ServiceConnectionFailure) { assertEquals("FORMAT_CHANGED", failure.code) }
        assertTrue(store.state().keys.isEmpty())
    }
    @Test fun rateLimitsAndServiceConfigurationDoNotTriggerPublicPageFallbacks() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val account = OfficialApiResponse.facebookPage(page(), null, now).first; store.bind(account)
        var calls = 0
        try {
            client(store) { calls++; 429 to """{"error":{"code":"RATE_LIMITED","retryAfterSeconds":301}}""" }.read(account)
            fail()
        } catch (failure: CollectionFailure) {
            assertEquals(SyncStatus.RATE_LIMITED, failure.status); assertEquals(301L, failure.retryAfterSeconds)
        }
        assertEquals(1, calls)
        for (origin in listOf("", "http://service.example", "https://user:secret@service.example", "https://service.example/path")) {
            try { ManagedApiClient(origin, store).youtube("youtube.com/@fixture", null); fail() }
            catch (failure: ServiceConnectionFailure) { assertEquals("SERVICE_NOT_CONFIGURED", failure.code) }
        }
    }
    @Test fun logoutQueuesServerDeletionAndInvalidatesLateWritesEvenWhileOffline() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val account = OfficialApiResponse.facebookPage(page(), null, now).first
        store.bind(account); store.pending(PendingFacebookLogin(flow))
        val guard = store.guard()
        store.clear(Provider.FACEBOOK)
        assertFalse(guard()); assertFalse(store.owns(account)); assertNull(store.pending())
        try { client(store) { 503 to """{"error":{"code":"OFFLINE"}}""" }.flushRevocations(); fail() }
        catch (_: ServiceConnectionFailure) { }
        assertEquals(setOf("*"), store.state().revocations)
        val paths = mutableListOf<String>()
        client(store) { request -> paths += request.url.encodedPath; 200 to "{}" }.flushRevocations()
        assertEquals(listOf("/v1/facebook/disconnect", "/v1/facebook/cancel"), paths)
        assertTrue(store.state().revocations.isEmpty()); assertTrue(store.state().cancelledFlows.isEmpty())
    }
    @Test fun serviceChangeNeverSendsTheOldInstallationCredentialToAnotherHost() = runBlocking {
        val store = store(); store.installation("https://old.example", token)
        try { client(store) { error("Must not make a request") }.youtube("youtube.com/@fixture", null); fail() }
        catch (failure: ServiceConnectionFailure) { assertEquals("SERVICE_CHANGED", failure.code) }
    }
    @Test fun explicitReconnectRecoversAnUnknownInstallationWithoutAnInfiniteRetry() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val replacement = "r".repeat(43); var calls = 0
        val client = client(store) { request ->
            calls++
            if (request.url.encodedPath.endsWith("installations")) 201 to """{"installationToken":"$replacement"}"""
            else if (request.header("Authorization") == "Bearer $token") 401 to """{"error":{"code":"INSTALLATION_REQUIRED"}}"""
            else 200 to observation(channel())
        }
        assertEquals(id, client.youtube("youtube.com/@fixture", null).first.stableId)
        assertEquals(3, calls); assertEquals(replacement, store.state().installationToken)
    }
    @Test fun cancellingTheHttpRequestDoesNotReturnAnObservation() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val client = client(store) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); 200 to observation(channel()) }
        var delivered = false
        val job = launch(Dispatchers.Default) { client.youtube("youtube.com/@fixture", null); delivered = true }
        assertTrue(entered.await(3, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertFalse(delivered)
    }
    @Test fun malformedFutureTimestampsAndOversizedResponsesNeverCreateHistory() = runBlocking {
        for (payload in listOf(observation(channel(), now + 60000), """{"payload":{},"observedAt":0}""", "x".repeat(524289))) {
            val store = store(); store.installation("https://service.example", token)
            try { client(store) { 200 to payload }.youtube("youtube.com/@fixture", null); fail() }
            catch (failure: ServiceConnectionFailure) { assertEquals("FORMAT_CHANGED", failure.code) }
        }
    }
    @Test fun proxyRateLimitHtmlStillPreservesTheRetryDeadline() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val client = ManagedApiClient("https://service.example", store, OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Fixture")
                .header("Retry-After", "300").body("<html>limited</html>".toResponseBody()).build()
        }.build())
        try { client.youtube("youtube.com/@fixture", null); fail() }
        catch (failure: ServiceConnectionFailure) {
            assertEquals("RATE_LIMITED", failure.code); assertEquals(300L, failure.retryAfterSeconds)
        }
    }
    @Test fun resumeWaitsForAnInFlightSelectionThenRecoversItsCompletedPage() = runBlocking {
        val store = store(); store.installation("https://service.example", token)
        val pending = PendingFacebookLogin(flow); store.pending(pending)
        var step = 0
        val client = client(store) {
            200 to if (step++ == 0) """{"status":"selecting","pages":[]}"""
            else """{"status":"selected","selectedId":"99","pages":[]}"""
        }
        assertEquals("selecting", client.facebookStatus(pending).status)
        assertEquals("99", client.facebookStatus(pending).selectedId)
        assertEquals(pending, store.pending())
    }
}
