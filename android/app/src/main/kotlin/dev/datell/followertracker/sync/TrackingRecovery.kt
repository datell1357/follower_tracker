package dev.datell.followertracker.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal enum class TrackingRecoveryResult { INACTIVE, WAITING_FOR_CONNECTION, HEALTHY, START_REQUESTED, DEFERRED }

/** A recovery request never creates an entitlement, a new session, a running flag or a successful read. */
internal class TrackingRecovery(
    private val mode: () -> TrackingMode,
    private val requested: () -> Boolean,
    private val running: () -> Boolean,
    private val start: () -> Unit,
) {
    fun run(hasEligibleAccount: Boolean = true): TrackingRecoveryResult {
        if (mode() != TrackingMode.PREMIUM_WIDGET_TEST || !requested()) return TrackingRecoveryResult.INACTIVE
        if (running()) return TrackingRecoveryResult.HEALTHY
        if (!hasEligibleAccount) return TrackingRecoveryResult.WAITING_FOR_CONNECTION
        if (!requested()) return TrackingRecoveryResult.INACTIVE
        return try {
            start()
            TrackingRecoveryResult.START_REQUESTED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { TrackingRecoveryResult.DEFERRED }
    }

    companion object {
        fun retryDeferred(attempt: Int): Boolean = attempt < 2
    }
}

/** Startup wiring is shared by Application, including a process created after an update. */
internal fun restoreTrackingSchedule(requested: Boolean, register: () -> Unit, cancel: () -> Unit) {
    if (requested) register() else cancel()
}

/** Keep service execution state consistent even when a child operation cancels the loop. */
internal suspend fun runTrackingSession(stopping: () -> Boolean, run: suspend () -> TrackingLoopStop,
    onStop: (TrackingLoopStop) -> Unit, onInterrupted: () -> Unit) {
    try { onStop(run()) }
    catch (cancelled: CancellationException) {
        if (!stopping()) onInterrupted()
        throw cancelled
    }
    catch (_: Exception) { if (!stopping()) onInterrupted() }
}

/** App visibility and session changes also wake an offline wait if no network callback arrives. */
internal suspend fun awaitTrackingSignal(changes: ReceiveChannel<Unit>, network: ReceiveChannel<Unit>? = null,
    timeoutMillis: Long? = null) {
    val receive: suspend () -> Unit = {
        if (network == null) changes.receive()
        else select { changes.onReceive { }; network.onReceive { } }
    }
    if (timeoutMillis == null) receive()
    else withTimeoutOrNull(timeoutMillis.coerceAtLeast(1)) { receive() }
}
