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
        var earlyWakeEvery: Long? = null

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
                }, wait = { advance(minOf(it, earlyWakeEvery ?: it)); afterWait() })
            val job = launch(start = CoroutineStart.UNDISPATCHED) { stopped = loop.run() }
            job.join()
        }
    }

    @Test fun foregroundWakeupsCannotTurnOneMinuteTrackingIntoRepeatedRequests() {
        val e = Experiment().apply { cost = 0; earlyWakeEvery = 1_000 }
        e.runFor(3 * minute)
        for (provider in Provider.entries) {
            assertEquals(listOf(0L, minute, 2 * minute), e.requests.getValue(provider))
        }
    }

    @Test fun aReconnectedAccountIsReadWithoutWaitingForAnotherAccountsLongCooldown() {
        val e = Experiment().apply {
            cost = 0; earlyWakeEvery = minute
            for (provider in Provider.entries) rows[provider] = rows.getValue(provider).let { row ->
                row.copy(account = row.account.copy(status = if (provider == Provider.INSTAGRAM) SyncStatus.RATE_LIMITED else SyncStatus.REAUTH_REQUIRED,
                    nextAllowedAt = if (provider == Provider.INSTAGRAM) wall + 10 * minute else null))
            }
            afterWait = {
                if (elapsed == minute) rows[Provider.X] = rows.getValue(Provider.X).let {
                    it.copy(account = it.account.copy(status = SyncStatus.READY, nextAllowedAt = null))
                }
            }
        }
        e.runFor(2 * minute)
        assertEquals(listOf(minute), e.requests.getValue(Provider.X))
        assertTrue(e.requests.getValue(Provider.INSTAGRAM).isEmpty())
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

    @Test fun a72HourLoopReadsEveryProviderWithoutSixHourReset() {
        val e = Experiment()
        e.runFor(72 * hour)
        assertEquals(4_320 * Provider.entries.size, e.observations.values.sumOf { it.size })
        assertEquals(4_320, e.cycles.size)
        assertTrue(e.cycles.all { it.freshKeys.size == Provider.entries.size && it.freshAt != null })
        assertEquals(1, e.maxInFlight)
    }

    @Test fun aMissingFacebookDocumentAfterTwentyHoursRecoversOnTheNextMinuteWithoutReconnecting() {
        val e = Experiment().apply {
            cost = 0
            behavior = { provider, attempt ->
                if (provider == Provider.FACEBOOK && attempt == 1_201) CollectionFailure(SyncStatus.FORMAT_CHANGED) else null
            }
        }
        e.runFor(24 * hour)
        assertEquals(1_440, e.requests.getValue(Provider.FACEBOOK).size)
        val observations = e.observations.getValue(Provider.FACEBOOK)
        assertEquals(1_439, observations.size)
        assertEquals(2 * minute, observations.zipWithNext().maxOf { (a, b) -> b - a })
        assertTrue(observations.contains(20 * hour + minute))
        val recovered = e.rows.getValue(Provider.FACEBOOK).account
        assertEquals(SyncStatus.READY, recovered.status)
        assertNull(recovered.transientRetry)
        assertEquals(1L, recovered.connectedAt)
        for (other in Provider.entries.filter { it != Provider.FACEBOOK })
            assertEquals(1_440, e.observations.getValue(other).size)
    }

    @Test fun facebookPersonalAndMultiplePagesKeepIndependentCadenceAndFailureStateFor24Hours() = runBlocking {
        val personal = Account(Provider.FACEBOOK, "42", "fixture.owner",
            profileUrl = "https://www.facebook.com/profile.php?id=42", connectedAt = 1,
            countTransport = CountTransport.PROFILE_BROWSER, capabilities = Capabilities(background = Capability.OBSERVED))
        fun page(id: String) = personal.copy(stableId = id, accountType = AccountType.PAGE, sessionOwnerId = "42",
            profileUrl = "https://www.facebook.com/profile.php?id=$id")
        val limited = page("99"); val challenged = page("88")
        val rows = listOf(personal, limited, challenged).associate { it.key to TrackingCount(it, 1) }.toMutableMap()
        val attempts = rows.keys.associateWith { mutableListOf<Long>() }
        var elapsed = 0L
        val wall = { 1_700_000_000_000L + elapsed }
        val loop = CountTrackingLoop(TrackingMode.PREMIUM_WIDGET_TEST, { elapsed }, wall, { false },
            readCounts = { rows.values.toList() }, refresh = { account ->
                val calls = attempts.getValue(account.key).apply { add(elapsed) }
                val failed = when {
                    account.key == challenged.key -> account.copy(status = SyncStatus.CHECK_REQUIRED)
                    account.key == limited.key && calls.size == 1 -> account.copy(status = SyncStatus.RATE_LIMITED, nextAllowedAt = wall() + 900_000)
                    else -> null
                }
                rows[account.key] = if (failed != null) rows.getValue(account.key).copy(account = failed)
                    else TrackingCount(account.copy(status = SyncStatus.READY, nextAllowedAt = null), wall())
            }, onCycle = {}, onOffline = {}, awaitNetwork = { fail("Network is available") }, wait = {
                elapsed += it
                if (elapsed >= 24 * hour) throw CancellationException("End of virtual observation")
            })
        launch { loop.run() }.join()
        assertEquals(1_440, attempts.getValue(personal.key).size)
        assertEquals(1_426, attempts.getValue(limited.key).size)
        assertEquals(15 * minute, attempts.getValue(limited.key)[1])
        assertEquals(listOf(0L), attempts.getValue(challenged.key))
        assertTrue(attempts.getValue(personal.key).zipWithNext().all { (a,b) -> b - a == minute })
        assertEquals(SyncStatus.READY, rows.getValue(personal.key).account.status)
        assertEquals(SyncStatus.READY, rows.getValue(limited.key).account.status)
        assertEquals(SyncStatus.CHECK_REQUIRED, rows.getValue(challenged.key).account.status)
        assertEquals(setOf("42"), rows.values.map { it.account.authenticationOwnerId }.toSet())
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
        e.runFor(minute / 2)
        assertEquals(1, e.requests.getValue(Provider.FACEBOOK).size)
        assertTrue(e.observations.getValue(Provider.FACEBOOK).isEmpty())
        assertEquals(1_699_999_999_999L, e.rows.getValue(Provider.FACEBOOK).observedAt)
        assertTrue(e.cycles.none { e.rows.getValue(Provider.FACEBOOK).account.key in it.freshKeys })
        e.runFor(minute)
        assertEquals(1, e.observations.getValue(Provider.FACEBOOK).size)
        assertEquals(listOf(0L, minute), e.requests.getValue(Provider.FACEBOOK))
        assertEquals(listOf(minute), e.observations.getValue(Provider.FACEBOOK))
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
