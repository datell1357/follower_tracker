package dev.datell.followertracker.sync

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.datell.followertracker.BuildConfig
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.SyncStatus
import dev.datell.followertracker.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** Debug-only, app-UID totals. No cookies, URLs, account identifiers or response data. */
internal object CollectionMeter {
    private data class Sample(val elapsed: Long, val rx: Long, val tx: Long)
    private fun sample(): Sample? = if (!BuildConfig.DEBUG) null else runCatching {
        val uid = Process.myUid()
        Sample(SystemClock.elapsedRealtime(), TrafficStats.getUidRxBytes(uid), TrafficStats.getUidTxBytes(uid))
    }.getOrNull()

    internal fun byteDelta(before: Long, after: Long): Long? =
        if (before < 0 || after < before) null else after - before

    suspend fun <T> measure(provider: Provider, operation: String, read: suspend () -> T): T {
        val before = sample()
        var status: SyncStatus? = SyncStatus.READY
        var kind = DiagnosticKind.READ_COMPLETE
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        try { return read() }
        catch (cancelled: CancellationException) {
            kind = DiagnosticKind.ATTEMPT_CANCELLED; status = null; throw cancelled
        }
        catch (failure: Exception) {
            kind = DiagnosticKind.READ_FAILURE; status = (failure as? CollectionFailure)?.status; throw failure
        }
        finally {
            if (before != null) sample()?.let { after ->
                Log.d("FollowerCollection", "provider=${provider.name} operation=$operation " +
                    "elapsedMs=${after.elapsed - before.elapsed} " +
                    "rxBytes=${byteDelta(before.rx, after.rx) ?: "unknown"} " +
                    "txBytes=${byteDelta(before.tx, after.tx) ?: "unknown"}")
                DebugDiagnostics.record(DiagnosticEntry(kind, provider, operation =
                    if (operation == "count") DiagnosticOperation.COUNT else DiagnosticOperation.RELATIONSHIPS,
                    attempt = attempt, status = status, elapsedMs = (after.elapsed - before.elapsed).coerceAtLeast(0),
                    rxBytes = byteDelta(before.rx, after.rx), txBytes = byteDelta(before.tx, after.tx)))
            }
        }
    }
}
