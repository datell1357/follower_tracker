package dev.datell.followertracker.sync

import dev.datell.followertracker.core.Account
import dev.datell.followertracker.core.Provider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CountRefreshBatchTest {
    private val accounts = listOf(Provider.FACEBOOK, Provider.INSTAGRAM, Provider.X, Provider.TIKTOK, Provider.REDDIT)
        .map { Account(it, "42", "fixture", profileUrl = "https://${it.domain}/fixture/", connectedAt = 1) }

    @Test fun aFailureCannotPreventTheOtherFourProvidersFromCollecting() = runBlocking {
        val attempted = mutableListOf<Provider>()
        val saved = mutableSetOf<Provider>()
        val failures = refreshCountBatch(accounts) {
            attempted += it.provider
            if (it.provider == Provider.FACEBOOK) throw IllegalStateException("synthetic collector failure")
            saved += it.provider
        }
        assertEquals(accounts.map { it.provider }, attempted)
        assertEquals(setOf(Provider.FACEBOOK), failures)
        assertEquals(Provider.entries.toSet() - Provider.FACEBOOK, saved)
    }

    @Test fun theNextCycleRetriesTheFailedAccountAndReadsEveryAccountAgain() = runBlocking {
        val observations = mutableMapOf<Provider, MutableList<Int>>()
        for (cycle in 1..3) {
            val failures = refreshCountBatch(accounts) {
                if (cycle == 1 && it.provider == Provider.FACEBOOK) throw IllegalStateException("synthetic first cycle failure")
                observations.getOrPut(it.provider) { mutableListOf() } += cycle
            }
            assertEquals(if (cycle == 1) setOf(Provider.FACEBOOK) else emptySet<Provider>(), failures)
        }
        for (provider in Provider.entries) assertEquals(
            if (provider == Provider.FACEBOOK) listOf(2, 3) else listOf(1, 2, 3), observations[provider])
    }

    @Test fun multipleFailuresAreReportedAndLaterAccountsStillRun() = runBlocking {
        val attempted = mutableListOf<Provider>()
        val failures = refreshCountBatch(accounts) {
            attempted += it.provider
            if (it.provider in setOf(Provider.FACEBOOK, Provider.X)) throw IllegalArgumentException("synthetic")
        }
        assertEquals(accounts.map { it.provider }, attempted)
        assertEquals(setOf(Provider.FACEBOOK, Provider.X), failures)
    }

    @Test fun cancellationStopsTheBatchInsteadOfBecomingAnAccountFailure() = runBlocking {
        val attempted = mutableListOf<Provider>()
        try {
            refreshCountBatch(accounts) {
                attempted += it.provider
                if (it.provider == Provider.INSTAGRAM) throw CancellationException("synthetic cancellation")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(Provider.FACEBOOK, Provider.INSTAGRAM), attempted)
        }
    }

    @Test fun aCancelledJobCannotStartAnotherAccountEvenIfTheCollectorReturnsNormally() = runBlocking {
        val attempted = mutableListOf<Provider>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshCountBatch(accounts) {
                attempted += it.provider
                currentCoroutineContext().cancel()
            }
        }
        job.join()
        assertEquals(listOf(Provider.FACEBOOK), attempted)
    }

    @Test fun fatalErrorsAreNotHiddenAsRecoverableFailures() = runBlocking {
        val attempted = mutableListOf<Provider>()
        var escaped = false
        try {
            refreshCountBatch(accounts) { attempted += it.provider; throw AssertionError("synthetic fatal error") }
        } catch (_: AssertionError) { escaped = true }
        assertTrue("Fatal errors must propagate", escaped)
        assertEquals(listOf(Provider.FACEBOOK), attempted)
    }
}
