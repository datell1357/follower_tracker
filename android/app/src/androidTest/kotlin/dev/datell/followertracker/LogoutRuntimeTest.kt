package dev.datell.followertracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only an in-memory Room database and a synthetic session fence. Never clears real SNS cookies. */
@RunWith(AndroidJUnit4::class)
class LogoutRuntimeTest {
    @Test fun explicitLogoutRemovesConnectedCardsAndWidgetsWhileKeepingHistoryForLogin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val repository = TrackerRepository(db, DataCipher("logout-visible-qa-${UUID.randomUUID()}"))
        try {
            val personal = Account(Provider.FACEBOOK, "42", "fixture.owner",
                profileUrl = "https://www.facebook.com/profile.php?id=42", connectedAt = 1)
            val page = Account(Provider.FACEBOOK, "99", "fixture.page", profileUrl = "https://www.facebook.com/profile.php?id=99",
                connectedAt = 2, accountType = AccountType.PAGE, sessionOwnerId = "42")
            val other = Account(Provider.REDDIT, "fixture", "sample", profileUrl = "https://www.reddit.com/user/sample/", connectedAt = 3)
            for (owner in listOf(personal, page, other)) repository.saveObservation(owner,
                MetricSnapshot(owner.key, 1_000, 95, 74, source = "synthetic-logout-baseline"))
            repository.updateStatus(personal.key, SyncStatus.REAUTH_REQUIRED, 1_500)
            assertEquals(3, repository.accounts().size) // Expiry still offers reconnect; explicit logout hides it.
            repository.markLoggedOut(Provider.FACEBOOK)
            assertEquals(listOf(other.key), repository.accounts().map { it.key })
            assertEquals(listOf(other.key), repository.accounts.first().map { it.key })
            assertEquals(listOf(other.key), repository.overviews().map { it.account.key })
            assertEquals(listOf(other.key), repository.widgetOverviews().map { it.account.key })
            assertTrue(repository.widgetOverviews(personal.key).isEmpty())
            assertTrue(repository.widgetOverviews(page.key).isEmpty())
            for (owner in listOf(personal, page)) {
                assertEquals(owner.connectedAt, repository.account(owner.key)?.connectedAt)
                assertEquals(listOf(1_000L), repository.history(owner.key).map { it.observedAt })
            }
            // A collector queued before logout cannot make the retained connection visible again.
            repository.saveObservation(personal, MetricSnapshot(personal.key, 2_000, 100, 74,
                source = "synthetic-late-result"), requireExisting = true)
            assertTrue(repository.widgetOverviews(personal.key).isEmpty())
            assertEquals(1, repository.history(personal.key).size)
            // Only a fresh, successful login restores its matching key, including exact zero.
            repository.saveObservation(personal.copy(connectedAt = 3_000), MetricSnapshot(personal.key, 3_000, 0, 74,
                source = "synthetic-relogin"))
            assertEquals(setOf(personal.key, other.key), repository.accounts().map { it.key }.toSet())
            assertEquals(1L, repository.account(personal.key)?.connectedAt)
            assertEquals(listOf(95L, 0L), repository.history(personal.key).map { it.followers })
            assertEquals(0L, repository.widgetOverviews(personal.key).single().latest?.followers)
            assertTrue(repository.widgetOverviews(page.key).isEmpty())
        } finally { db.close() }
    }

    @Test fun anotherPersonalLoginAfterLogoutPreservesBothHistoriesAndActiveIdentityExclusion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val repository = TrackerRepository(db, DataCipher("logout-switch-qa-${UUID.randomUUID()}"))
        try {
            val original = Account(Provider.INSTAGRAM, "42", "fixture.owner",
                profileUrl = "https://www.instagram.com/fixture.owner/", connectedAt = 1)
            val replacement = original.copy(stableId = "77", username = "fixture.other", connectedAt = 2)
            fun metric(owner: Account, at: Long) = MetricSnapshot(owner.key, at, 0, 0, source = "synthetic-login-switch")
            repository.saveObservation(original, metric(original, 1_000))
            repository.markLoggedOut(Provider.INSTAGRAM)
            repository.saveObservation(replacement, metric(replacement, 2_000))
            assertEquals(listOf(replacement.key), repository.accounts().map { it.key })
            assertTrue(repository.account(original.key)?.loggedOut == true)
            try {
                repository.saveObservation(original, metric(original, 3_000))
                fail("Only one active personal identity is allowed for an SNS")
            } catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
            assertEquals(1, repository.history(original.key).size)
            repository.markLoggedOut(Provider.INSTAGRAM)
            repository.saveObservation(original.copy(connectedAt = 4_000), metric(original, 4_000))
            assertEquals(listOf(original.key), repository.accounts().map { it.key })
            assertEquals(1L, repository.account(original.key)?.connectedAt)
            assertEquals(2, repository.history(original.key).size)
            assertEquals(1, repository.history(replacement.key).size)
            assertTrue(repository.account(replacement.key)?.loggedOut == true)
        } finally { db.close() }
    }

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
