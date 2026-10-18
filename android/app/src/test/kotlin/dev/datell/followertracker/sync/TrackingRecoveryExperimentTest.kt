package dev.datell.followertracker.sync

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TrackingRecoveryExperimentTest {
    @Test fun ordinaryAndUnrequestedSessionsCanNeverBeRecoveredByAQueuedHealthCheck() {
        for (mode in TrackingMode.entries) for (requested in listOf(false, true)) {
            if (mode == TrackingMode.PREMIUM_WIDGET_TEST && requested) continue
            var starts = 0
            val outcome = TrackingRecovery({ mode }, { requested }, { false }, { starts++ }).run()
            assertEquals(TrackingRecoveryResult.INACTIVE, outcome)
            assertEquals(0, starts)
        }
    }

    @Test fun aLiveServiceIsNeverRestartedEvenIfItsLastSuccessfulSnsReadIsOld() {
        var starts = 0
        repeat(96) {
            assertEquals(TrackingRecoveryResult.HEALTHY,
                TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { true }, { true }, { starts++ }).run())
        }
        assertEquals(0, starts)
    }

    @Test fun anInterruptedPremiumRequestDispatchesRecoveryWithoutClaimingTheServiceIsAlreadyRunning() {
        var starts = 0
        val outcome = TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { true }, { false }, { starts++ }).run()
        assertEquals(TrackingRecoveryResult.START_REQUESTED, outcome)
        assertEquals(1, starts)
        assertNotEquals(TrackingRecoveryResult.HEALTHY, outcome)
    }

    @Test fun deniedBackgroundStartsPreserveTheRequestAndHaveABoundedRetryBudget() {
        var requested = true
        var starts = 0
        val recovery = TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { requested }, { false }) {
            starts++; throw SecurityException("Synthetic OS denial")
        }
        for (attempt in 0..2) {
            assertEquals(TrackingRecoveryResult.DEFERRED, recovery.run())
            assertTrue(requested)
            assertEquals(attempt < 2, TrackingRecovery.retryDeferred(attempt))
        }
        requested = false
        assertEquals(TrackingRecoveryResult.INACTIVE, recovery.run())
        assertEquals(3, starts)
    }

    @Test fun aStopBetweenTheHealthCheckAndDispatchCancelsTheRecovery() {
        var checks = 0
        var starts = 0
        val outcome = TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { ++checks == 1 }, { false }, { starts++ }).run()
        assertEquals(TrackingRecoveryResult.INACTIVE, outcome)
        assertEquals(0, starts)
    }

    @Test fun aRecoveryIntentDeliveredAfterAStopCannotBeTreatedAsANewUserStart() {
        assertFalse(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, explicitStart = false, requested = false))
        assertTrue(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, explicitStart = false, requested = true))
        assertTrue(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, explicitStart = true, requested = false))
    }

    @Test fun cancellationIsNotMistakenForAnOsDenialThatShouldBeRetried() {
        try {
            TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { true }, { false }) {
                throw CancellationException("Synthetic cancellation")
            }.run()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun a24HourRecoveryExperimentNeverResurrectsAUserStoppedSession() {
        var requested = true
        var running = true
        var minute = 0
        val starts = mutableListOf<Int>()
        val deferred = mutableListOf<Int>()
        val recovery = TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { requested }, { running }) {
            starts += minute
            if (minute / 15 % 3 == 0) throw IllegalStateException("Synthetic background restriction")
            running = true
        }
        repeat(1_440) { now ->
            minute = now
            if (now == 720) { requested = false; running = false }
            if (requested && now > 0 && now % 73 == 0) running = false
            if (now % 15 == 0) {
                val outcome = recovery.run()
                if (outcome == TrackingRecoveryResult.DEFERRED) {
                    deferred += now
                    assertFalse(running)
                    assertTrue(requested)
                }
                if (!requested) assertEquals(TrackingRecoveryResult.INACTIVE, outcome)
            }
        }
        assertTrue(starts.isNotEmpty())
        assertTrue(deferred.isNotEmpty())
        assertTrue(starts.all { it < 720 && it % 15 == 0 })
        assertFalse(running)
        assertFalse(requested)
    }
}
