package dev.datell.followertracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

private class TransientCollector : SessionCollecting {
    var metricRequests = 0
    var listRequests = 0
    var failure: CollectionFailure? = CollectionFailure(SyncStatus.OFFLINE)
    var cancels = false
    override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
        metricRequests++
        if (cancels) throw CancellationException()
        failure?.let { throw it }
        val account = checkNotNull(expected).copy(status = SyncStatus.READY, nextAllowedAt = null)
        return account to MetricSnapshot(account.key, System.currentTimeMillis(), 9, 0, source = "synthetic-transient-recovery")
    }
    override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> {
        listRequests++
        if (cancels) throw CancellationException()
        failure?.let { throw it }
        val at = System.currentTimeMillis()
        fun scan(direction: Direction) = RelationshipSnapshot(account.key, direction, at, at,
            emptyList(), true, true, "synthetic-terminal")
        return scan(Direction.FOLLOWERS) to scan(Direction.FOLLOWING)
    }
}

/** UUID databases and synthetic collectors only. Never uses appGraph, sessions, or a live SNS request. */
@RunWith(AndroidJUnit4::class)
class SyncTransientRetryRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private data class Fixture(val name: String, val database: TrackerDatabase, val repository: TrackerRepository, val account: Account)
    private suspend fun fixture(): Fixture {
        val name = "tracker-transient-qa-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, name).build()
        val repository = TrackerRepository(database, DataCipher())
        val account = Account(Provider.INSTAGRAM, "fixture", "sample", profileUrl = "https://www.instagram.com/sample/", connectedAt = 1)
        repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 0, 0, source = "synthetic-baseline"))
        return Fixture(name, database, repository, account)
    }
    @Test fun automaticNetworkRetriesWaitAndPersistWithoutReplacingTheLastMetric() = runBlocking {
        val f = fixture()
        try {
            var publications = 0
            val collector = TransientCollector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = { publications++ })
            sync.refresh(f.account.key, background = true)
            sync.refresh(f.account.key, background = true)
            val stored = checkNotNull(f.repository.account(f.account.key))
            assertEquals(1, collector.metricRequests)
            assertEquals(1, publications)
            assertEquals(SyncStatus.OFFLINE, stored.status)
            assertEquals(1, stored.transientRetry?.failureCount)
            assertEquals(listOf(1_000L), f.repository.history(f.account.key).map { it.observedAt })
            assertEquals(0L, f.repository.history(f.account.key).last().followers)
            f.database.close()
            val reopened = Room.databaseBuilder(context, TrackerDatabase::class.java, f.name).build()
            try { assertEquals(stored.transientRetry, TrackerRepository(reopened, DataCipher()).account(f.account.key)?.transientRetry) }
            finally { reopened.close() }
        } finally { f.database.close() }
    }
    @Test fun anExplicitRetryCanRecoverAndResetTheLocalBackoff() = runBlocking {
        val f = fixture()
        try {
            val collector = TransientCollector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            sync.refresh(f.account.key, background = true)
            collector.failure = null
            sync.refresh(f.account.key)
            val stored = checkNotNull(f.repository.account(f.account.key))
            assertEquals(2, collector.metricRequests)
            assertEquals(SyncStatus.READY, stored.status); assertNull(stored.transientRetry)
            assertEquals(2, f.repository.history(f.account.key).size)
            assertEquals(9L, f.repository.history(f.account.key).last().followers)
        } finally { f.database.close() }
    }
    @Test fun consecutiveFailuresIncreaseThePersistedDelay() = runBlocking {
        val f = fixture()
        try {
            val collector = TransientCollector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            sync.refresh(f.account.key)
            val first = checkNotNull(f.repository.account(f.account.key)?.transientRetry)
            sync.refresh(f.account.key)
            val second = checkNotNull(f.repository.account(f.account.key)?.transientRetry)
            assertEquals(2, collector.metricRequests); assertEquals(2, second.failureCount)
            assertTrue(second.nextAttemptAt >= first.nextAttemptAt + 60_000)
            assertEquals(1, f.repository.history(f.account.key).size)
        } finally { f.database.close() }
    }
    @Test fun serverRetryAfterBlocksEvenAnExplicitNetworkRetry() = runBlocking {
        val f = fixture()
        try {
            val collector = TransientCollector().apply { failure = CollectionFailure(SyncStatus.OFFLINE, 120) }
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            val began = System.currentTimeMillis()
            sync.refresh(f.account.key, background = true)
            sync.refresh(f.account.key)
            val stored = checkNotNull(f.repository.account(f.account.key))
            assertEquals(1, collector.metricRequests)
            assertTrue(checkNotNull(stored.nextAllowedAt) >= began + 120_000)
            assertNotNull(stored.transientRetry)
        } finally { f.database.close() }
    }
    @Test fun listFailuresShareTheAutomaticBackoffAndKeepTheCompletedReport() = runBlocking {
        val f = fixture()
        try {
            fun baseline(direction: Direction) = RelationshipSnapshot(f.account.key, direction, 1, 2,
                emptyList(), true, true, "synthetic-terminal")
            f.repository.saveScans(baseline(Direction.FOLLOWERS), baseline(Direction.FOLLOWING))
            val collector = TransientCollector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            sync.relationships(f.account.key, background = true)
            sync.refresh(f.account.key, background = true)
            sync.relationships(f.account.key, background = true)
            assertEquals(1, collector.listRequests); assertEquals(0, collector.metricRequests)
            assertEquals(2L, f.repository.report(f.account.key)?.comparedAt)
            collector.failure = null
            sync.relationships(f.account.key)
            assertEquals(SyncStatus.READY, f.repository.account(f.account.key)?.relationshipStatus)
            assertNull(f.repository.account(f.account.key)?.transientRetry)
            assertEquals(1, f.repository.history(f.account.key).size)
        } finally { f.database.close() }
    }
    @Test fun cancellationRestoresTheOriginalRetryState() = runBlocking {
        val f = fixture()
        try {
            val collector = TransientCollector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            sync.refresh(f.account.key, background = true)
            val before = checkNotNull(f.repository.account(f.account.key))
            collector.cancels = true
            try { sync.refresh(f.account.key); fail("The cancelled collection must propagate cancellation") }
            catch (_: CancellationException) { }
            val after = checkNotNull(f.repository.account(f.account.key))
            assertEquals(before.status, after.status); assertEquals(before.nextAllowedAt, after.nextAllowedAt)
            assertEquals(before.transientRetry, after.transientRetry)
            assertEquals(1, f.repository.history(f.account.key).size)
        } finally { f.database.close() }
    }
}
