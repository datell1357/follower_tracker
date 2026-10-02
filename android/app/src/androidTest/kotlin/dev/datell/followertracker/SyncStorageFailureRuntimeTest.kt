package dev.datell.followertracker

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.UUID

private class StorageFailureCollector : SessionCollecting {
    var failure: Exception? = null
    var metricAt = 1_000L
    var scanAt = 2L
    override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
        failure?.let { throw it }
        val account = checkNotNull(expected).copy(status = SyncStatus.READY, nextAllowedAt = null, transientRetry = null)
        return account to MetricSnapshot(account.key, metricAt, 9, 0, source = "synthetic-storage-recovery")
    }
    override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> {
        failure?.let { throw it }
        fun scan(direction: Direction) = RelationshipSnapshot(account.key, direction, scanAt, scanAt,
            emptyList(), true, true, "synthetic-terminal")
        return scan(Direction.FOLLOWERS) to scan(Direction.FOLLOWING)
    }
}

/** Isolated UUID Room databases and synthetic collectors. No appGraph, session Vault, or SNS requests. */
@RunWith(AndroidJUnit4::class)
class SyncStorageFailureRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private data class Fixture(val database: TrackerDatabase, val repository: TrackerRepository, val account: Account)
    private suspend fun fixture(completedLists: Boolean = false): Fixture {
        val identifier = UUID.randomUUID()
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "tracker-storage-qa-$identifier.db").build()
        val repository = TrackerRepository(database, DataCipher("tracker-storage-qa-$identifier"))
        val account = Account(Provider.INSTAGRAM, "fixture", "sample", profileUrl = "https://www.instagram.com/sample/", connectedAt = 1)
        repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 0, 0, source = "synthetic-baseline"))
        if (completedLists) {
            val (followers, following) = StorageFailureCollector().relationships(account)
            repository.saveScans(followers, following)
        }
        val now = System.currentTimeMillis()
        repository.updateStatus(account.key, SyncStatus.OFFLINE, now - 1_000, now - 1,
            transientRetry = TransientRetryState(2, now + 120_000))
        return Fixture(database, repository, checkNotNull(repository.account(account.key)))
    }
    private suspend fun assertPreserved(f: Fixture) {
        val after = checkNotNull(f.repository.account(f.account.key))
        assertEquals(f.account.status, after.status)
        assertEquals(f.account.nextAllowedAt, after.nextAllowedAt)
        assertEquals(f.account.transientRetry, after.transientRetry)
        assertEquals(f.account.relationshipStatus, after.relationshipStatus)
        assertEquals(listOf(1_000L), f.repository.history(f.account.key).map { it.observedAt })
        assertEquals(0L, f.repository.history(f.account.key).last().followers)
    }
    private suspend fun expectDatabaseFailure(block: suspend () -> Unit) {
        try { block(); fail("The original database failure must reach the caller") }
        catch (actual: Exception) { assertTrue("Expected SQLite failure, got ${actual.javaClass.name}", actual is SQLiteException) }
    }
    private suspend fun expectOriginalFailure(expected: Exception, block: suspend () -> Unit) {
        try { block(); fail("The original storage failure must reach the caller") }
        catch (actual: Exception) { assertSame(expected, actual) }
    }
    @Test fun sessionStorageFailureRestoresTheMetricStatusAndRetryState() = runBlocking {
        val f = fixture()
        try {
            val expected = GeneralSecurityException("synthetic session storage failure")
            val collector = StorageFailureCollector().apply { failure = expected }
            var publications = 0
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = { publications++ })
            expectOriginalFailure(expected) { sync.refresh(f.account.key) }
            assertPreserved(f); assertEquals(0, publications)
        } finally { f.database.close() }
    }
    @Test fun aFailedMetricCommitPreservesTheRecordAndAllowsALaterRetry() = runBlocking {
        val f = fixture()
        try {
            val collector = StorageFailureCollector()
            var publications = 0
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = { publications++ })
            // The real Room transaction aborts on the existing metric's primary key.
            expectDatabaseFailure { sync.refresh(f.account.key) }
            assertPreserved(f); assertEquals(0, publications)
            collector.metricAt = 2_000
            sync.refresh(f.account.key)
            assertEquals(SyncStatus.READY, f.repository.account(f.account.key)?.status)
            assertNull(f.repository.account(f.account.key)?.transientRetry)
            assertEquals(listOf(1_000L, 2_000L), f.repository.history(f.account.key).map { it.observedAt })
            assertEquals(1, publications)
        } finally { f.database.close() }
    }
    @Test fun storageFailureBeforeTheFirstListRestoresTheAbsentListStatus() = runBlocking {
        val f = fixture()
        try {
            val expected = GeneralSecurityException("synthetic list storage failure")
            val collector = StorageFailureCollector().apply { failure = expected }
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            expectOriginalFailure(expected) { sync.relationships(f.account.key) }
            assertPreserved(f); assertNull(f.repository.report(f.account.key))
        } finally { f.database.close() }
    }
    @Test fun aFailedListCommitPreservesTheCompletedReportAndAllowsALaterRetry() = runBlocking {
        val f = fixture(completedLists = true)
        try {
            val collector = StorageFailureCollector().apply { scanAt = 3 }
            f.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER qa_reject_scan_commit
                BEFORE INSERT ON scans WHEN NEW.finishedAt = 3
                BEGIN SELECT RAISE(ABORT, 'synthetic scan commit failure'); END""")
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            // History changes run first; the real SQLite insert then aborts the transaction.
            expectDatabaseFailure { sync.relationships(f.account.key) }
            assertPreserved(f); assertEquals(2L, f.repository.report(f.account.key)?.comparedAt)
            assertEquals(2L, f.database.dao().historyCursor(f.account.key)?.finishedAt)
            collector.scanAt = 4
            sync.relationships(f.account.key)
            assertEquals(SyncStatus.READY, f.repository.account(f.account.key)?.relationshipStatus)
            assertEquals(4L, f.repository.report(f.account.key)?.comparedAt)
        } finally { f.database.close() }
    }
    @Test fun aFailedErrorStatusWriteDoesNotLeaveTheMetricRefreshing() = runBlocking {
        val f = fixture()
        try {
            f.repository.updateStatus(f.account.key, SyncStatus.READY, 1_000)
            val before = checkNotNull(f.repository.account(f.account.key))
            // Fail the error handler's update, while allowing initial and restoration writes.
            f.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER qa_reject_offline
                BEFORE UPDATE ON accounts WHEN json_extract(NEW.json, '$.status') = 'OFFLINE'
                BEGIN SELECT RAISE(ABORT, 'synthetic status failure'); END""")
            val collector = StorageFailureCollector().apply { failure = CollectionFailure(SyncStatus.OFFLINE) }
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {})
            expectDatabaseFailure { sync.refresh(f.account.key) }
            assertEquals(before.status, f.repository.account(f.account.key)?.status)
            assertEquals(before.nextAllowedAt, f.repository.account(f.account.key)?.nextAllowedAt)
            assertEquals(before.transientRetry, f.repository.account(f.account.key)?.transientRetry)
            assertEquals(listOf(1_000L), f.repository.history(f.account.key).map { it.observedAt })
        } finally { f.database.close() }
    }
}
