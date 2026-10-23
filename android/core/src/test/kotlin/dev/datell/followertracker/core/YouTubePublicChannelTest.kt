package dev.datell.followertracker.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class YouTubePublicChannelTest {
    private val id = "UC" + "a".repeat(22)
    private val target = YouTubePublicChannel.target("https://www.youtube.com/@fixture")!!
    private fun document(label: String, metadataId: String = id, handle: String = "fixture", another: String? = null): String {
        val root = buildJsonObject {
            put("metadata", buildJsonObject { put("channelMetadataRenderer", buildJsonObject {
                put("title", "Fixture Channel"); put("externalId", metadataId)
                put("channelUrl", "https://www.youtube.com/channel/$metadataId"); put("vanityChannelUrl", "http://www.youtube.com/@$handle")
            }) })
            put("header", buildJsonObject { put("c4TabbedHeaderRenderer", buildJsonObject {
                put("subscriberCountText", buildJsonObject { put("simpleText", label) })
                another?.let { put("ignoredButton", buildJsonObject { put("content", it) }) }
            }) })
            put("contents", buildJsonObject { put("content", "999M subscribers") })
        }
        return "<html><script>var ytInitialData = $root;</script></html>"
    }
    @Test fun anonymousCountsKeepPublicPrecisionAndTheTargetChannelIdentity() {
        for ((label, count, precision) in listOf(Triple("0 subscribers", 0L, Precision.EXACT),
            Triple("No subscribers", 0L, Precision.EXACT), Triple("999 subscribers", 999L, Precision.EXACT),
            Triple("4.39K subscribers", 4390L, Precision.ROUNDED), Triple("4.4K subscribers", 4400L, Precision.ROUNDED),
            Triple("1.23M subscribers", 1230000L, Precision.ROUNDED), Triple("1,000 subscribers", 1000L, Precision.ROUNDED))) {
            val (account, metric) = YouTubePublicChannel.observation(document(label, another = "1M subscribers"), target, null, 100)
            assertEquals("YOUTUBE:$id", account.key); assertEquals("fixture", account.username)
            assertEquals(count, metric.followers); assertEquals(precision, metric.precision)
            assertEquals(CountTransport.PUBLIC_CHANNEL, account.countTransport)
            assertTrue(RefreshPolicy.canRefresh(account, 100, background = true))
            if (precision != Precision.EXACT) assertNull(MetricComparison.between(metric.copy(observedAt = 99), metric))
            assertEquals(100L, YouTubePublicChannel.observation(document(label), target, account, 200).first.connectedAt)
        }
    }
    @Test fun theCurrentMetadataHeaderShapeIsSupportedWithoutReadingOtherPageContent() {
        val legacy = Json.parseToJsonElement(document("4.39K subscribers").substringAfter("var ytInitialData = ").substringBefore(";</script>")).jsonObject
        val modern = Json.parseToJsonElement("""{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"4.39K subscribers"}}]}]}}}}}}""")
        val root = JsonObject(legacy + ("header" to modern))
        assertEquals(4390L, YouTubePublicChannel.observation("var ytInitialData = $root;", target, null, 1).second.followers)
    }
    @Test fun foreignPagesOwnerChangesMalformedLabelsAndRecommendedCountsCannotBecomeObservations() {
        for (url in listOf("https://youtube.com.evil.test/@fixture", "https://studio.youtube.com/channel/$id", "https://accounts.google.com/",
            "http://youtube.com/@fixture", "https://private@youtube.com/@fixture", "https://youtube.com/watch?v=fixture", "https://youtu.be/fixture"))
            assertNull(YouTubePublicChannel.target(url))
        for (label in listOf("4.39K views", "4.3.9K subscribers", "-1 subscribers", "1.5 subscribers", "", "not available")) {
            try { YouTubePublicChannel.observation(document(label), target, null, 1); fail(label) } catch (_: CollectionFailure) { }
        }
        val account = YouTubePublicChannel.observation(document("4.39K subscribers"), target, null, 1).first
        for (html in listOf(document("4.39K subscribers", handle = "other"), document("4.39K subscribers", metadataId = "UC" + "b".repeat(22)))) {
            try { YouTubePublicChannel.observation(html, target, account, 2); fail() } catch (_: CollectionFailure) { }
        }
        assertEquals("https://www.youtube.com/@fixture", YouTubePublicChannel.target("youtube.com/@fixture?si=share")?.url)
        assertFalse(RefreshPolicy.canRefresh(account.copy(loggedOut = true), 2, background = true))
    }
}
