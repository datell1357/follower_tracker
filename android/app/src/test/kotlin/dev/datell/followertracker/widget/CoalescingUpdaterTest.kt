package dev.datell.followertracker.widget

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class CoalescingUpdaterTest {
    @Test fun combinesABurstAndKeepsRequestsThatArriveDuringPublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val windows = Channel<Unit>(Channel.UNLIMITED)
        val releaseWindow = Channel<Unit>(Channel.UNLIMITED)
        val published = Channel<Unit>(Channel.UNLIMITED)
        val releasePublication = Channel<Unit>(Channel.UNLIMITED)
        try {
            val updater = CoalescingUpdater(scope, { windows.send(Unit); releaseWindow.receive() },
                { throw AssertionError(it) }, { published.send(Unit); releasePublication.receive() })
            updater.request()
            withTimeout(5_000) { windows.receive() }
            repeat(100) { updater.request() }
            releaseWindow.send(Unit)
            withTimeout(5_000) { published.receive() }
            assertFalse(published.tryReceive().isSuccess)
            updater.request() // Publication is suspended; this update must not get lost.
            releasePublication.send(Unit)
            withTimeout(5_000) { windows.receive() }
            releaseWindow.send(Unit)
            withTimeout(5_000) { published.receive() }
            assertFalse(published.tryReceive().isSuccess)
        } finally { scope.cancel() }
    }

    @Test fun aFailedPublicationDoesNotDisableLaterUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val windows = Channel<Unit>(Channel.UNLIMITED)
        val releaseWindow = Channel<Unit>(Channel.UNLIMITED)
        val failure = CompletableDeferred<Unit>()
        val success = CompletableDeferred<Unit>()
        var attempts = 0
        try {
            val updater = CoalescingUpdater(scope, { windows.send(Unit); releaseWindow.receive() },
                { failure.complete(Unit) }, {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("synthetic")
                    success.complete(Unit)
                })
            updater.request()
            withTimeout(5_000) { windows.receive() }; releaseWindow.send(Unit)
            withTimeout(5_000) { failure.await() }
            updater.request()
            withTimeout(5_000) { windows.receive() }; releaseWindow.send(Unit)
            withTimeout(5_000) { success.await() }
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }
}
