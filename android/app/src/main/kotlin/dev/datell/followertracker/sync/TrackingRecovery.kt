package dev.datell.followertracker.sync

import kotlinx.coroutines.CancellationException

internal enum class TrackingRecoveryResult { INACTIVE, HEALTHY, START_REQUESTED, DEFERRED }

/** A recovery request never creates an entitlement, a new session, a running flag or a successful read. */
internal class TrackingRecovery(
    private val mode: () -> TrackingMode,
    private val requested: () -> Boolean,
    private val running: () -> Boolean,
    private val start: () -> Unit,
) {
    fun run(): TrackingRecoveryResult {
        if (mode() != TrackingMode.PREMIUM_WIDGET_TEST || !requested()) return TrackingRecoveryResult.INACTIVE
        if (running()) return TrackingRecoveryResult.HEALTHY
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
