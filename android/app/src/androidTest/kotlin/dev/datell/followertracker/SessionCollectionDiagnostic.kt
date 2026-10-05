package dev.datell.followertracker

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.AccountType
import dev.datell.followertracker.core.CollectionFailure
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.sync.ProfilePageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One opt-in production profile read. Never saves an observation or prints account/session values. */
@RunWith(AndroidJUnit4::class)
class SessionCollectionDiagnostic {
    @Test fun inspectStoredProfileWithoutChangingItsObservation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("probeSessionCollection") == "true")
        val provider = Provider.valueOf(checkNotNull(args.getString("provider")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val before = graph.repository.widgetOverviews().firstOrNull {
            it.account.provider == provider && it.account.accountType == AccountType.PROFILE
        }
        assumeTrue(before != null)
        val original = checkNotNull(before)
        val metadata = graph.sessions.metadata(provider)
        assumeTrue(metadata != null)
        val reader = ProfilePageCollector(context, graph.sessions)
        val report = JSONObject().put("provider", provider.name)
            .put("sessionBefore", graph.sessions.hasAuthentication(provider))
            .put("ownerBefore", graph.sessions.identity(provider) == original.account.stableId)
            .put("storedStatus", original.account.status.name)
        try {
            try {
                val payload = JSONObject(reader.read(original.account, checkNotNull(metadata).userAgent))
                report.put("result", "SUCCESS")
                    .put("capturedOwner", payload.optString("stableId") == original.account.stableId)
                    .put("capturedExact", payload.optString("precision") == "EXACT")
            } catch (failure: CollectionFailure) { report.put("result", failure.status.name) }
            val metadataPreserved = graph.sessions.metadata(provider) == metadata
            val observationPreserved = graph.repository.widgetOverviews(original.account.key).single() == original
            assertTrue("A read-only diagnostic must preserve session metadata", metadataPreserved)
            assertTrue("A read-only diagnostic must preserve the stored observation", observationPreserved)
            report.put("sessionAfter", graph.sessions.hasAuthentication(provider))
                .put("ownerAfter", graph.sessions.identity(provider) == original.account.stableId)
                .put("metadataPreserved", metadataPreserved)
                .put("observationPreserved", observationPreserved)
            instrumentation.sendStatus(2, Bundle().apply { putString("SESSION_COLLECTION_DIAGNOSTIC", report.toString()) })
        } finally { withContext(Dispatchers.Main) { reader.release() } }
    }
}
