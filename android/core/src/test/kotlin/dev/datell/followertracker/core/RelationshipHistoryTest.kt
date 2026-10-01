package dev.datell.followertracker.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RelationshipHistoryTest {
    private fun scan(vararg ids: String, at: Long) = RelationshipSnapshot("INSTAGRAM:owner", Direction.FOLLOWERS,
        at, at + 1, ids.map { Member(it, "user_$it") }, true, true, "terminal")

    @Test fun baselineNeverCreatesEventsAndLossRetainsOriginalEvidence() {
        val first = scan("a", "b", at = 100)
        assertTrue(RelationshipHistory.observe(first, null, emptyList()).isEmpty())
        val second = scan("b", at = 200)
        val event = RelationshipHistory.observe(second, first, emptyList()).single()
        assertEquals("a", event.member.id)
        assertEquals(RelationshipHistory.scanId(first), event.previousScanId)
        assertEquals(RelationshipHistory.scanId(second), event.currentScanId)
        assertEquals(201L, event.detectedAt)
        assertEquals(RelationshipChangeState.CANDIDATE, event.state)
    }

    @Test fun repeatedAbsenceSurvivesManyScansWithoutDuplicatingEpisode() {
        var previous = scan("a", at = 100)
        var events = emptyList<RelationshipChange>()
        for (time in listOf(200L, 300L, 400L, 500L, 600L)) {
            val current = scan(at = time)
            events = RelationshipHistory.observe(current, previous, events)
            previous = current
        }
        val event = events.single()
        assertEquals(201L, event.detectedAt)
        assertEquals(601L, event.checkedAt)
        assertEquals(5, event.absenceChecks)
        assertEquals("INSTAGRAM:owner:FOLLOWERS:201", event.currentScanId)
        assertEquals(RelationshipChangeState.REPEATED_ABSENCE, event.state)
    }

    @Test fun reappearanceClosesOldEpisodeAndLaterLossCreatesNewEpisode() {
        val first = scan("a", at = 100); val absent = scan(at = 200)
        val initial = RelationshipHistory.observe(absent, first, emptyList()).single()
        val returned = scan("a", at = 300)
        val closed = RelationshipHistory.observe(returned, absent, listOf(initial)).single()
        assertEquals(initial.id, closed.id)
        assertEquals(RelationshipChangeState.REOBSERVED, closed.state)
        assertEquals(1, closed.absenceChecks)
        val events = RelationshipHistory.observe(scan(at = 400), returned, listOf(closed))
        assertEquals(2, events.size)
        assertEquals(closed, events.first())
        assertNotEquals(closed.id, events.last().id)
    }

    @Test fun usernameChangeDoesNotCreateLossAndJsonPreservesHistory() {
        val first = scan("a", at = 100)
        val renamed = scan("a", at = 200).copy(members = listOf(Member("a", "renamed")))
        assertTrue(RelationshipHistory.observe(renamed, first, emptyList()).isEmpty())
        val event = RelationshipHistory.observe(scan(at = 300), renamed, emptyList()).single()
        assertEquals("renamed", event.member.username)
        assertEquals(event, Json.decodeFromString<RelationshipChange>(Json.encodeToString(event)))
    }

    @Test fun incompleteChangedOwnerDuplicateAndStaleScansCannotAdvanceHistory() {
        val first = scan("a", at = 100)
        val missing = scan(at = 200)
        val event = RelationshipHistory.observe(missing, first, emptyList()).single()
        for (invalid in listOf(scan(at = 300).copy(complete = false), scan(at = 300).copy(consistent = false),
            scan(at = 300).copy(accountKey = "INSTAGRAM:other"), scan("a", "a", at = 300), missing,
            scan(at = 300).copy(direction = Direction.FOLLOWING))) {
            assertThrows(IllegalArgumentException::class.java) { RelationshipHistory.observe(invalid, missing, listOf(event)) }
        }
        assertEquals(RelationshipChangeState.CANDIDATE, event.state)
        assertEquals(201L, event.checkedAt)
    }
}
