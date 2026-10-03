package dev.datell.followertracker.sync

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.datell.followertracker.BuildConfig
import dev.datell.followertracker.core.Provider

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
        try { return read() }
        finally {
            if (before != null) sample()?.let { after ->
                Log.d("FollowerCollection", "provider=${provider.name} operation=$operation " +
                    "elapsedMs=${after.elapsed - before.elapsed} " +
                    "rxBytes=${byteDelta(before.rx, after.rx) ?: "unknown"} " +
                    "txBytes=${byteDelta(before.tx, after.tx) ?: "unknown"}")
            }
        }
    }
}
