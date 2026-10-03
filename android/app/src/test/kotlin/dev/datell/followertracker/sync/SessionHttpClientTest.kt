package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SessionHttpClientTest {
    private class SyntheticSession(var authenticated: Boolean = true) : SessionAccess {
        var cookiesAccepted = false
        override fun header(provider: Provider, url: String) = "sessionid=synthetic-session; ds_user_id=42; csrftoken=synthetic-csrf"
        override fun hasAuthentication(provider: Provider) = authenticated
        override fun acceptResponseCookies(provider: Provider, url: String, values: List<String>, expectedHeader: String) { cookiesAccepted = values.isNotEmpty() }
    }
    private fun client(session: SyntheticSession, code: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap(), inspect: (Request) -> Unit = {}) =
        SessionHttpClient(session, OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic fixture")
                .body(body.toResponseBody()).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        }.build())
    private val url = "https://www.instagram.com/api/v1/users/42/info/".toHttpUrl()
    private fun failure(expected: SyncStatus, block: suspend () -> Unit): CollectionFailure = runBlocking {
        try { block(); fail("Expected collection failure"); error("unreachable") }
        catch (failure: CollectionFailure) { assertEquals(expected, failure.status); failure }
    }
    @Test fun sessionIsSentOnlyToAllowedHTTPSHost() = runBlocking {
        val session = SyntheticSession()
        val http = client(session, headers = mapOf("Set-Cookie" to "sessionid=synthetic-rotated; Secure; Path=/")) { request ->
            assertEquals("GET", request.method)
            assertEquals("no-cache", request.header("Cache-Control"))
            assertEquals("synthetic-csrf", request.header("X-CSRFToken"))
            assertEquals("fixture-agent", request.header("User-Agent"))
            assertEquals("https://www.instagram.com", request.header("Origin"))
            assertEquals("https://www.instagram.com/", request.header("Referer"))
        }
        assertEquals("{}", http.read(Provider.INSTAGRAM, url, "fixture-agent"))
        assertTrue(session.cookiesAccepted)
    }
    @Test fun lookalikeHostIsRejectedBeforeTransportReceivesCookies() {
        var sent = false
        val http = client(SyntheticSession(), inspect = { sent = true })
        failure(SyncStatus.CHECK_REQUIRED) { http.read(Provider.INSTAGRAM, "https://instagram.com.example.test/api/".toHttpUrl(), "fixture-agent") }
        assertFalse(sent)
    }
    @Test fun expiredSessionDoesNotMakeARequest() {
        var sent = false
        val http = client(SyntheticSession(false), inspect = { sent = true })
        failure(SyncStatus.REAUTH_REQUIRED) { http.read(Provider.INSTAGRAM, url, "fixture-agent") }
        assertFalse(sent)
    }
    @Test fun challengeAndAuthenticationAreDifferentFailures() {
        failure(SyncStatus.CHECK_REQUIRED) { client(SyntheticSession(), 403).read(Provider.INSTAGRAM, url, "fixture-agent") }
        failure(SyncStatus.REAUTH_REQUIRED) { client(SyntheticSession(), 401).read(Provider.INSTAGRAM, url, "fixture-agent") }
    }
    @Test fun rateLimitPreservesBoundedRetryAfter() {
        val result = failure(SyncStatus.RATE_LIMITED) { client(SyntheticSession(), 429, headers = mapOf("Retry-After" to "99999999")).read(Provider.INSTAGRAM, url, "fixture-agent") }
        assertEquals(86_400L, result.retryAfterSeconds)
    }
    @Test fun loginRedirectIsNotTreatedAsProfileData() {
        failure(SyncStatus.REAUTH_REQUIRED) { client(SyntheticSession(), 302, headers = mapOf("Location" to "/accounts/login/")).read(Provider.INSTAGRAM, url, "fixture-agent") }
    }
    @Test fun oversizedBodyDoesNotRotateSessionOrReturnPartialData() {
        val session = SyntheticSession()
        failure(SyncStatus.FORMAT_CHANGED) { client(session, body = "x".repeat(8 * 1024 * 1024 + 1), headers = mapOf("Set-Cookie" to "sessionid=synthetic-new")).read(Provider.INSTAGRAM, url, "fixture-agent") }
        assertFalse(session.cookiesAccepted)
    }
}
