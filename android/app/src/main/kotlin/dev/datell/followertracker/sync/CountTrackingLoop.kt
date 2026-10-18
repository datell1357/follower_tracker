package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TrackingCount(val account: Account, val observedAt: Long?)

internal data class TrackingCycle(
    val rows: List<TrackingCount>,
    val freshKeys: Set<String>,
    val freshAt: Long?,
    val failedProviders: Set<Provider>,
    val elapsedMillis: Long,
) {
    val message: String get() = countBatchMessage(rows.map { it.account.status }, freshAt != null, failedProviders.isNotEmpty())
}

internal enum class TrackingLoopStop { TIME_LIMIT, NO_ACCOUNTS, CONNECTION_REQUIRED }

/** The service's real loop. Only clock, waiting and I/O are injected for deterministic failure experiments. */
internal class CountTrackingLoop(
    private val mode: TrackingMode,
    private val elapsedRealtime: () -> Long,
    private val currentTimeMillis: () -> Long,
    private val isOffline: () -> Boolean,
    private val readCounts: suspend () -> List<TrackingCount>,
    private val refresh: suspend (Account) -> Unit,
    private val onCycle: suspend (TrackingCycle) -> Unit,
    private val onOffline: () -> Unit,
    private val awaitNetwork: suspend (remainingMillis: Long?) -> Unit,
    private val wait: suspend (Long) -> Unit,
) {
    suspend fun run(): TrackingLoopStop {
        val started = elapsedRealtime()
        val attemptedAt = mutableMapOf<String, Long>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val elapsed = elapsedRealtime() - started
            if (!TrackingSessionPolicy.canContinue(mode, elapsed)) return TrackingLoopStop.TIME_LIMIT
            if (isOffline()) {
                onOffline()
                awaitNetwork(TrackingSessionPolicy.remainingMillis(mode, elapsed))
                continue
            }
            val cycleStarted = elapsedRealtime()
            val before = readCounts().filter { RefreshPolicy.supportsCountRefresh(it.account) }
            val retryable = before.filter { !it.account.status.blocksAutomaticRetry }
            if (retryable.isEmpty()) return if (before.isEmpty()) TrackingLoopStop.NO_ACCOUNTS else TrackingLoopStop.CONNECTION_REQUIRED
            val available = retryable.filter {
                RefreshPolicy.canRefresh(it.account, currentTimeMillis(), background = true) &&
                    (attemptedAt[it.account.key]?.let { at -> cycleStarted - at >= INTERVAL } ?: true)
            }
            available.forEach { attemptedAt[it.account.key] = cycleStarted }
            val failed = refreshCountBatch(available.map { it.account }, refresh)
            val after = readCounts().filter { RefreshPolicy.supportsCountRefresh(it.account) }
            val fresh = after.filter { row -> row.account.capabilities.background == Capability.OBSERVED &&
                row.observedAt != null && row.observedAt >
                (available.firstOrNull { it.account.key == row.account.key }?.observedAt ?: Long.MAX_VALUE) }
            if (after.isEmpty()) return TrackingLoopStop.NO_ACCOUNTS
            if (after.all { it.account.status.blocksAutomaticRetry }) return TrackingLoopStop.CONNECTION_REQUIRED
            onCycle(TrackingCycle(after, fresh.map { it.account.key }.toSet(), fresh.maxOfOrNull { it.observedAt!! },
                failed, cycleStarted - started))
            val now = currentTimeMillis()
            attemptedAt.keys.retainAll(after.map { it.account.key }.toSet())
            val elapsedNow = elapsedRealtime()
            val next = after.filter { !it.account.status.blocksAutomaticRetry }.minOfOrNull { row ->
                val cooldown = maxOf(row.account.nextAllowedAt ?: 0, row.account.transientRetry?.nextAttemptAt ?: 0) - now
                val cadence = attemptedAt[row.account.key]?.let { INTERVAL - (elapsedNow - it) } ?: 0
                maxOf(cooldown, cadence)
            } ?: INTERVAL
            val delayMillis = maxOf(1_000L, next)
            val remaining = TrackingSessionPolicy.remainingMillis(mode, elapsedRealtime() - started)
            currentCoroutineContext().ensureActive()
            wait(minOf(delayMillis, (remaining ?: delayMillis).coerceAtLeast(1)))
        }
    }

    companion object { const val INTERVAL = 60_000L }
}
