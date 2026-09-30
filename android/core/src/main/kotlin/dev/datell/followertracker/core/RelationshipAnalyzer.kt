package dev.datell.followertracker.core

class PageAccumulator(private val ownerKey: String, private val limit: Int = 100_000) {
    private val members = linkedMapOf<String, Member>()
    private val cursors = mutableSetOf<String>()
    private var terminal = false
    private var failed = false

    fun append(page: RelationshipPage) {
        if (failed || terminal || page.ownerKey != ownerKey) fail()
        page.members.forEach { members[it.id] = it }
        if (members.size > limit) fail()
        if (page.hasMore && page.nextCursor.isNullOrBlank()) fail()
        if (!page.hasMore && page.nextCursor != null) fail()
        page.nextCursor?.let {
            if (!cursors.add(it)) fail()
        }
        terminal = !page.hasMore
    }

    fun finish(): List<Member> {
        if (failed || !terminal) throw CollectionFailure(SyncStatus.LIST_INCOMPLETE)
        return members.values.toList()
    }
    private fun fail(): Nothing {
        failed = true
        throw CollectionFailure(SyncStatus.LIST_INCOMPLETE)
    }
}

data class RelationshipReport(
    val notFollowingBack: List<Member>,
    val youDoNotFollowBack: List<Member>,
    val mutual: List<Member>,
    val unfollowCandidates: List<Member>,
    val repeatedAbsences: List<Member>,
    val comparedAt: Long,
    val baseline: Boolean,
)

object RelationshipAnalyzer {
    private fun usable(snapshot: RelationshipSnapshot) = snapshot.complete && snapshot.consistent

    fun compare(
        followers: RelationshipSnapshot,
        following: RelationshipSnapshot,
        previous: RelationshipSnapshot? = null,
        beforePrevious: RelationshipSnapshot? = null,
        maxSkewMillis: Long = 10 * 60 * 1000L,
    ): RelationshipReport {
        require(usable(followers) && usable(following)) { "Incomplete or inconsistent scan" }
        require(followers.direction == Direction.FOLLOWERS && following.direction == Direction.FOLLOWING)
        require(followers.accountKey == following.accountKey) { "Account changed" }
        require(kotlin.math.abs(followers.finishedAt - following.finishedAt) <= maxSkewMillis) { "Stale pairing" }
        previous?.let {
            require(usable(it) && it.accountKey == followers.accountKey && it.direction == Direction.FOLLOWERS)
            require(it.finishedAt < followers.startedAt) { "Overlapping snapshots" }
        }
        beforePrevious?.let {
            require(previous != null && usable(it) && it.accountKey == followers.accountKey)
            require(it.direction == Direction.FOLLOWERS && it.finishedAt < previous.startedAt)
        }
        val currentById = followers.members.associateBy(Member::id)
        val followingById = following.members.associateBy(Member::id)
        val priorById = previous?.members?.associateBy(Member::id).orEmpty()
        val earlierById = beforePrevious?.members?.associateBy(Member::id).orEmpty()
        return RelationshipReport(
            notFollowingBack = followingById.filterKeys { it !in currentById }.values.toList(),
            youDoNotFollowBack = currentById.filterKeys { it !in followingById }.values.toList(),
            mutual = currentById.filterKeys { it in followingById }.values.toList(),
            unfollowCandidates = priorById.filterKeys { it !in currentById }.values.toList(),
            repeatedAbsences = earlierById.filterKeys { it !in priorById && it !in currentById }.values.toList(),
            comparedAt = maxOf(followers.finishedAt, following.finishedAt),
            baseline = previous == null,
        )
    }
}
