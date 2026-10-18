package dev.datell.followertracker.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class TrackingSignalsTest {
    @Test fun foregroundOrSessionChangeWakesAnOfflineWaitWithoutANetworkEvent() = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED); val network = Channel<Unit>(Channel.CONFLATED)
        var resumed = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { awaitTrackingSignal(changes, network); resumed = true }
        assertFalse(resumed); changes.send(Unit)
        withTimeout(1_000) { pending.join() }; assertTrue(resumed)
    }

    @Test fun anActualNetworkEventWakesTheSameWait() = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED); val network = Channel<Unit>(Channel.CONFLATED)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { awaitTrackingSignal(changes, network) }
        network.send(Unit); withTimeout(1_000) { pending.join() }
        assertTrue(pending.isCompleted)
    }

    @Test fun missingCallbacksHaveABoundedFallbackRatherThanAnInfiniteWait() = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED)
        withTimeout(1_000) { awaitTrackingSignal(changes, timeoutMillis = 1) }
        assertTrue(changes.tryReceive().isFailure)
    }

    @Test fun stoppingCancelsTheWaitAndLeavesNoConsumerThatStealsTheNextStartSignal() = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { awaitTrackingSignal(changes) }
        pending.cancelAndJoin(); changes.send(Unit)
        assertTrue(changes.tryReceive().isSuccess)
    }
}
