package dev.datell.followertracker.core

import java.math.BigDecimal
import java.net.URI
import kotlinx.serialization.json.*

/** Public channel headers only: no cookies, Studio credentials, API keys or recommended-video counts. */
object YouTubePublicChannel {
    data class Target(val url: String, val id: String?, val handle: String?)
    private val channelId = Regex("UC[A-Za-z0-9_-]{22}")

    fun target(input: String): Target? = runCatching {
        val value = input.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443))
        require(uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com"))
        val path = uri.path.trimEnd('/')
        val id = path.removePrefix("/channel/").takeIf { path.startsWith("/channel/") && channelId.matches(it) }
        val handle = path.removePrefix("/@").takeIf { path.startsWith("/@") && it.length in 1..100 &&
            it.none { c -> c.isWhitespace() || c in "/?#%\\" } }
        require(id != null || handle != null)
        Target(URI("https", null, "www.youtube.com", -1, if (id != null) "/channel/$id" else "/@$handle", null, null).toASCIIString(), id, handle)
    }.getOrNull()

    fun observation(html: String, target: Target, expected: Account?, now: Long): Pair<Account, MetricSnapshot> {
        if (html.length > 8 * 1024 * 1024) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val marker = Regex("(?:var\\s+ytInitialData\\s*=\\s*|window\\[\"ytInitialData\"\\]\\s*=\\s*)").find(html)
            ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0 || start - marker.range.last > 16) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        var depth = 0; var quoted = false; var escaped = false; var end = -1
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{' -> depth++; '}' -> if (--depth == 0) { end = i + 1; break } }
        }
        val root = if (end > start) runCatching { Json.parseToJsonElement(html.substring(start, end)).jsonObject }.getOrNull() else null
        root ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        fun JsonObject.objectAt(vararg keys: String): JsonObject? = keys.fold(this as JsonObject?) { value, key -> value?.get(key) as? JsonObject }
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        val metadata = root.objectAt("metadata", "channelMetadataRenderer") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val id = metadata.text("externalId")?.takeIf(channelId::matches) ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val name = metadata.text("title")?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val canonical = "https://www.youtube.com/channel/$id"
        if (metadata.text("channelUrl") != canonical || target.id?.let { it != id } == true ||
            expected?.let { it.provider != Provider.YOUTUBE || it.stableId != id || it.accountType != AccountType.PROFILE || it.loggedOut } == true)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val vanity = metadata.text("vanityChannelUrl")?.replaceFirst("http://", "https://")?.let(YouTubePublicChannel::target)
        if (target.handle != null && !target.handle.equals(vanity?.handle, ignoreCase = true)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val header = root["header"] as? JsonObject ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val labels = mutableSetOf<String>()
        // Only the target channel header is eligible, never recommendations or a video's author.
        fun visit(element: JsonElement, level: Int = 0) {
            if (level > 24) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "subscriberCountText") {
                        val text = value as? JsonObject
                        text?.text("simpleText")?.let(labels::add)
                        (text?.get("runs") as? JsonArray)?.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }
                            ?.takeIf(String::isNotBlank)?.let(labels::add)
                    }
                    if (key == "content" && value is JsonPrimitive && value.isString && value.content.length <= 100 &&
                        (value.content.matches(Regex("[0-9.,KMB]+ subscribers?")) || value.content == "No subscribers")) labels.add(value.content)
                    visit(value, level + 1)
                }
                is JsonArray -> element.forEach { visit(it, level + 1) }
                else -> Unit
            }
        }
        header.objectAt("pageHeaderRenderer", "content", "pageHeaderViewModel", "metadata")?.let { visit(it) }
        header.objectAt("c4TabbedHeaderRenderer")?.get("subscriberCountText")?.let { text ->
            visit(buildJsonObject { put("subscriberCountText", text) })
        }
        val counts = labels.mapNotNull(::count).toSet()
        if (counts.size != 1 || labels.size != 1) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val (followers, precision) = counts.single()
        val account = Account(Provider.YOUTUBE, id, vanity?.handle ?: id, name, canonical,
            connectedAt = expected?.connectedAt ?: now, lastAttemptAt = now, countTransport = CountTransport.PUBLIC_CHANNEL,
            capabilities = Capabilities(count = Capability.OBSERVED, background = expected?.capabilities?.background?.takeUnless { it == Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED,
                followers = Capability.UNAVAILABLE, following = Capability.UNAVAILABLE))
        return account to MetricSnapshot(account.key, now, followers, null, precision, "youtube-public-channel")
    }

    private fun count(label: String): Pair<Long, Precision>? = runCatching {
        if (label == "No subscribers") return 0L to Precision.EXACT
        val match = Regex("^([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)([KMB]?) subscribers?$").matchEntire(label) ?: return null
        val suffix = match.groupValues[2]
        val scale = when (suffix) { "K" -> 3; "M" -> 6; "B" -> 9; else -> 0 }
        val value = BigDecimal(match.groupValues[1].replace(",", "")).movePointRight(scale).longValueExact()
        require(value in 0..9_007_199_254_740_991L)
        require(suffix.isNotEmpty() || !match.groupValues[1].contains('.'))
        value to if (suffix.isEmpty() && value < 1000) Precision.EXACT else Precision.ROUNDED
    }.getOrNull()
}
