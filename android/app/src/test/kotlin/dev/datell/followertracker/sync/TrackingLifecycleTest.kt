package dev.datell.followertracker.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TrackingLifecycleTest {
    @Test fun processStartupRestoresHealthJobsForAnExistingRequest() {
        var registered = 0; var cancelled = 0
        restoreTrackingSchedule(true, { registered++ }, { cancelled++ })
        assertEquals(1, registered); assertEquals(0, cancelled)
    }

    @Test fun startupAfterAStopOnlyCancelsRecoveryJobs() {
        var registered = 0; var cancelled = 0
        restoreTrackingSchedule(false, { registered++ }, { cancelled++ })
        assertEquals(0, registered); assertEquals(1, cancelled)
    }

    @Test fun anUnexpectedChildCancellationCannotLeaveTheServiceMarkedRunning() = runBlocking {
        var running = true; var interrupted = 0
        val child = launch {
            runTrackingSession({ false }, { throw CancellationException("Synthetic collector cancellation") },
                onStop = { fail("A cancelled loop has no successful stop result") },
                onInterrupted = { running = false; interrupted++ })
        }
        child.join()
        assertFalse(running); assertEquals(1, interrupted)
    }

    @Test fun explicitStopCancellationNeverRequestsARecovery() = runBlocking {
        var interrupted = 0
        val child = launch {
            runTrackingSession({ true }, { throw CancellationException("Explicit stop") },
                onStop = { fail("Cancelled") }, onInterrupted = { interrupted++ })
        }
        child.join(); assertEquals(0, interrupted)
    }

    @Test fun anUnexpectedExceptionReportsAnInterruptionOnce() = runBlocking {
        var interrupted = 0
        runTrackingSession({ false }, { throw IllegalStateException("Synthetic storage failure") },
            onStop = { fail("Failed") }, onInterrupted = { interrupted++ })
        assertEquals(1, interrupted)
    }

    @Test fun aNormalLoopStopKeepsItsReasonAndDoesNotBecomeARecovery() = runBlocking {
        var reason: TrackingLoopStop? = null; var interrupted = 0
        runTrackingSession({ false }, { TrackingLoopStop.NO_ACCOUNTS },
            onStop = { reason = it }, onInterrupted = { interrupted++ })
        assertEquals(TrackingLoopStop.NO_ACCOUNTS, reason); assertEquals(0, interrupted)
    }

    @Test fun anExpiredSessionWaitsForReconnectionInsteadOfRestartingAnEmptyService() {
        var starts = 0
        val recovery = TrackingRecovery({ TrackingMode.PREMIUM_WIDGET_TEST }, { true }, { false }, { starts++ })
        assertEquals(TrackingRecoveryResult.WAITING_FOR_CONNECTION, recovery.run(hasEligibleAccount = false))
        assertEquals(0, starts)
        assertEquals(TrackingRecoveryResult.START_REQUESTED, recovery.run(hasEligibleAccount = true))
        assertEquals(1, starts)
    }
}
