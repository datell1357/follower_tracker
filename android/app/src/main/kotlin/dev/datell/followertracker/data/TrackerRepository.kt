package dev.datell.followertracker.data

import androidx.room.withTransaction
import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AccountOverview(val account: Account, val history: List<MetricSnapshot>) {
    val latest: MetricSnapshot? get() = history.lastOrNull()
    val previous: MetricSnapshot? get() = history.dropLast(1).lastOrNull()
    val comparison: MetricComparison? get() = MetricComparison.between(previous, latest)
    val change: Long? get() = comparison?.change
    val comparisonAt: Long? get() = comparison?.previousAt
}

class TrackerRepository(private val database: TrackerDatabase, private val cipher: DataCipher) {
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true }
    val accounts = dao.observeAccounts().map { rows -> rows.map { json.decodeFromString<Account>(it.json) }.filterNot { it.loggedOut } }
    suspend fun accounts(): List<Account> = withContext(Dispatchers.IO) {
        dao.accounts().map { json.decodeFromString<Account>(it.json) }.filterNot { it.loggedOut }
    }
    suspend fun account(key: String): Account? = withContext(Dispatchers.IO) {
        dao.account(key)?.let { json.decodeFromString<Account>(it.json) }
    }
    suspend fun overviews(): List<AccountOverview> = withContext(Dispatchers.IO) {
        accounts().map { AccountOverview(it, history(it.key)) }
    }
    /** Display-only snapshot. History remains intact and is read separately by charts. */
    suspend fun widgetOverviews(key: String? = null): List<AccountOverview> = withContext(Dispatchers.IO) {
        accounts().filter { key == null || it.key == key }.map { AccountOverview(it, recentMetrics(it.key, 2)) }
    }
    fun widgetOverviewsFlow() = accounts.map { widgetOverviews() }
    suspend fun history(key: String): List<MetricSnapshot> = recentMetrics(key, 366)
    private suspend fun recentMetrics(key: String, limit: Int): List<MetricSnapshot> = withContext(Dispatchers.IO) {
        dao.metrics(key, limit).asReversed().map {
            MetricSnapshot(it.accountKey, it.observedAt, it.followers, it.following, Precision.valueOf(it.precision), it.source, it.adapterVersion)
        }
    }
    suspend fun saveObservation(account: Account, metric: MetricSnapshot, requireExisting: Boolean = false,
        writeIf: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        require(account.key == metric.accountKey)
        val stored = database.withTransaction {
            if (!writeIf()) return@withTransaction false
            if (accounts().any(account::conflictsWith)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            val previous = this@TrackerRepository.account(account.key)
            // A request must belong to the same connection, including after disconnect/reconnect.
            if (requireExisting && (previous == null || previous.loggedOut || previous.connectedAt != account.connectedAt)) return@withTransaction false
            val preserved = account.copy(connectedAt = previous?.connectedAt ?: account.connectedAt,
                relationshipStatus = previous?.relationshipStatus?.takeUnless { previous.loggedOut && it == SyncStatus.REAUTH_REQUIRED },
                capabilities = account.capabilities.copy(followers = previous?.capabilities?.followers ?: account.capabilities.followers,
                    following = previous?.capabilities?.following ?: account.capabilities.following), transientRetry = null)
            dao.putAccount(AccountEntity(preserved.key, preserved.provider.name, preserved.connectedAt, json.encodeToString(preserved)))
            dao.putMetric(MetricEntity(metric.accountKey, metric.observedAt, metric.followers, metric.following,
                metric.precision.name, metric.source, metric.adapterVersion))
            true
        }
        // Emitted only after the transaction commits. A fenced/rolled-back result is never a success.
        DebugDiagnostics.record(DiagnosticEntry(if (stored) DiagnosticKind.OBSERVATION_STORED else DiagnosticKind.OBSERVATION_DISCARDED,
            account.provider, account.accountType, attempt = currentCoroutineContext()[DiagnosticAttempt]?.id,
            observedAt = metric.observedAt.takeIf { stored }))
    }
    suspend fun updateStatus(key: String, status: SyncStatus, now: Long, nextAllowedAt: Long? = null,
        expectedConnectedAt: Long? = null, transientRetry: TransientRetryState? = null,
        writeIf: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        val saved = database.withTransaction {
            if (!writeIf()) return@withTransaction null
            val previous = account(key) ?: return@withTransaction null
            if (previous.loggedOut) return@withTransaction null
            if (expectedConnectedAt != null && previous.connectedAt != expectedConnectedAt) return@withTransaction null
            val updated = previous.copy(status = status, lastAttemptAt = now, nextAllowedAt = nextAllowedAt, transientRetry = transientRetry)
            dao.putAccount(AccountEntity(updated.key, updated.provider.name, updated.connectedAt, json.encodeToString(updated)))
            updated
        }
        if (saved != null && status != SyncStatus.REFRESHING) DebugDiagnostics.record(DiagnosticEntry(
            DiagnosticKind.STATUS_SAVED, saved.provider, saved.accountType, attempt = currentCoroutineContext()[DiagnosticAttempt]?.id,
            status = status, serviceRetryAt = nextAllowedAt, localRetryAt = transientRetry?.nextAttemptAt,
            failureCount = transientRetry?.failureCount?.coerceIn(0, 5)))
    }
    suspend fun saveScans(followers: RelationshipSnapshot, following: RelationshipSnapshot, expectedConnectedAt: Long? = null,
        writeIf: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        RelationshipAnalyzer.compare(followers, following)
        database.withTransaction {
            if (!writeIf()) return@withTransaction
            val previous = account(followers.accountKey) ?: return@withTransaction
            if (previous.loggedOut) return@withTransaction
            if (expectedConnectedAt != null && previous.connectedAt != expectedConnectedAt) return@withTransaction
            restoreRelationshipHistory(followers.accountKey)
            val baseline = scans(followers.accountKey, Direction.FOLLOWERS).firstOrNull()
            recordRelationshipChanges(followers, baseline)
            for (scan in listOf(followers, following)) {
                dao.putScan(ScanEntity(scan.accountKey, scan.direction.name, scan.finishedAt,
                    cipher.seal(json.encodeToString(scan).toByteArray(Charsets.UTF_8))))
            }
            val updated = previous.copy(relationshipStatus = SyncStatus.READY,
                capabilities = previous.capabilities.copy(followers = Capability.OBSERVED, following = Capability.OBSERVED), transientRetry = null)
            dao.putAccount(AccountEntity(updated.key, updated.provider.name, updated.connectedAt, json.encodeToString(updated)))
        }
    }
    suspend fun updateListStatus(key: String, status: SyncStatus?, expectedConnectedAt: Long? = null,
        writeIf: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!writeIf()) return@withTransaction
            val previous = account(key) ?: return@withTransaction
            if (previous.loggedOut) return@withTransaction
            if (expectedConnectedAt != null && previous.connectedAt != expectedConnectedAt) return@withTransaction
            val updated = previous.copy(relationshipStatus = status)
            dao.putAccount(AccountEntity(updated.key, updated.provider.name, updated.connectedAt, json.encodeToString(updated)))
        }
    }
    private suspend fun scans(key: String, direction: Direction): List<RelationshipSnapshot> = withContext(Dispatchers.IO) {
        dao.scans(key, direction.name, 3).map { json.decodeFromString<RelationshipSnapshot>(cipher.open(it.encrypted).toString(Charsets.UTF_8)) }
    }
    private fun decodeChange(row: RelationshipChangeEntity): RelationshipChange =
        json.decodeFromString(cipher.open(row.encrypted).toString(Charsets.UTF_8))

    private suspend fun recordRelationshipChanges(current: RelationshipSnapshot, previous: RelationshipSnapshot?) {
        val existing = dao.pendingRelationshipChanges(current.accountKey).map(::decodeChange)
        for (change in RelationshipHistory.observe(current, previous, existing)) {
            dao.putRelationshipChange(RelationshipChangeEntity(change.id, change.accountKey, change.detectedAt, change.state.name,
                cipher.seal(json.encodeToString(change).toByteArray(Charsets.UTF_8))))
        }
        dao.putHistoryCursor(RelationshipHistoryCursorEntity(current.accountKey, current.finishedAt))
    }

    // Replay legacy scans once, one encrypted snapshot at a time, within the caller's transaction.
    private suspend fun restoreRelationshipHistory(key: String) {
        if (dao.historyCursor(key) != null) return
        var previous: RelationshipSnapshot? = null
        var after = Long.MIN_VALUE
        while (true) {
            val row = dao.nextFollowersScan(key, after) ?: break
            val current = json.decodeFromString<RelationshipSnapshot>(cipher.open(row.encrypted).toString(Charsets.UTF_8))
            require(current.accountKey == key && current.finishedAt == row.finishedAt)
            recordRelationshipChanges(current, previous)
            previous = current; after = row.finishedAt
        }
    }

    suspend fun relationshipChanges(key: String): List<RelationshipChange> = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (account(key) == null) return@withTransaction emptyList()
            restoreRelationshipHistory(key)
            dao.relationshipChanges(key).map(::decodeChange)
        }
    }
    suspend fun report(key: String): RelationshipReport? = withContext(Dispatchers.IO) {
        val followers = scans(key, Direction.FOLLOWERS)
        val following = scans(key, Direction.FOLLOWING).firstOrNull() ?: return@withContext null
        val latest = followers.firstOrNull() ?: return@withContext null
        RelationshipAnalyzer.compare(latest, following, followers.getOrNull(1), followers.getOrNull(2))
    }
    suspend fun markLoggedOut(provider: Provider) = withContext(Dispatchers.IO) {
        database.withTransaction {
            for (account in accounts().filter { it.provider == provider }) {
                val updated = account.copy(status = SyncStatus.REAUTH_REQUIRED, loggedOut = true,
                    nextAllowedAt = null, transientRetry = null)
                dao.putAccount(AccountEntity(updated.key, updated.provider.name, updated.connectedAt, json.encodeToString(updated)))
            }
        }
    }
    suspend fun disconnect(key: String) = withContext(Dispatchers.IO) { dao.deleteAccount(key) }
}
