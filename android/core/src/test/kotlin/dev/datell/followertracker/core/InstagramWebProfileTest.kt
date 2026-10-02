package dev.datell.followertracker.core

import org.junit.Assert.*
import org.junit.Test

class InstagramWebProfileTest {
    private fun body(id: String = "42", followers: String = "123", following: String = "100") =
        """{"status":"ok","data":{"user":{"id":"$id","username":"fixture_owner","edge_followed_by":{"count":$followers},"edge_follow":{"count":$following}}}}"""

    @Test fun readsExactCountsAndValidZeroForCookieOwner() {
        val (account, metric) = ResponseParser.instagramWebProfile(body(followers = "0"), "42", 1)
        assertEquals("42", account.stableId)
        assertEquals(0L, metric.followers)
        assertEquals(100L, metric.following)
        assertEquals("instagram-web-profile-session", metric.source)
    }

    @Test fun rejectsOtherProfileEvenWhenItsCountsAreValid() {
        failure(SyncStatus.CHECK_REQUIRED) { ResponseParser.instagramWebProfile(body(id = "other"), "42", 1) }
    }

    @Test fun missingRoundedNegativeAndFractionalCountsAreNotObservations() {
        for (invalid in listOf("null", "\"1.2K\"", "-1", "1.5")) {
            failure(SyncStatus.FORMAT_CHANGED) { ResponseParser.instagramWebProfile(body(followers = invalid), "42", 1) }
        }
        failure(SyncStatus.FORMAT_CHANGED) {
            ResponseParser.instagramWebProfile("""{"data":{"user":{"id":"42","username":"fixture_owner"}}}""", "42", 1)
        }
    }

    @Test fun directIntegerCountsAreAcceptedWhenEdgesAreAbsent() {
        val (_, metric) = ResponseParser.instagramWebProfile(
            """{"data":{"user":{"pk":"42","username":"fixture_owner","follower_count":12,"following_count":0}}}""", "42", 1)
        assertEquals(12L, metric.followers)
        assertEquals(0L, metric.following)
    }

    @Test fun rateLimitAndLoginFailuresKeepTheirMeaning() {
        failure(SyncStatus.RATE_LIMITED) {
            ResponseParser.instagramWebProfile("""{"status":"fail","message":"Please wait a few minutes"}""", "42", 1)
        }
        failure(SyncStatus.REAUTH_REQUIRED) {
            ResponseParser.instagramWebProfile("""{"status":"fail","message":"login_required"}""", "42", 1)
        }
    }

    private fun failure(expected: SyncStatus, block: () -> Unit) {
        try { block(); fail("Expected a collection failure") }
        catch (failure: CollectionFailure) { assertEquals(expected, failure.status) }
    }
}
