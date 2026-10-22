package dev.datell.followertracker

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.diagnostics.*
import dev.datell.followertracker.sync.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only the isolated validation package, UUID databases and synthetic collectors. No SNS or cookies. */
@RunWith(AndroidJUnit4::class)
class DiagnosticLoggingRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "dev.datell.followertracker.loginvalidation") { "An isolated package is required" }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private data class Fixture(val database: TrackerDatabase, val repository: TrackerRepository, val account: Account)
    private suspend fun fixture(): Fixture {
        val context = context
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "diagnostic-${UUID.randomUUID()}.db").build()
        val repository = TrackerRepository(database, DataCipher())
        val account = Account(Provider.FACEBOOK, "synthetic-private-id", "synthetic-private-name",
            profileUrl = "https://www.facebook.com/synthetic-private-profile", connectedAt = 1,
            countTransport = CountTransport.PROFILE_BROWSER)
        repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 987_654_321, null, source = "synthetic-baseline"))
        return Fixture(database, repository, account)
    }
    private class Collector : SessionCollecting {
        var failure: Exception? = CollectionFailure(SyncStatus.FORMAT_CHANGED)
        var at = 2_000L
        var attempt: Long? = null
        override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
            attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
            failure?.let { throw it }
            val account = checkNotNull(expected).copy(status = SyncStatus.READY)
            return account to MetricSnapshot(account.key, at, 0, null, source = "synthetic-diagnostic")
        }
        override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> = error("Not used")
    }
    private suspend fun records(): List<DiagnosticRecord> {
        withTimeout(5_000) { DebugDiagnostics.flush() }
        return File(context.noBackupFilesDir, "diagnostics").listFiles().orEmpty()
            .filter { it.name.matches(Regex("collection-[0-3]\\.jsonl")) }
            .mapNotNull { file ->
                val complete = file.readText().substringBeforeLast('\n', "").lines()
                if (complete.isEmpty() || complete.first().isBlank()) null else {
                    val generation = json.parseToJsonElement(complete.first()).jsonObject.getValue("generation").jsonPrimitive.long
                    generation to complete.drop(1).filter(String::isNotBlank).map { json.decodeFromString<DiagnosticRecord>(it) }
                }
            }.sortedBy { it.first }.flatMap { it.second }
    }
    private fun forAttempt(records: List<DiagnosticRecord>, attempt: Long?): List<DiagnosticEntry> {
        checkNotNull(attempt)
        val start = records.last { it.entry.kind == DiagnosticKind.ATTEMPT_START && it.entry.attempt == attempt }
        return records.filter { it.run == start.run && it.entry.attempt == start.entry.attempt }.map { it.entry }
    }

    @Test fun aFailurePersistsItsReasonAndRetryTimeAndOnlyACommittedRecoveryBecomesSuccess() = runBlocking {
        val f = fixture()
        try {
            val collector = Collector()
            val sync = SyncCoordinator(context, f.repository, collector, publishWidgets = {}, isDefinitelyOffline = { false })
            sync.refresh(f.account.key, background = true)
            val stored = checkNotNull(f.repository.account(f.account.key))
            val failed = forAttempt(records(), collector.attempt)
            assertTrue(failed.any { it.kind == DiagnosticKind.STATUS_SAVED && it.status == SyncStatus.FORMAT_CHANGED &&
                it.localRetryAt == stored.transientRetry?.nextAttemptAt && it.failureCount == 1 })
            assertFalse(failed.any { it.kind == DiagnosticKind.OBSERVATION_STORED })
            sync.refresh(f.account.key, background = true)
            assertTrue(records().any { it.entry.kind == DiagnosticKind.ATTEMPT_SKIPPED && it.entry.stage == DiagnosticStage.LOCAL_BACKOFF })
            f.repository.updateStatus(f.account.key, stored.status, System.currentTimeMillis(),
                transientRetry = checkNotNull(stored.transientRetry).copy(nextAttemptAt = 0))
            collector.failure = null
            sync.refresh(f.account.key, background = true)
            val all = records()
            val recovered = forAttempt(all, collector.attempt)
            assertEquals(listOf(2_000L), recovered.filter { it.kind == DiagnosticKind.OBSERVATION_STORED }.map { it.observedAt })
            assertTrue(recovered.any { it.kind == DiagnosticKind.ATTEMPT_FINISH })
            assertEquals(listOf(1_000L, 2_000L), f.repository.history(f.account.key).map { it.observedAt })
            val encoded = all.joinToString { Json.encodeToString(DiagnosticRecord.serializer(), it) }
            assertFalse(encoded.contains("synthetic-private"))
            assertFalse(encoded.contains("987654321"))
            assertEquals(context.noBackupFilesDir.canonicalPath,
                File(context.noBackupFilesDir, "diagnostics").canonicalFile.parent)
        } finally { f.database.close() }
    }

    @Test fun anUnexpectedExceptionKeepsItsOriginalBehaviorButNeverItsMessageInTheLog() = runBlocking {
        val f = fixture()
        try {
            val expected = IllegalStateException("Cookie: synthetic-hidden-token; private-profile")
            val collector = Collector().apply { failure = expected }
            try {
                SyncCoordinator(context, f.repository, collector, publishWidgets = {}, isDefinitelyOffline = { false }).refresh(f.account.key)
                fail("The original exception must propagate")
            } catch (actual: IllegalStateException) { assertSame(expected, actual) }
            val all = records()
            assertTrue(forAttempt(all, collector.attempt).any { it.kind == DiagnosticKind.ATTEMPT_FAILURE })
            assertFalse(forAttempt(all, collector.attempt).any { it.kind == DiagnosticKind.OBSERVATION_STORED })
            assertFalse(all.joinToString { Json.encodeToString(DiagnosticRecord.serializer(), it) }.contains("synthetic-hidden-token"))
            assertEquals(listOf(1_000L), f.repository.history(f.account.key).map { it.observedAt })
        } finally { f.database.close() }
    }

    @Test fun aDatabaseRollbackNeverWritesAStoredObservationEvent() = runBlocking {
        val f = fixture()
        try {
            val collector = Collector().apply { failure = null; at = 1_000 }
            try {
                SyncCoordinator(context, f.repository, collector, publishWidgets = {}, isDefinitelyOffline = { false }).refresh(f.account.key)
                fail("The duplicate primary key must fail")
            } catch (_: SQLiteException) { }
            val entries = forAttempt(records(), collector.attempt)
            assertTrue(entries.any { it.kind == DiagnosticKind.ATTEMPT_FAILURE })
            assertFalse(entries.any { it.kind == DiagnosticKind.OBSERVATION_STORED })
            assertEquals(987_654_321L, f.repository.history(f.account.key).single().followers)
        } finally { f.database.close() }
    }
}
