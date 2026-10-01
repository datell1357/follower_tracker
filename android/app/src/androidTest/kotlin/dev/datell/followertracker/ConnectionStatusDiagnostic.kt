package dev.datell.followertracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.Provider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only opt-in diagnostic. Emits booleans/statuses only, never IDs, names, counts, cookies, or DOM. */
@RunWith(AndroidJUnit4::class)
class ConnectionStatusDiagnostic {
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
                .put("metricSource", row?.latest?.source ?: "NONE"))
        }
        println("CONNECTION_DIAGNOSTIC=$result")
    }
}
