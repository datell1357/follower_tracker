package dev.datell.followertracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageRuntimeTest {
    private fun account(id: String = "42") = Account(Provider.INSTAGRAM, id, "fixture_owner", profileUrl = "https://www.instagram.com/fixture_owner/", connectedAt = 1)
    private fun metric(account: Account, at: Long = 1) = MetricSnapshot(account.key, at, 3, 2, source = "synthetic-runtime-fixture")
    @Test fun simultaneousFirstUseAcrossCipherInstancesPreservesEveryRecord() {
        val alias = "followertracker.test.${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val payload = "synthetic concurrent fixture".toByteArray()
        try {
            val records = (1..8).map { executor.submit<ByteArray> { start.await(); DataCipher(alias).seal(payload) } }
            start.countDown()
            val cipher = DataCipher(alias)
            records.forEach { assertArrayEquals(payload, cipher.open(it.get(30, TimeUnit.SECONDS))) }
        } finally {
            executor.shutdownNow()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    @Test fun encryptionUsesFreshNonceAndRejectsTampering() {
        val cipher = DataCipher()
        val payload = "synthetic relationship fixture".toByteArray()
        val first = cipher.seal(payload)
        val second = cipher.seal(payload)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload, cipher.open(first))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        try { cipher.open(first); fail("Tampered data must fail authentication") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun requestCompletingAfterDisconnectCannotRecreateAccount() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val account = account()
            repository.saveObservation(account, metric(account))
            repository.disconnect(account.key)
            repository.saveObservation(account, metric(account, 2), requireExisting = true)
            assertTrue(repository.accounts().isEmpty())
            assertTrue(repository.history(account.key).isEmpty())
        } finally { database.close() }
    }
    @Test fun differentLoggedInAccountDoesNotReplaceExistingHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val original = account()
            val different = account("99")
            repository.saveObservation(original, metric(original))
            try { repository.saveObservation(different, metric(different)); fail("Identity mismatch must abort") }
            catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
            assertEquals(listOf(original.key), repository.accounts().map { it.key })
            assertEquals(1, repository.history(original.key).size)
        } finally { database.close() }
    }
    @Test fun lateRequestCannotOverwriteReconnectedAccountWithTheSameIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val original = account()
            repository.saveObservation(original, metric(original))
            repository.disconnect(original.key)
            val reconnected = original.copy(connectedAt = 2, status = SyncStatus.READY)
            repository.saveObservation(reconnected, metric(reconnected, 20))
            repository.saveObservation(original, metric(original, 99), requireExisting = true)
            assertEquals(listOf(20L), repository.history(original.key).map { it.observedAt })
            assertEquals(2L, repository.account(original.key)?.connectedAt)
            repository.updateStatus(original.key, SyncStatus.REAUTH_REQUIRED, 99, expectedConnectedAt = original.connectedAt)
            repository.updateListStatus(original.key, SyncStatus.LIST_INCOMPLETE, expectedConnectedAt = original.connectedAt)
            fun scan(direction: Direction) = RelationshipSnapshot(original.key, direction, 10, 20,
                listOf(Member("member_1", "fixture_member")), true, true, "synthetic-complete")
            repository.saveScans(scan(Direction.FOLLOWERS), scan(Direction.FOLLOWING), expectedConnectedAt = original.connectedAt)
            assertEquals(SyncStatus.READY, repository.account(original.key)?.status)
            assertNull(repository.account(original.key)?.relationshipStatus)
            assertNull(repository.report(original.key))
            repository.updateStatus(reconnected.key, SyncStatus.REFRESHING, 100, expectedConnectedAt = reconnected.connectedAt)
            repository.saveScans(scan(Direction.FOLLOWERS), scan(Direction.FOLLOWING), expectedConnectedAt = reconnected.connectedAt)
            assertEquals(SyncStatus.REFRESHING, repository.account(original.key)?.status)
            assertEquals(1, repository.report(original.key)?.mutual?.size)
        } finally { database.close() }
    }
    @Test fun failedScanPreservesLastCompletedRelationshipReport() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val owner = account()
            repository.saveObservation(owner, metric(owner))
            fun scan(direction: Direction, complete: Boolean = true) = RelationshipSnapshot(owner.key, direction, 10, 20,
                listOf(Member("member_1", "fixture_member")), complete, true, "synthetic-complete")
            repository.saveScans(scan(Direction.FOLLOWERS), scan(Direction.FOLLOWING))
            try { repository.saveScans(scan(Direction.FOLLOWERS, false), scan(Direction.FOLLOWING)); fail("Partial list cannot be compared") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, repository.report(owner.key)?.mutual?.size)
            repository.updateListStatus(owner.key, SyncStatus.LIST_INCOMPLETE)
            assertEquals(1, repository.report(owner.key)?.mutual?.size)
        } finally { database.close() }
    }
}
