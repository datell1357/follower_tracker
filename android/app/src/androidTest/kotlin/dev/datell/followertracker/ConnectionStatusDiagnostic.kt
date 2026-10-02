package dev.datell.followertracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.Capabilities
import dev.datell.followertracker.core.Capability
import dev.datell.followertracker.core.CountTransport
import dev.datell.followertracker.core.SyncStatus
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only opt-in diagnostic. Emits booleans/statuses only, never IDs, names, counts, cookies, or DOM. */
@RunWith(AndroidJUnit4::class)
class ConnectionStatusDiagnostic {
    /** One authorized live request, only when explicitly opted in. Does not change stored observations. */
    @Test fun probeNativeCountWithoutLoginScreen() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeNativeCount") == "true")
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val row = graph.repository.overviews().firstOrNull { it.account.provider == Provider.INSTAGRAM }
        assumeTrue(row != null)
        val account = checkNotNull(row).account
        assumeTrue((account.nextAllowedAt ?: 0) <= System.currentTimeMillis())
        val started = android.os.SystemClock.elapsedRealtime()
        val result = JSONObject().put("provider", account.provider.name)
        try {
            val (fresh, metric) = graph.collector.native(account.provider,
                account.copy(status = SyncStatus.READY, countTransport = CountTransport.SESSION_HTTP,
                    capabilities = Capabilities(count = Capability.OBSERVED)))
            result.put("status", "SUCCESS").put("identityMatched", fresh.stableId == account.stableId)
                .put("newResponse", metric.observedAt > (row.latest?.observedAt ?: 0))
                .put("countMatchesStored", metric.followers == row.latest?.followers)
                .put("source", metric.source)
        } catch (failure: CollectionFailure) {
            result.put("status", failure.status.name).put("retryAfterSeconds", failure.retryAfterSeconds ?: 900)
        }
        result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
        println("NATIVE_COUNT_DIAGNOSTIC=$result")
        assertTrue("The live native collection did not succeed; see the redacted diagnostic status.", result.optString("status") == "SUCCESS")
    }
    @Test fun recordStoredConnectionStatus() = runBlocking {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val rows = graph.repository.overviews()
        val result = JSONArray()
        for (provider in Provider.entries) {
            val row = rows.firstOrNull { it.account.provider == provider }
            result.put(JSONObject().put("provider", provider.name)
                .put("sessionCandidate", graph.sessions.hasAuthentication(provider))
                .put("connected", row != null)
                .put("metricStored", row?.latest != null)
                .put("status", row?.account?.status?.name ?: "UNCONNECTED")
                .put("metricSource", row?.latest?.source ?: "NONE")
                .put("backgroundCapability", row?.account?.capabilities?.background?.name ?: "UNCONNECTED")
                .put("countTransport", row?.account?.countTransport?.name ?: "UNCONNECTED"))
        }
        println("CONNECTION_DIAGNOSTIC=$result")
    }
}
