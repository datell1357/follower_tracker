package dev.datell.followertracker.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun scan(ids: List<String>, at: Long, direction: Direction = Direction.FOLLOWERS,
        complete: Boolean = true, account: String = "INSTAGRAM:owner") = RelationshipSnapshot(
        account, direction, at, at + 1, ids.map { Member(it, "user_$it") }, complete, true, "terminal")

    @Test fun stableIdsSurviveUsernameChangesAndFirstScanIsBaseline() {
        val followers = scan(listOf("a", "b"), 100).copy(members = listOf(Member("a", "renamed"), Member("b", "b")))
        val following = scan(listOf("a", "c"), 101, Direction.FOLLOWING)
        val result = RelationshipAnalyzer.compare(followers, following)
        assertTrue(result.baseline)
        assertEquals(listOf("c"), result.notFollowingBack.map(Member::id))
        assertEquals(listOf("b"), result.youDoNotFollowBack.map(Member::id))
        assertEquals(listOf("a"), result.mutual.map(Member::id))
        assertTrue(result.unfollowCandidates.isEmpty())
    }

    @Test fun incompleteOrChangedOwnerNeverCreatesUnfollowers() {
        for (bad in listOf(scan(emptyList(), 100, complete = false), scan(emptyList(), 100, account = "INSTAGRAM:other"))) {
            assertThrows(IllegalArgumentException::class.java) {
                RelationshipAnalyzer.compare(bad, scan(listOf("a"), 101, Direction.FOLLOWING), scan(listOf("a"), 1))
            }
        }
    }

    @Test fun absentCandidateIsRecheckedOnNextCompleteScan() {
        val result = RelationshipAnalyzer.compare(scan(listOf("c"), 300), scan(listOf("c"), 301, Direction.FOLLOWING),
            scan(listOf("b", "c"), 200), scan(listOf("a", "b", "c"), 100))
        assertEquals(listOf("b"), result.unfollowCandidates.map(Member::id))
        assertEquals(listOf("a"), result.repeatedAbsences.map(Member::id))
    }

    @Test fun staleOrOverlappingSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RelationshipAnalyzer.compare(scan(listOf("a"), 1), scan(listOf("a"), 900_000, Direction.FOLLOWING))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelationshipAnalyzer.compare(scan(listOf("a"), 100), scan(listOf("a"), 101, Direction.FOLLOWING), scan(listOf("a"), 100))
        }
    }

    @Test fun paginationRequiresTerminalAndRejectsLoops() {
        val accumulator = PageAccumulator("owner")
        accumulator.append(RelationshipPage("owner", listOf(Member("a", "a")), "next", true))
        assertThrows(CollectionFailure::class.java) { accumulator.finish() }
        assertThrows(CollectionFailure::class.java) {
            accumulator.append(RelationshipPage("owner", listOf(Member("b", "b")), "next", true))
        }
    }

    @Test fun paginationDeduplicatesAndRejectsChangedAccountOrMissingCursor() {
        val accumulator = PageAccumulator("owner")
        accumulator.append(RelationshipPage("owner", listOf(Member("a", "a")), "next", true))
        accumulator.append(RelationshipPage("owner", listOf(Member("a", "renamed"), Member("b", "b")), null, false))
        assertEquals(listOf("a", "b"), accumulator.finish().map(Member::id))
        assertThrows(CollectionFailure::class.java) {
            PageAccumulator("owner").append(RelationshipPage("other", emptyList(), null, false))
        }
        assertThrows(CollectionFailure::class.java) {
            PageAccumulator("owner").append(RelationshipPage("owner", emptyList(), null, true))
        }
    }

    @Test fun sessionDestinationRejectsLookalikesInsecureAndCredentialUrls() {
        assertTrue(Provider.INSTAGRAM.allows("https://www.instagram.com/api/v1/users/1/info/"))
        listOf("https://instagram.com.evil.example/", "http://instagram.com/", "https://instagram.com:444/",
            "https://user:password@instagram.com/", "https://x.com/").forEach { assertFalse(Provider.INSTAGRAM.allows(it)) }
    }

    @Test fun roundedCountsDoNotBecomeExactMetrics() {
        assertEquals(12345L, exactDisplayedCount("12,345"))
        assertEquals(12345L, exactDisplayedCount("12 345"))
        listOf("1.2K", "1.2만", "-4", "", "123.4").forEach { assertNull(exactDisplayedCount(it)) }
    }

    @Test fun validZeroIsDifferentFromMissingCountAndOwnerMismatch() {
        val body = """{"status":"ok","user":{"pk":12,"username":"test","follower_count":0,"following_count":2}}"""
        assertEquals(0L, ResponseParser.instagramProfile(body, "12", 1).second.followers)
        assertThrows(CollectionFailure::class.java) { ResponseParser.instagramProfile(body, "13", 1) }
        assertThrows(CollectionFailure::class.java) { ResponseParser.instagramProfile(body.replace("\"follower_count\":0,", ""), "12", 1) }
    }

    @Test fun loginAndChallengeResponsesStayErrors() {
        val login = assertThrows(CollectionFailure::class.java) { ResponseParser.objectBody("<html>accounts/login</html>") }
        assertEquals(SyncStatus.REAUTH_REQUIRED, login.status)
        val challenge = assertThrows(CollectionFailure::class.java) {
            ResponseParser.instagramProfile("""{"status":"fail","message":"challenge_required"}""", "1", 1)
        }
        assertEquals(SyncStatus.CHECK_REQUIRED, challenge.status)
        assertEquals(SyncStatus.RATE_LIMITED, statusForHttp(429))
        assertEquals(SyncStatus.CHECK_REQUIRED, statusForHttp(403))
    }

    @Test fun redditUsesProfileSubscribersRatherThanKarma() {
        val body = """{"id":"r1","name":"test","total_karma":1234,"subreddit":{"title":"Test","subscribers":12}}"""
        assertEquals(12L, ResponseParser.redditProfile(body, "r1", 1).second.followers)
    }

    @Test fun failedPaginationCannotBeResumedAsACompleteScan() {
        val accumulator = PageAccumulator("owner")
        assertThrows(CollectionFailure::class.java) { accumulator.append(RelationshipPage("other", emptyList(), null, false)) }
        assertThrows(CollectionFailure::class.java) { accumulator.append(RelationshipPage("owner", emptyList(), null, false)) }
        assertThrows(CollectionFailure::class.java) { accumulator.finish() }
    }

    @Test fun tiktokNativeCountRequiresLoggedInOwnerNotOnlyPublicProfileId() {
        val html = """<script type="application/json">{"__DEFAULT_SCOPE__":{"webapp.app-context":{"user":{"id":"42"}},"webapp.user-detail":{"userInfo":{"user":{"id":"42","uniqueId":"self"},"stats":{"followerCount":5,"followingCount":2}}}}}</script>"""
        assertEquals(5L, ResponseParser.tiktokProfile(html, "42", 1).second.followers)
        assertThrows(CollectionFailure::class.java) {
            ResponseParser.tiktokProfile(html.replace("\"webapp.app-context\":{\"user\":{\"id\":\"42\"}}", "\"webapp.app-context\":{}"), "42", 1)
        }
    }
}
