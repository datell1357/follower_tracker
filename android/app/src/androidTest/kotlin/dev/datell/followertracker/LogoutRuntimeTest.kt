package dev.datell.followertracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only an in-memory Room database and a synthetic session fence. Never clears real SNS cookies. */
@RunWith(AndroidJUnit4::class)
class LogoutRuntimeTest {
    @Test fun cancellingOnlyTheRevokedSessionKeepsTheBatchAndOtherProvidersRunning() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val repository = TrackerRepository(db, DataCipher("logout-cancel-qa-${UUID.randomUUID()}"))
        try {
            val accounts = listOf(Provider.INSTAGRAM, Provider.REDDIT).map {
                Account(it, "fixture", "sample", profileUrl = "https://${it.domain}/sample", connectedAt = 1)
            }
            accounts.forEach { repository.saveObservation(it, MetricSnapshot(it.key, 1_000, 95, 74, source = "synthetic-logout-baseline")) }
            val entered = CompletableDeferred<Job>()
            var authorized = true
            val collector = object : SessionCollecting {
                override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
                    if (provider == Provider.INSTAGRAM) withContext(Dispatchers.Default) {
                        entered.complete(currentCoroutineContext()[Job]!!); awaitCancellation()
                    }
                    return expected!! to MetricSnapshot(expected.key, 2_000, 96, 74, source = "synthetic-other-provider")
                }
                override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> = error("No lists")
            }
            val sync = SyncCoordinator(context, repository, collector, publishWidgets = {},
                sessionGuard = { provider -> { provider != Provider.INSTAGRAM || authorized } })
            val batch = launch { accounts.forEach { sync.refresh(it.key) } }
            val active = entered.await(); authorized = false
            repository.markLoggedOut(Provider.INSTAGRAM); active.cancel(); batch.join()
            assertFalse(batch.isCancelled)
            assertEquals(SyncStatus.REAUTH_REQUIRED, repository.account(accounts.first().key)?.status)
            assertEquals(1, repository.history(accounts.first().key).size)
            assertEquals(2, repository.history(accounts.last().key).size)
        } finally { db.close() }
    }

    @Test fun logoutPreservesAllHistoryAndRejectsAnAlreadyRunningCollectorsLateSuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val repository = TrackerRepository(db, DataCipher("logout-qa-${UUID.randomUUID()}"))
        try {
            fun account(provider: Provider) = Account(provider, "fixture", "sample", profileUrl = "https://${provider.domain}/sample", connectedAt = 1)
            val instagram = account(Provider.INSTAGRAM); val other = account(Provider.REDDIT)
            for (owner in listOf(instagram, other)) repository.saveObservation(owner,
                MetricSnapshot(owner.key, 1_000, 95, 74, source = "synthetic-logout-baseline"))
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            var authorized = true
            val collector = object : SessionCollecting {
                override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
                    entered.complete(Unit); finish.await()
                    return expected!!.copy(status = SyncStatus.READY) to MetricSnapshot(expected.key, 2_000, 100, 74, source = "synthetic-late-result")
                }
                override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> = error("No relationship request")
            }
            val sync = SyncCoordinator(context, repository, collector, publishWidgets = {}, sessionGuard = { { authorized } })
            val pending = launch { sync.refresh(instagram.key) }
            entered.await(); authorized = false; repository.markLoggedOut(Provider.INSTAGRAM)
            finish.complete(Unit); pending.join()
            assertEquals(SyncStatus.REAUTH_REQUIRED, repository.account(instagram.key)?.status)
            assertEquals(listOf(1_000L), repository.history(instagram.key).map { it.observedAt })
            assertEquals(95L, repository.history(instagram.key).last().followers)
            assertEquals(SyncStatus.READY, repository.account(other.key)?.status)
            assertEquals(1, repository.history(other.key).size)
            assertEquals(1L, repository.account(instagram.key)?.connectedAt)
        } finally { db.close() }
    }
}
