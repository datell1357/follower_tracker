package dev.datell.followertracker.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeStudioTest {
    private val id = "UC" + "a".repeat(22)
    private fun payload(vararg overrides: Pair<String, JsonElement>) = buildJsonObject {
        put("provider", "YOUTUBE"); put("stableId", id); put("studioChannelId", id)
        put("username", id); put("displayName", "Fixture Channel")
        put("profileURL", "https://studio.youtube.com/channel/$id")
        put("followers", 0); put("following", JsonNull); put("precision", "EXACT"); put("source", "youtube-studio-dom")
        overrides.forEach { (key, value) -> put(key, value) }
    }.toString()

    @Test fun anExactStudioChannelKeepsItsIdentityHistoryAndBrowserRoute() {
        val (account, metric) = ResponseParser.youtubeStudioCapture(payload(), null, 100)
        assertEquals("YOUTUBE:$id", account.key); assertEquals("Fixture Channel", account.identityLabel)
        assertEquals("구독자", account.provider.metricLabel); assertEquals(0L, metric.followers)
        assertNull(metric.following); assertEquals(CountTransport.PROFILE_BROWSER, account.countTransport)
        assertTrue(RefreshPolicy.canRefresh(account, 100, background = true))
        assertEquals(100L, ResponseParser.youtubeStudioCapture(payload(), account, 200).first.connectedAt)
    }
    @Test fun publicCountsAnotherChannelAndFalsePrecisionCannotConnect() {
        for (change in listOf("profileURL" to JsonPrimitive("https://www.youtube.com/channel/$id"),
            "profileURL" to JsonPrimitive("https://studio.youtube.com.example.test/channel/$id"),
            "studioChannelId" to JsonPrimitive("UC" + "b".repeat(22)), "source" to JsonPrimitive("youtube-public-api"),
            "precision" to JsonPrimitive("ROUNDED"), "followers" to JsonPrimitive("1.2K"),
            "followers" to JsonPrimitive("12345"), "accountType" to JsonPrimitive("PAGE"),
            "followers" to JsonPrimitive(true), "followers" to JsonPrimitive(9_007_199_254_740_992L))) {
            try { ResponseParser.youtubeStudioCapture(payload(change), null, 100); fail(change.first) }
            catch (_: CollectionFailure) { }
        }
    }
    @Test fun persistedExistingProvidersRemainCompatible() {
        assertEquals(Provider.YOUTUBE, Json.decodeFromString<Provider>("\"YOUTUBE\""))
        assertEquals(Provider.INSTAGRAM, Json.decodeFromString<Provider>("\"INSTAGRAM\""))
        assertFalse(Provider.YOUTUBE.allows("https://accounts.google.com/"))
        assertTrue(Provider.YOUTUBE.allows("https://studio.youtube.com/channel/$id"))
    }
}
