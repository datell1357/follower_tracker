package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionCountRecoveryTest {
    private val account = Account(Provider.INSTAGRAM, "42", "fixture", profileUrl = "https://www.instagram.com/fixture/", connectedAt = 1)
    private fun observed(account: Account = this.account) = account.copy(countTransport = CountTransport.PROFILE_BROWSER) to
        MetricSnapshot(account.key, 100, 12, null, source = "fixture")

    @Test fun aValidSessionRecoversOnceAndPromotesTheActuallySuccessfulBrowserRoute() = runBlocking {
        var calls = 0
        val result = collectWithSessionRecovery(account, 100, { true },
            primary = { throw CollectionFailure(SyncStatus.FORMAT_CHANGED) }, browser = { calls++; observed(it) })
        assertEquals(1, calls); assertEquals(CountTransport.PROFILE_BROWSER, result.first.countTransport)
        assertEquals(account.key, result.second.accountKey)
    }
    @Test fun authenticationChallengesServerCooldownsAndOfflineDoNotTriggerExtraRequests() = runBlocking {
        for (status in listOf(SyncStatus.REAUTH_REQUIRED, SyncStatus.CHECK_REQUIRED, SyncStatus.RATE_LIMITED, SyncStatus.OFFLINE)) {
            var calls = 0
            try {
                collectWithSessionRecovery(account, 100, { true }, primary = { throw CollectionFailure(status, 300) },
                    browser = { calls++; observed(it) }); fail(status.name)
            } catch (failure: CollectionFailure) { assertEquals(status, failure.status); assertEquals(300L, failure.retryAfterSeconds) }
            assertEquals(0, calls)
        }
    }
    @Test fun loggedOutReplacedAndCoolingDownSessionsAreNotResurrected() = runBlocking {
        for (row in listOf(account.copy(loggedOut = true), account.copy(nextAllowedAt = 101))) {
            var called = false
            try { collectWithSessionRecovery(row, 100, { true }, primary = { throw CollectionFailure(SyncStatus.FORMAT_CHANGED) },
                browser = { called = true; observed(it) }); fail() } catch (_: CollectionFailure) { }
            assertFalse(called)
        }
        var called = false
        try { collectWithSessionRecovery(account, 100, { false }, primary = { throw CollectionFailure(SyncStatus.FORMAT_CHANGED) },
            browser = { called = true; observed(it) }); fail() } catch (_: CollectionFailure) { }
        assertFalse(called)
    }
    @Test fun logoutDuringRecoveryAndAnotherOwnersObservationCannotBeSaved() = runBlocking {
        var current = true
        try { collectWithSessionRecovery(account, 100, { current }, primary = { throw CollectionFailure(SyncStatus.FORMAT_CHANGED) },
            browser = { current = false; observed(it) }); fail() }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
        try { collectWithSessionRecovery(account, 100, { true }, primary = { throw CollectionFailure(SyncStatus.FORMAT_CHANGED) },
            browser = { observed(it.copy(stableId = "99")) }); fail() }
        catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
    }
    @Test fun successfulPrimaryAndCancellationNeverRunRecovery() = runBlocking {
        var called = false
        collectWithSessionRecovery(account, 100, { true }, primary = { observed() }, browser = { called = true; observed(it) })
        assertFalse(called)
        try { collectWithSessionRecovery(account, 100, { true }, primary = { throw CancellationException("fixture") },
            browser = { called = true; observed(it) }); fail() } catch (_: CancellationException) { }
        assertFalse(called)
    }
}
