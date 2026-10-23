package dev.datell.followertracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*
import dev.datell.followertracker.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic service responses only. Never run against the user's installed application. */
@RunWith(AndroidJUnit4::class)
class ManagedApiRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val channelId = "UC" + "a".repeat(22)
    private val token = "t".repeat(43)
    private lateinit var store: ManagedApiStore
    @Before fun isolatedPackage() {
        check(context.packageName == "dev.datell.followertracker.managedvalidation")
        store = ManagedApiStore(context)
        store.clear(Provider.YOUTUBE); store.clear(Provider.FACEBOOK)
        store.installation("https://service.example", token)
    }
    private fun client(respond: (Request) -> String) = ManagedApiClient("https://service.example", store,
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(respond(chain.request()).toResponseBody()).build()
        }.build())
    private fun observation(payload: String, timestamp: Long) = """{"payload":$payload,"observedAt":$timestamp}"""
    private fun page(count: Long) = """{"id":"99","name":"Fixture Page","followers_count":$count}"""
    private fun channel(count: Long) = """{"items":[{"id":"$channelId","snippet":{"title":"Fixture channel"},"statistics":{"subscriberCount":"$count","hiddenSubscriberCount":false}}]}"""
    @Test fun serviceCollectorsRepeatWithoutCookiesAndKeepHistoryAndWidgetIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, DataCipher())
            val old = OfficialApiResponse.facebookPage(page(10), null, 100).first.copy(
                countTransport = CountTransport.PROFILE_BROWSER, sessionOwnerId = "42")
            repository.saveObservation(old, MetricSnapshot(old.key, 100, 10, null, source = "fixture-old"))
            var timestamp = System.currentTimeMillis() - 60000
            var calls = 0
            val api = client { request ->
                if (request.url.encodedPath.contains("disconnect") || request.url.encodedPath.contains("cancel")) "{}"
                else {
                    calls++; assertNull(request.header("Cookie"))
                    observation(if (request.url.encodedPath.contains("youtube")) channel(12300) else page(42 + calls.toLong()), timestamp++)
                }
            }
            api.flushRevocations()
            val updated = OfficialApiResponse.facebookPage(page(42), old, timestamp++)
            store.bind(updated.first); repository.saveObservation(updated.first, updated.second)
            val youtube = api.youtube("youtube.com/channel/$channelId", null)
            store.bind(youtube.first); repository.saveObservation(youtube.first, youtube.second)
            val sessions = SessionStore(context, DataCipher(), "managed_fixture_session")
            val collector = SessionCollector(sessions, managedStore = store, managedApi = api)
            val coordinator = SyncCoordinator(context, repository, collector, publishWidgets = {},
                isDefinitelyOffline = { false }, sessionGuard = { store.guard() })
            repeat(3) {
                coordinator.refresh(updated.first.key)
                coordinator.refresh(youtube.first.key)
            }
            assertEquals(7, calls)
            assertEquals(100L, repository.account(old.key)?.connectedAt)
            assertEquals(5, repository.history(old.key).size)
            assertEquals(2, repository.widgetOverviews().size)
            assertNull(sessions.metadata(Provider.FACEBOOK))
            assertNull(sessions.metadata(Provider.YOUTUBE))
            val ciphertext = context.getSharedPreferences("managed_api", 0).getString("state", "")!!
            assertFalse(ciphertext.contains(token))
            assertTrue(ManagedApiStore(context).owns(updated.first))
            store.clear(Provider.FACEBOOK); repository.markLoggedOut(Provider.FACEBOOK)
            assertEquals(1, repository.widgetOverviews().size)
            assertEquals(5, repository.history(old.key).size)
        } finally { database.close() }
    }
    @Test fun youtubeConnectionAsksOnlyForTheChannelLink() {
        var connected: String? = null
        compose.setContent { TrackerTheme { YouTubeChannelDialog("", false, null, {}, { connected = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("YouTube 채널 링크").performTextInput("https://www.youtube.com/@fixture")
        compose.onNodeWithText("연결", useUnmergedTree = true).performClick()
        assertEquals("https://www.youtube.com/@fixture", connected)
        compose.onNodeWithText("YouTube Data API 키").assertDoesNotExist()
    }
    @Test fun facebookSelectionNeedsOnePageTapAndNoTokenField() {
        var selected: String? = null
        compose.setContent { TrackerTheme { FacebookPageSelectionDialog(FacebookConnectionUi(
            pages = listOf(FacebookPageChoice("99", "Studio Fixture"), FacebookPageChoice("100", "Other Fixture")), waiting = false),
            false, null, {}, {}, { selected = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Studio Fixture").performClick()
        assertEquals("99", selected)
        compose.onNodeWithText("페이지 액세스 토큰").assertDoesNotExist()
    }
    @Test fun unconfiguredServiceShowsPreparationInsteadOfAKeyFormOrFakeConnection() {
        compose.setContent { TrackerTheme { YouTubeChannelDialog("https://www.youtube.com/@fixture",
            false, null, {}, { error("Must not connect") }, serviceAvailable = false) } }
        compose.onNodeWithText("연결").assertIsNotEnabled()
        compose.onNodeWithText("채널 연결 서비스를 준비하고 있어요.", substring = true).assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }
}
