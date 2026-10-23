package dev.datell.followertracker

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic HTTP fixtures, in a dedicated package without the user's accounts or cookies. */
@RunWith(AndroidJUnit4::class)
class OfficialApiRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val channelId = "UC" + "a".repeat(22)
    private lateinit var secrets: OfficialApiStore
    private lateinit var sessions: SessionStore
    @Before fun isolatedPackage() {
        check(context.packageName == "dev.datell.followertracker.apivalidation") { "Requires the dedicated API validation app" }
        secrets = OfficialApiStore(context)
        secrets.clear(Provider.YOUTUBE); secrets.clear(Provider.FACEBOOK)
        sessions = SessionStore(context, DataCipher(), "api_runtime_session_fixture")
    }
    private fun channel(count: Long = 12300) = """{"items":[{"id":"$channelId","snippet":{"title":"Fixture channel"},"statistics":{"subscriberCount":"$count","hiddenSubscriberCount":false}}]}"""
    private fun page(count: Long = 10) = """{"id":"99","name":"Fixture Page","followers_count":$count}"""
    private fun api(respond: (Request) -> Pair<Int, String>) = OfficialApiClient(OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .body(body.toResponseBody()).build()
    }.build())
    private fun pageAccount() = OfficialApiResponse.facebookPage(page(), null, 1).first
    private fun youtubeAccount() = OfficialApiResponse.youtube(channel(), YouTubePublicChannel.target("youtube.com/channel/$channelId")!!, null, 1).first

    @Test fun repeatedNativeCollectionNeedsNeitherLoginCookiesNorABrowser() = runBlocking {
        var calls = 0
        secrets.saveYouTubeKey("fixture-key-123"); secrets.savePageToken("99", "fixture-page-token")
        val collector = SessionCollector(sessions, context = null, officialStore = secrets, officialApi = api { request ->
            calls++; assertNull(request.header("Cookie"))
            200 to if (request.url.host == "graph.facebook.com") page(calls.toLong()) else channel()
        })
        assertNull(sessions.metadata(Provider.FACEBOOK)); assertNull(sessions.metadata(Provider.YOUTUBE))
        repeat(3) {
            val page = collector.native(Provider.FACEBOOK, pageAccount())
            assertEquals(CountTransport.OFFICIAL_API, page.first.countTransport)
            assertEquals("facebook-graph-api-v26", page.second.source)
            assertEquals((it * 2 + 1).toLong(), page.second.followers)
            val youtube = collector.native(Provider.YOUTUBE, youtubeAccount())
            assertEquals(Precision.ROUNDED, youtube.second.precision)
        }
        assertEquals(6, calls)
    }
    @Test fun apiTransitionKeepsHistoryAndWidgetIdentityAndBackgroundStoresNewCounts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val legacy = pageAccount().copy(countTransport = CountTransport.PROFILE_BROWSER, sessionOwnerId = "42")
            repository.saveObservation(legacy, MetricSnapshot(legacy.key, 1, 5, null, source = "synthetic-legacy"))
            val official = OfficialApiResponse.facebookPage(page(10), legacy, 2)
            secrets.savePageToken("99", "fixture-page-token")
            repository.saveObservation(official.first, official.second)
            val collector = SessionCollector(sessions, null, secrets, api { 200 to page(12) })
            val coordinator = SyncCoordinator(context, repository, collector, publishWidgets = {}, isDefinitelyOffline = { false }, sessionGuard = secrets::guard)
            coordinator.refresh(legacy.key, background = true)
            assertEquals(listOf(5L, 10L, 12L), repository.history(legacy.key).map { it.followers })
            val row = repository.widgetOverviews(legacy.key).single()
            assertEquals(legacy.key, row.account.key); assertEquals(legacy.connectedAt, row.account.connectedAt)
            assertEquals(12L, row.latest?.followers); assertEquals(Capability.OBSERVED, row.account.capabilities.background)
            assertEquals(CountTransport.OFFICIAL_API, row.account.countTransport)
        } finally { database.close() }
    }
    @Test fun failedApiRequestsKeepTheLastValueAndBlockAutomaticReauthenticationOrQuotaLoops() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            secrets.saveYouTubeKey("fixture-key-123"); secrets.savePageToken("99", "fixture-page-token")
            var calls = 0
            val collector = SessionCollector(sessions, null, secrets, api { request ->
                calls++
                if (request.url.host == "graph.facebook.com") 400 to """{"error":{"code":190}}"""
                else 403 to """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""
            })
            val coordinator = SyncCoordinator(context, repository, collector, publishWidgets = {}, isDefinitelyOffline = { false }, sessionGuard = secrets::guard)
            for (account in listOf(pageAccount(), youtubeAccount())) {
                repository.saveObservation(account, MetricSnapshot(account.key, 1, 5, null, source = "synthetic-before"))
                repeat(3) { coordinator.refresh(account.key, background = true) }
                assertEquals(listOf(5L), repository.history(account.key).map { it.followers })
                assertEquals(1L, repository.history(account.key).last().observedAt)
            }
            assertEquals(2, calls)
            assertEquals(SyncStatus.REAUTH_REQUIRED, repository.account(pageAccount().key)?.status)
            assertEquals(SyncStatus.RATE_LIMITED, repository.account(youtubeAccount().key)?.status)
            assertTrue(repository.account(youtubeAccount().key)!!.nextAllowedAt!! > System.currentTimeMillis())
        } finally { database.close() }
    }
    @Test fun tokenReplacementAndLogoutFenceLateResults() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val account = pageAccount()
            repository.saveObservation(account, MetricSnapshot(account.key, 1, 5, null, source = "synthetic-before"))
            secrets.savePageToken("99", "fixture-page-token")
            var calls = 0
            val collector = SessionCollector(sessions, null, secrets, api {
                calls++; secrets.savePageToken("99", "replacement-fixture-token"); 200 to page(999)
            })
            val coordinator = SyncCoordinator(context, repository, collector, publishWidgets = {}, isDefinitelyOffline = { false }, sessionGuard = secrets::guard)
            coordinator.refresh(account.key, background = true)
            assertEquals(listOf(5L), repository.history(account.key).map { it.followers })
            secrets.clear(Provider.FACEBOOK); repository.markLoggedOut(Provider.FACEBOOK)
            coordinator.refresh(account.key, background = true)
            assertEquals(1, calls); assertTrue(repository.widgetOverviews().isEmpty())
            assertEquals(1, repository.history(account.key).size)
        } finally { database.close() }
    }
    @Test fun deviceKeystoreCredentialsSurviveRecreationAndMissingTokensDoNotFallBack() = runBlocking {
        secrets.savePageToken("99", "fixture-page-token")
        val encoded = context.getSharedPreferences("official_api", Context.MODE_PRIVATE).getString("FACEBOOK", null)!!
        assertFalse(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8).contains("fixture-page-token"))
        assertEquals("fixture-page-token", OfficialApiStore(context).pageToken("99"))
        secrets.clear(Provider.FACEBOOK)
        var calls = 0
        val collector = SessionCollector(sessions, null, secrets, api { calls++; 200 to page() })
        try { collector.native(Provider.FACEBOOK, pageAccount()); fail() }
        catch (e: CollectionFailure) { assertEquals(SyncStatus.REAUTH_REQUIRED, e.status) }
        assertEquals(0, calls)
    }
}
