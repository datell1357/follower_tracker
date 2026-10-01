package dev.datell.followertracker.core

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class RelationshipChangeState(val label: String) {
    CANDIDATE("처음 미관측"), REPEATED_ABSENCE("연속 미관측"), REOBSERVED("다시 관측됨")
}

@Serializable
data class RelationshipChange(
    val id: String,
    val accountKey: String,
    val member: Member,
    val previousScanId: String,
    val currentScanId: String,
    val detectedAt: Long,
    val checkedScanId: String,
    val checkedAt: Long,
    val absenceChecks: Int,
    val state: RelationshipChangeState,
) {
    init {
        require(id.isNotBlank() && accountKey.isNotBlank())
        require(previousScanId.isNotBlank() && currentScanId.isNotBlank() && checkedScanId.isNotBlank())
        require(checkedAt >= detectedAt && absenceChecks > 0)
    }
}

/** Each episode retains its original evidence even after more than three scans. */
object RelationshipHistory {
    fun observe(current: RelationshipSnapshot, previous: RelationshipSnapshot?,
        existing: List<RelationshipChange>): List<RelationshipChange> {
        fun validate(scan: RelationshipSnapshot) {
            require(scan.direction == Direction.FOLLOWERS && scan.complete && scan.consistent)
            require(scan.finishedAt >= scan.startedAt && scan.accountKey == current.accountKey)
            require(scan.members.map(Member::id).toSet().size == scan.members.size)
        }
        validate(current)
        previous?.let { validate(it); require(it.finishedAt < current.startedAt) }
        require(existing.all { it.accountKey == current.accountKey })
        require(existing.map(RelationshipChange::id).toSet().size == existing.size)
        val pending = existing.filter { it.state != RelationshipChangeState.REOBSERVED }
        require(pending.map { it.member.id }.toSet().size == pending.size)
        require(pending.all { it.checkedAt < current.startedAt })
        val currentIds = current.members.map(Member::id).toSet()
        val scanId = scanId(current)
        val result = existing.map { change ->
            if (change.state == RelationshipChangeState.REOBSERVED) change
            else change.copy(checkedScanId = scanId, checkedAt = current.finishedAt,
                absenceChecks = if (change.member.id in currentIds) change.absenceChecks else Math.addExact(change.absenceChecks, 1),
                state = if (change.member.id in currentIds) RelationshipChangeState.REOBSERVED else RelationshipChangeState.REPEATED_ABSENCE)
        }.toMutableList()
        val pendingIds = pending.map { it.member.id }.toSet()
        previous?.members?.filter { it.id !in currentIds && it.id !in pendingIds }?.forEach { member ->
            result += RelationshipChange(UUID.randomUUID().toString(), current.accountKey, member,
                scanId(previous), scanId, current.finishedAt, scanId, current.finishedAt, 1, RelationshipChangeState.CANDIDATE)
        }
        return result
    }

    fun scanId(scan: RelationshipSnapshot): String = "${scan.accountKey}:${scan.direction.name}:${scan.finishedAt}"
}
