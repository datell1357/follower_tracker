package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the production loop with an in-memory store and virtual time. No device, cookies or HTTP. */
class CountTrackingLoopExperimentTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private inner class Experiment(val mode: TrackingMode = TrackingMode.PREMIUM_WIDGET_TEST) {
        var elapsed = 0L
        var wallOffset = 0L
        var horizon = Long.MAX_VALUE
        val wall: Long get() = 1_700_000_000_000L + elapsed + wallOffset
        var cost = 100L
        var offlineStart = Long.MAX_VALUE
        var offlineEnd = Long.MAX_VALUE
        var offlineEvents = 0
        var networkWaits = 0
        var reads = 0
        var inFlight = 0
        var maxInFlight = 0
        var stopped: TrackingLoopStop? = null
        val rows = Provider.entries.associateWith { provider ->
            TrackingCount(Account(provider, "42", "fixture", profileUrl = "https://${provider.domain}/fixture/",
                connectedAt = 1, countTransport = CountTransport.PROFILE_BROWSER,
                capabilities = Capabilities(background = Capability.OBSERVED)), wall - 1)
        }.toMutableMap()
        val dispatches = Provider.entries.associateWith { mutableListOf<Long>() }
        val requests = Provider.entries.associateWith { mutableListOf<Long>() }
        val observations = Provider.entries.associateWith { mutableListOf<Long>() }
        val cycles = mutableListOf<TrackingCycle>()
        var behavior: (Provider, Int) -> CollectionFailure? = { _, _ -> null }
        var beforeRequest: (Provider) -> Unit = {}
        var afterWait: () -> Unit = {}

        private fun advance(millis: Long) {
            require(millis >= 0)
            if (millis >= horizon - elapsed) {
                elapsed = horizon
                throw CancellationException("End of virtual observation")
            }
            elapsed += millis
        }

