package dev.datell.followertracker.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A bounded window: requests cannot postpone an update indefinitely. */
internal class CoalescingUpdater(
    scope: CoroutineScope,
    private val waitForWindow: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val update: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (request in requests) {
                waitForWindow()
                requests.tryReceive() // A conflated channel holds at most one pending request.
                try { update() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { onFailure(failure) }
                // Requests arriving while update suspends remain queued for the next window.
            }
        }
    }
    fun request() { requests.trySend(Unit) }
}
