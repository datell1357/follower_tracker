package dev.datell.followertracker

import android.content.Context
import android.webkit.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic sessions and UUID databases. Run only on an isolated QA emulator. */
@RunWith(AndroidJUnit4::class)
class ResourceEfficiencyRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun account(provider: Provider) = Account(provider, "42", "fixture",
        profileUrl = "https://${provider.domain}/fixture/", connectedAt = 1)
    private class Collector : SessionCollecting {
        var requests = 0
        override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
            requests++
            val updated = checkNotNull(expected).copy(status = SyncStatus.READY)
            return updated to MetricSnapshot(updated.key, 5_000, 0, 2, source = "synthetic-resource")
        }
        override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> {
            requests++
            fun scan(direction: Direction) = RelationshipSnapshot(account.key, direction, 1, 2,
                emptyList(), true, true, "synthetic-resource")
            return scan(Direction.FOLLOWERS) to scan(Direction.FOLLOWING)
        }
    }

    @Test fun offlineAutomaticCollectionPreservesEveryProviderAndManualRefreshCanStillRun() = runBlocking {
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "resource-offline-${UUID.randomUUID()}.db").build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val collector = Collector()
            var publications = 0
            val sync = SyncCoordinator(context, repository, collector, { publications++ }, { true })
            for (provider in Provider.entries) {
                val account = account(provider)
                repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 7, 2, source = "synthetic-baseline"))
                sync.refresh(account.key, background = true)
                sync.relationships(account.key, background = true)
                assertEquals(account, repository.account(account.key))
                assertEquals(listOf(1_000L), repository.history(account.key).map { it.observedAt })
            }
            assertEquals(0, collector.requests); assertEquals(0, publications)
            for (provider in Provider.entries) sync.refresh(account(provider).key)
            assertEquals(5, collector.requests); assertEquals(5, publications)
            for (provider in Provider.entries) assertEquals(0L, repository.widgetOverviews(account(provider).key).single().latest!!.followers)
        } finally { database.close() }
    }

    @Test fun compactWidgetSnapshotsKeepFreshTimestampsChangesSelectionAndFullHistory() = runBlocking {
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "resource-widget-${UUID.randomUUID()}.db").build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            for (provider in Provider.entries) {
                val account = account(provider)
                for (i in 1..4) repository.saveObservation(account,
                    MetricSnapshot(account.key, i * 1_000L, (4 - i).toLong(), 2, source = "synthetic-history"))
            }
            val rows = repository.widgetOverviews()
            assertEquals(5, rows.size)
            for (row in rows) {
                assertEquals(2, row.history.size); assertEquals(0L, row.latest!!.followers)
                assertEquals(4_000L, row.latest!!.observedAt); assertEquals(-1L, row.change)
                assertEquals(4, repository.history(row.account.key).size)
                repository.updateStatus(row.account.key, SyncStatus.RATE_LIMITED, 6_000, 9_000)
                val failed = repository.widgetOverviews(row.account.key).single()
                assertEquals(SyncStatus.RATE_LIMITED, failed.account.status)
                assertEquals(row.latest, failed.latest)
                assertEquals(row.comparison, failed.comparison)
            }
            assertTrue(repository.widgetOverviews("missing").isEmpty())
        } finally { database.close() }
    }

    @Test fun metadataCacheInvalidatesOnSaveExternalChangeAndDisconnectForAllProviders() {
        val name = "resource-sessions-${UUID.randomUUID()}"
        val sessions = SessionStore(context, DataCipher(), name)
        val other = SessionStore(context, DataCipher(), name)
        for (provider in Provider.entries) {
            val first = SessionMetadata("synthetic-agent", "42", 1)
            val second = first.copy(savedAt = 2)
            sessions.save(provider, first); assertEquals(first, sessions.metadata(provider))
            other.save(provider, second); assertEquals(second, sessions.metadata(provider))
            other.disconnect(provider); assertNull(sessions.metadata(provider))
        }
    }

    @Test fun widgetFlowReadsANewMetricWithinTheSameSubscription() = runBlocking {
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "resource-widget-flow-${UUID.randomUUID()}.db").build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val account = account(Provider.INSTAGRAM)
            repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 7, 2, source = "synthetic-flow"))
            val updates = Channel<List<AccountOverview>>(Channel.UNLIMITED)
            val watching = launch { repository.widgetOverviewsFlow().collect { updates.send(it) } }
            try {
                assertEquals(1_000L, withTimeout(5_000) { updates.receive() }.single().latest!!.observedAt)
                // Account JSON is identical. A new observation must still reach a live widget.
                repository.saveObservation(account, MetricSnapshot(account.key, 5_000, 0, 2, source = "synthetic-flow"))
                val fresh = withTimeout(5_000) {
                    var rows = updates.receive()
                    while (rows.single().latest!!.observedAt != 5_000L) rows = updates.receive()
                    rows.single()
                }
                assertEquals(0L, fresh.latest!!.followers); assertEquals(-7L, fresh.change)
                assertEquals(2, fresh.history.size)
            } finally { watching.cancelAndJoin() }
        } finally { database.close() }
    }

    @Test fun serverCooldownCannotBeBypassedByManualCollectionForAnyProvider() = runBlocking {
        val database = Room.databaseBuilder(context, TrackerDatabase::class.java, "resource-cooldown-${UUID.randomUUID()}.db").build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val collector = Collector()
            val sync = SyncCoordinator(context, repository, collector, {}, { false })
            val until = System.currentTimeMillis() + 60_000
            for (provider in Provider.entries) {
                val account = account(provider).copy(status = SyncStatus.RATE_LIMITED, nextAllowedAt = until)
                repository.saveObservation(account, MetricSnapshot(account.key, 1_000, 0, 2, source = "synthetic-cooldown"))
                sync.refresh(account.key); sync.refresh(account.key, background = true)
                assertEquals(until, repository.account(account.key)!!.nextAllowedAt)
                assertEquals(1_000L, repository.widgetOverviews(account.key).single().latest!!.observedAt)
            }
            assertEquals(0, collector.requests)
        } finally { database.close() }
    }

    @Test fun reusedBrowserReadsANewCountAndClearsThePageBetweenCycles() = runBlocking {
        val sessions = SessionStore(context, DataCipher(), "resource-browser-${UUID.randomUUID()}")
        val provider = Provider.INSTAGRAM
        val account = account(provider).copy(profileUrl = "https://www.instagram.com/fixture/")
        val views = mutableListOf<WebView>()
        var documentLoads = 0
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().setCookie(provider.loginUrl, "sessionid=synthetic-resource; Path=/; Secure")
            CookieManager.getInstance().setCookie(provider.loginUrl, "ds_user_id=42; Path=/; Secure")
        }
        sessions.save(provider, SessionMetadata("synthetic-agent", "42", 1))
        val browser = ProfilePageCollector(context, sessions) { ctx ->
            object : WebView(ctx) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    assertEquals("no-cache", additionalHttpHeaders["Cache-Control"])
                    assertEquals(WebSettings.LOAD_DEFAULT, settings.cacheMode)
                    assertTrue(settings.blockNetworkImage)
                    documentLoads++
                    val count = if (documentLoads == 1) 7 else 0
                    loadDataWithBaseURL(url, """<html><script type="application/json">{"user":{"id":"42","username":"fixture","follower_count":$count,"following_count":2}}</script></html>""",
                        "text/html", "UTF-8", url)
                }
            }.also { views += it }
        }
        try {
            assertEquals(7L, JSONObject(browser.read(account, "synthetic-agent", retain = true)).getLong("followers"))
            withContext(Dispatchers.Main) {
                assertEquals("about:blank", views.single().url)
                assertFalse(views.single().settings.javaScriptEnabled)
            }
            assertEquals(0L, JSONObject(browser.read(account, "synthetic-agent", retain = true)).getLong("followers"))
            assertEquals(1, views.size); assertEquals(2, documentLoads)
            withContext(Dispatchers.Main) {
                val web = views.single()
                assertTrue(web.webViewClient.onRenderProcessGone(web, object : RenderProcessGoneDetail() {
                    override fun didCrash() = false
                    override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
                }))
            }
            browser.read(account, "synthetic-agent", retain = true)
            assertEquals(2, views.size)
            sessions.save(provider, SessionMetadata("synthetic-agent", "42", 2))
            browser.read(account, "synthetic-agent", retain = true)
            assertEquals(3, views.size)
            withContext(Dispatchers.Main) { browser.release() }
            browser.read(account, "synthetic-agent", retain = true)
            assertEquals(4, views.size)
        } finally {
            withContext(Dispatchers.Main) { browser.release() }
            sessions.disconnect(provider)
        }
    }
}