        fun runFor(duration: Long) = runBlocking {
            horizon = elapsed + duration
            val loop = CountTrackingLoop(mode, { elapsed }, { wall },
                isOffline = { elapsed >= offlineStart && elapsed < offlineEnd },
                readCounts = { reads++; rows.values.toList() },
                refresh = { account ->
                    val provider = account.provider
                    dispatches.getValue(provider) += elapsed
                    if (RefreshPolicy.canRefresh(account, wall, background = true)) {
                        beforeRequest(provider)
                        requests.getValue(provider) += elapsed
                        inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
                        try {
                            advance(cost)
                            val failure = behavior(provider, requests.getValue(provider).size)
                            if (failure != null) {
                                rows[provider] = rows.getValue(provider).copy(account = account.copy(status = failure.status,
                                    nextAllowedAt = RefreshPolicy.serviceRetryAt(failure, wall),
                                    transientRetry = if (failure.status in setOf(SyncStatus.OFFLINE, SyncStatus.FORMAT_CHANGED))
                                        RefreshPolicy.nextTransientRetry(account, wall, failure.status) else null))
                            } else {
                                rows[provider] = TrackingCount(account.copy(status = SyncStatus.READY,
                                    nextAllowedAt = null, transientRetry = null), wall)
                                observations.getValue(provider) += elapsed
                            }
                        } finally { inFlight-- }
                    }
                }, onCycle = { cycles += it }, onOffline = { offlineEvents++ },
                awaitNetwork = { remaining ->
                    networkWaits++
                    advance(minOf(offlineEnd - elapsed, remaining ?: Long.MAX_VALUE))
                }, wait = { advance(it); afterWait() })
            val job = launch(start = CoroutineStart.UNDISPATCHED) { stopped = loop.run() }
            job.join()
        }
    }

    @Test fun allFiveProvidersRunFor24VirtualHoursWithoutTheAppOrDuplicateCycles() {
        val e = Experiment()
        e.runFor(24 * hour)
        for (provider in Provider.entries) {
            assertEquals(1_440, e.observations.getValue(provider).size)
            assertTrue(e.observations.getValue(provider).zipWithNext().all { (a,b) -> b-a == minute })
        }
        assertEquals(1_440, e.cycles.size)
        assertEquals(1, e.maxInFlight)
        assertNull(e.stopped)
    }

    @Test fun a72HourLoopHas21600FreshReadsAndNoSixHourReset() {
        val e = Experiment()
        e.runFor(72 * hour)
        assertEquals(21_600, e.observations.values.sumOf { it.size })
        assertEquals(4_320, e.cycles.size)
        assertTrue(e.cycles.all { it.freshKeys.size == 5 && it.freshAt != null })
        assertEquals(1, e.maxInFlight)
    }

    @Test fun theOrdinaryModeStillStopsAtItsActualLoopDeadline() {
        val e = Experiment(TrackingMode.FAST)
        e.runFor(24 * hour)
        assertEquals(TrackingLoopStop.TIME_LIMIT, e.stopped)
        assertEquals(6 * hour - 30_000, e.elapsed)
        assertEquals(360, e.cycles.size)
    }

    @Test fun offlineTimeWaitsForOneRecoveryEventWithoutPollingOrInventingObservations() {
        val e = Experiment().apply { offlineStart = 30 * minute; offlineEnd = 2 * hour }
        e.runFor(24 * hour)
        assertEquals(1, e.networkWaits)
        assertEquals(1, e.offlineEvents)
        for (provider in Provider.entries) {
            assertEquals(1_350, e.observations.getValue(provider).size)
            assertTrue(e.requests.getValue(provider).none { it in e.offlineStart until e.offlineEnd })
        }
    }

    @Test fun anOfflineOrdinarySessionCannotWaitPastItsSixHourLimit() {
        val e = Experiment(TrackingMode.FAST).apply { offlineStart = 0; offlineEnd = 24 * hour }
        e.runFor(48 * hour)
        assertEquals(TrackingLoopStop.TIME_LIMIT, e.stopped)
        assertEquals(6 * hour - 30_000, e.elapsed)
        assertEquals(1, e.networkWaits)
        assertTrue(e.requests.values.all { it.isEmpty() })
    }

    @Test fun eachProviders429HonorsItsDeadlineWhileTheOtherFourKeepTheirMinuteCadence() {
        for (limited in Provider.entries) {
            val e = Experiment().apply {
                cost = 0
                behavior = { provider, attempt -> if (provider == limited && attempt == 1)
                    CollectionFailure(SyncStatus.RATE_LIMITED, 900) else null }
            }
            e.runFor(24 * hour)
            assertEquals(1_426, e.requests.getValue(limited).size)
            assertEquals(15 * minute, e.requests.getValue(limited)[1])
            assertEquals("Not-due accounts must not be dispatched to storage/collection", 1_426,
                e.dispatches.getValue(limited).size)
            for (other in Provider.entries.filter { it != limited }) assertEquals(1_440, e.observations.getValue(other).size)
            assertTrue(e.cycles.first().message.contains("요청 제한"))
            assertNull(e.rows.getValue(limited).account.nextAllowedAt)
        }
    }

    @Test fun aBlockedAccountCannotCauseMinutePollingWhileTheOnlyRunnableAccountIsRateLimited() {
        val e = Experiment().apply {
            for (provider in Provider.entries) rows[provider] = rows.getValue(provider).let { row ->
                row.copy(account = row.account.copy(status = if (provider == Provider.X) SyncStatus.RATE_LIMITED else SyncStatus.REAUTH_REQUIRED,
                    nextAllowedAt = if (provider == Provider.X) wall + 10 * minute else null))
            }
        }
        e.runFor(9 * minute)
        assertEquals(0, e.dispatches.values.sumOf { it.size })
        assertEquals("Wait once for the actual deadline, rather than repeatedly reading unchanged storage", 2, e.reads)
        assertEquals(1, e.cycles.size)
        assertNull(e.cycles.single().freshAt)
    }

    @Test fun transientFailuresUsePersistedExponentialBackoffThenResetOnSuccess() {
        val e = Experiment().apply {
            cost = 0
            behavior = { provider, attempt -> if (provider == Provider.TIKTOK && attempt <= 6)
                CollectionFailure(SyncStatus.OFFLINE) else null }
        }
        e.runFor(3 * hour)
        assertEquals(listOf(0L,1L,3L,7L,15L,30L,45L).map { it * minute }, e.requests.getValue(Provider.TIKTOK).take(7))
        assertTrue(e.rows.getValue(Provider.TIKTOK).account.status == SyncStatus.READY)
        assertNull(e.rows.getValue(Provider.TIKTOK).account.transientRetry)
        for (other in Provider.entries.filter { it != Provider.TIKTOK }) assertEquals(180, e.observations.getValue(other).size)
    }

    @Test fun aFormatFailureKeepsItsLastObservationUntilThePersistedRetryActuallySucceeds() {
        val e = Experiment().apply {
            cost = 0
            behavior = { provider, attempt -> if (provider == Provider.FACEBOOK && attempt == 1)
                CollectionFailure(SyncStatus.FORMAT_CHANGED) else null }
        }
        e.runFor(14 * minute)
        assertEquals(1, e.requests.getValue(Provider.FACEBOOK).size)
        assertTrue(e.observations.getValue(Provider.FACEBOOK).isEmpty())
        assertEquals(1_699_999_999_999L, e.rows.getValue(Provider.FACEBOOK).observedAt)
        assertTrue(e.cycles.none { e.rows.getValue(Provider.FACEBOOK).account.key in it.freshKeys })
        e.runFor(2 * minute)
        assertEquals(1, e.observations.getValue(Provider.FACEBOOK).size)
        assertNull(e.rows.getValue(Provider.FACEBOOK).account.transientRetry)
    }

    @Test fun eachExpiredSessionStopsOnlyItsOwnRequestsAndNeverSavesAReplacementCount() {
        for (expired in Provider.entries) {
            val e = Experiment().apply {
                behavior = { provider, _ -> if (provider == expired) CollectionFailure(SyncStatus.REAUTH_REQUIRED) else null }
            }
            e.runFor(24 * hour)
            assertEquals(1, e.requests.getValue(expired).size)
            assertTrue(e.observations.getValue(expired).isEmpty())
            for (other in Provider.entries.filter { it != expired }) assertEquals(1_440, e.observations.getValue(other).size)
        }
    }

    @Test fun aCancelledCollectorStopsLaterProvidersAndCannotPublishAFakeCycle() {
        val e = Experiment().apply { beforeRequest = { throw CancellationException("User stopped") } }
        e.runFor(hour)
        assertTrue(e.cycles.isEmpty())
        assertTrue(e.requests.values.all { it.isEmpty() })
        assertTrue(e.observations.values.all { it.isEmpty() })
        assertEquals(0, e.inFlight)
    }

    @Test fun slowCollectionNeverOverlapsOrQueuesCatchUpBursts() {
        val e = Experiment().apply { cost = 20_000 }
        e.runFor(hour)
        assertEquals(1, e.maxInFlight)
        assertTrue(e.requests.values.all { starts -> starts.zipWithNext().all { (a,b) -> b-a >= 101_000 } })
    }

    @Test fun wallClockCorrectionsCannotEndPremiumTrackingOrChangeItsMonotonicCadence() {
        val e = Experiment().apply { afterWait = { wallOffset = if (elapsed >= hour) -12 * hour else 0 } }
        e.runFor(24 * hour)
        assertEquals(1_440, e.requests.getValue(Provider.INSTAGRAM).size)
        assertTrue(e.requests.getValue(Provider.INSTAGRAM).zipWithNext().all { (a,b) -> b-a == minute })
    }

    @Test fun eachUnexpectedCollectorExceptionPreservesTheOtherFourAndCanRecoverNextCycle() {
        for (broken in Provider.entries) {
            var thrown = false
            val e = Experiment().apply {
                cost = 0
                beforeRequest = { if (it == broken && !thrown) { thrown = true; throw IllegalStateException("Synthetic failure") } }
            }
            e.runFor(2 * minute)
            assertEquals(setOf(broken), e.cycles.first().failedProviders)
            assertTrue(e.cycles.first().message.contains("오류"))
            assertEquals(1, e.observations.getValue(broken).size)
            for (other in Provider.entries.filter { it != broken }) assertEquals(2, e.observations.getValue(other).size)
        }
    }

    @Test fun anEmptyConnectionSetStopsWithoutInventingASuccessfulCycle() {
        val e = Experiment().apply { rows.clear() }
        e.runFor(hour)
        assertEquals(TrackingLoopStop.NO_ACCOUNTS, e.stopped)
        assertTrue(e.cycles.isEmpty())
        assertTrue(e.requests.values.all { it.isEmpty() })
    }

    @Test fun allExpiredConnectionsStopRatherThanLoopOrRestartThemselves() {
        val e = Experiment().apply {
            for (p in Provider.entries) rows[p] = rows.getValue(p).let { it.copy(account = it.account.copy(status = SyncStatus.REAUTH_REQUIRED)) }
        }
        e.runFor(hour)
        assertEquals(TrackingLoopStop.CONNECTION_REQUIRED, e.stopped)
        assertTrue(e.cycles.isEmpty())
        assertTrue(e.requests.values.all { it.isEmpty() })
    }
}
