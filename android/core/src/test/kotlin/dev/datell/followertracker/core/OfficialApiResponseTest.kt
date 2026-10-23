package dev.datell.followertracker.core

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OfficialApiResponseTest {
    private val id = "UC" + "a".repeat(22)
    private val target = YouTubePublicChannel.target("https://www.youtube.com/channel/$id")!!
    private fun youtube(count: String = "12300", hidden: String = "false", channel: String = id) =
        """{"items":[{"id":"$channel","snippet":{"title":"Fixture channel"},"statistics":{"subscriberCount":"$count","hiddenSubscriberCount":$hidden}}]}"""
    private fun page(count: String = "12345", pageId: String = "99") = """{"id":"$pageId","name":"Fixture Page","followers_count":$count}"""
    private fun rejected(status: SyncStatus, run: () -> Unit) {
        try { run(); fail("Expected rejection") } catch (e: CollectionFailure) { assertEquals(status, e.status) }
    }
    @Test fun youtubeCountsKeepOfficialRoundingAndZero() {
        for (count in listOf(0L, 999L, 1000L, 12300L)) {
            val (account, metric) = OfficialApiResponse.youtube(youtube(count.toString()), target, null, 5000)
            assertEquals(count, metric.followers)
            assertEquals(if (count < 1000) Precision.EXACT else Precision.ROUNDED, metric.precision)
            assertEquals(CountTransport.OFFICIAL_API, account.countTransport)
            assertEquals("youtube-data-api-v3", metric.source)
            assertTrue(RefreshPolicy.canRefresh(account, 5000, background = true))
            assertFalse(RefreshPolicy.usesProfileBrowser(account))
        }
    }
    @Test fun hiddenMissingInvalidAndAmbiguousCountsNeverCreateAnObservation() {
        for (count in listOf("-1", "1.5", "1e4", "1,234", "", "9007199254740992", "9223372036854775808"))
            rejected(SyncStatus.FORMAT_CHANGED) { OfficialApiResponse.youtube(youtube(count), target, null, 1) }
        for (hidden in listOf("true", "null", "\"false\""))
            rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.youtube(youtube(hidden = hidden), target, null, 1) }
        rejected(SyncStatus.FORMAT_CHANGED) { OfficialApiResponse.youtube(youtube().replace("\"subscriberCount\":\"12300\",", ""), target, null, 1) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.youtube("""{"items":[]}""", target, null, 1) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.youtube("""{"items":[{},{}]}""", target, null, 1) }
    }
    @Test fun channelIdentityIsPinnedAcrossHandleResolutionAndStatisticsOnlyRefresh() {
        val handle = YouTubePublicChannel.target("youtube.com/@fixture")!!
        val first = OfficialApiResponse.youtube(youtube(), handle, null, 10).first
        assertEquals("fixture", first.username)
        val next = OfficialApiResponse.youtube(youtube().replace("\"snippet\":{\"title\":\"Fixture channel\"},", ""), target, first, 20)
        assertEquals(first.key, next.first.key); assertEquals(10, next.first.connectedAt)
        assertEquals(20, next.second.observedAt); assertEquals(first.displayName, next.first.displayName)
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.youtube(youtube(channel = "UC" + "b".repeat(22)), handle, first, 30) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.youtube(youtube(), target, first.copy(loggedOut = true), 30) }
    }
    @Test fun pageTokenConnectionRequiresFollowersFieldAndPreservesPageHistoryKey() {
        val old = Account(Provider.FACEBOOK, "99", "fixture", "Fixture Page", "https://www.facebook.com/fixture",
            connectedAt = 1, accountType = AccountType.PAGE, sessionOwnerId = "42", countTransport = CountTransport.PROFILE_BROWSER)
        val (account, metric) = OfficialApiResponse.facebookPage(page(), old, 5000)
        assertEquals(old.key, account.key); assertEquals(old.connectedAt, account.connectedAt)
        assertEquals("99", account.authenticationOwnerId); assertNull(account.sessionOwnerId)
        assertEquals(12345L, metric.followers); assertNull(metric.following)
        assertEquals(Precision.EXACT, metric.precision); assertEquals("facebook-graph-api-v26", metric.source)
        assertEquals(0L, OfficialApiResponse.facebookPage(page("0"), null, 1).second.followers)
        rejected(SyncStatus.FORMAT_CHANGED) { OfficialApiResponse.facebookPage("""{"id":"42","name":"A person"}""", null, 1) }
        rejected(SyncStatus.FORMAT_CHANGED) { OfficialApiResponse.facebookPage("""{"id":"99","name":"Fixture Page","fan_count":99}""", null, 1) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.facebookPage(page(pageId = "100"), old, 1) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.facebookPage(page(), old.copy(loggedOut = true), 1) }
    }
    @Test fun malformedAndProviderErrorBodiesCannotBecomeCounts() {
        for (body in listOf("<html>login</html>", "[]", "{}", page("-1"), page("null"), page("1.2"), "x".repeat(65_537)))
            rejected(SyncStatus.FORMAT_CHANGED) { OfficialApiResponse.facebookPage(body, null, 1) }
        rejected(SyncStatus.CHECK_REQUIRED) { OfficialApiResponse.facebookPage("""{"error":{"code":190},"id":"99","name":"Fixture","followers_count":10}""", null, 1) }
    }
    @Test fun officialConnectionsSerializeAndKeepLegacyCookieRequirements() {
        val account = OfficialApiResponse.facebookPage(page(), null, 1).first
        assertEquals(account, Json.decodeFromString<Account>(Json.encodeToString(account)))
        assertTrue(RefreshPolicy.supportsCountRefresh(account)); assertFalse(RefreshPolicy.usesProfileBrowser(account))
        assertFalse(RefreshPolicy.canRefresh(account.copy(status = SyncStatus.REAUTH_REQUIRED), 2, true))
        assertFalse(RefreshPolicy.canRefresh(account.copy(loggedOut = true), 2, false))
        assertEquals("API 재연결 필요", account.copy(status = SyncStatus.REAUTH_REQUIRED).statusLabel)
        try { account.copy(countTransport = CountTransport.PROFILE_BROWSER); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun quotaWaitsUntilPacificMidnightRatherThanRetryingEveryMinute() {
        val now = Instant.parse("2026-10-23T19:00:00Z").toEpochMilli() // noon PDT
        val failure = OfficialApiResponse.failure(Provider.YOUTUBE, 403,
            """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}""", null, now)!!
        assertEquals(SyncStatus.RATE_LIMITED, failure.status); assertEquals(43_200L, failure.retryAfterSeconds)
        assertEquals(now + 43_200_000, RefreshPolicy.serviceRetryAt(failure, now))
    }
    @Test fun expiryPermissionThrottleAndTransientErrorsStayDistinct() {
        val now = Instant.parse("2026-10-23T19:00:00Z").toEpochMilli()
        fun facebook(code: Int, extra: String = "") = OfficialApiResponse.failure(Provider.FACEBOOK, 400,
            """{"error":{"code":$code$extra}}""", "123", now)!!
        assertEquals(SyncStatus.REAUTH_REQUIRED, facebook(190).status)
        assertEquals(SyncStatus.CHECK_REQUIRED, facebook(10).status)
        assertEquals(SyncStatus.CHECK_REQUIRED, facebook(200).status)
        for (code in listOf(4, 17, 32, 613)) {
            assertEquals(SyncStatus.RATE_LIMITED, facebook(code).status); assertEquals(123L, facebook(code).retryAfterSeconds)
        }
        assertEquals(SyncStatus.OFFLINE, facebook(2, ",\"is_transient\":true").status)
        assertEquals(120L, OfficialApiResponse.failure(Provider.YOUTUBE, 429, "", "Fri, 23 Oct 2026 19:02:00 GMT", now)?.retryAfterSeconds)
        assertNull(OfficialApiResponse.failure(Provider.FACEBOOK, 200, page(), null, now))
    }
}
