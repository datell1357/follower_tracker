package dev.datell.followertracker.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

/** Only the requested official resource may create an observation. Missing values are never zero. */
object OfficialApiResponse {
    fun youtube(body: String, target: YouTubePublicChannel.Target, expected: Account?, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        val items = root["items"] as? JsonArray ?: malformed()
        if (items.size != 1) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val item = items.single() as? JsonObject ?: malformed()
        val id = item.text("id")?.takeIf { it.matches(Regex("UC[A-Za-z0-9_-]{22}")) } ?: malformed()
        if (target.id?.let { it != id } == true || expected?.let { it.provider != Provider.YOUTUBE ||
                it.accountType != AccountType.PROFILE || it.stableId != id || it.loggedOut } == true)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val stats = item["statistics"] as? JsonObject ?: malformed()
        // Require an explicit false, including when the returned count happens to be zero.
        if (stats["hiddenSubscriberCount"] != JsonPrimitive(false)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val count = count(stats["subscriberCount"])
        val snippet = item["snippet"] as? JsonObject
        val name = snippet?.text("title")?.let(::name) ?: expected?.displayName ?: malformed()
        val account = Account(Provider.YOUTUBE, id, target.handle ?: expected?.username ?: id, name,
            "https://www.youtube.com/channel/$id", connectedAt = expected?.connectedAt ?: now,
            lastAttemptAt = now, countTransport = CountTransport.OFFICIAL_API, capabilities = capabilities(expected))
        return account to MetricSnapshot(account.key, now, count, null,
            if (count < 1000) Precision.EXACT else Precision.ROUNDED, "youtube-data-api-v3")
    }

    fun facebookPage(body: String, expected: Account?, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        val id = root.text("id")?.takeIf { it.matches(Regex("[0-9]{1,32}")) } ?: malformed()
        if (expected?.let { it.provider != Provider.FACEBOOK || it.accountType != AccountType.PAGE ||
                it.stableId != id || it.loggedOut } == true) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val count = count(root["followers_count"])
        val name = name(root.text("name") ?: malformed())
        val account = Account(Provider.FACEBOOK, id, id, name, "https://www.facebook.com/$id",
            connectedAt = expected?.connectedAt ?: now, lastAttemptAt = now,
            accountType = AccountType.PAGE, countTransport = CountTransport.OFFICIAL_API,
            capabilities = capabilities(expected))
        return account to MetricSnapshot(account.key, now, count, null, Precision.EXACT, "facebook-graph-api-v26")
    }

    /** Service errors are classified without retaining their message, request URL or token. */
    fun failure(provider: Provider, status: Int, body: String, retryAfter: String?, now: Long): CollectionFailure? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val code = (error?.get("code") as? JsonPrimitive)?.intOrNull
        val reasons = (error?.get("errors") as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.text("reason")
        }.toSet()
        val retry = retrySeconds(retryAfter, now)
        if (provider == Provider.YOUTUBE && reasons.any { it in setOf("quotaExceeded", "dailyLimitExceeded", "dailyLimitExceededUnreg") }) {
            val current = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/Los_Angeles"))
            val reset = current.toLocalDate().plusDays(1).atStartOfDay(current.zone).toInstant().toEpochMilli()
            return CollectionFailure(SyncStatus.RATE_LIMITED, maxOf(retry ?: 0, (reset - now + 999) / 1000))
        }
        if (provider == Provider.FACEBOOK && code in setOf(102, 190)) return CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (status == 429 || provider == Provider.FACEBOOK && code in setOf(4, 17, 32, 613) ||
            reasons.any { it in setOf("rateLimitExceeded", "userRateLimitExceeded") })
            return CollectionFailure(SyncStatus.RATE_LIMITED, retry ?: 900)
        if (status >= 500 || error?.get("is_transient") == JsonPrimitive(true))
            return CollectionFailure(SyncStatus.OFFLINE, retry)
        if (status == 401) return CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (status !in 200..299 || root?.containsKey("error") == true) return CollectionFailure(SyncStatus.CHECK_REQUIRED)
        return null
    }

    private fun retrySeconds(value: String?, now: Long): Long? = value?.toLongOrNull()?.coerceIn(1, 86_400)
        ?: value?.let { runCatching { ((ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - now + 999) / 1000).coerceIn(1, 86_400) }.getOrNull() }
    private fun objectBody(body: String): JsonObject = if (body.length > 65_536) malformed() else
        (runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: malformed()).also {
            if (it.containsKey("error")) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun name(value: String): String = value.trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: malformed()
    private fun count(value: JsonElement?): Long {
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: malformed()
        if (!raw.matches(Regex("0|[1-9][0-9]*"))) malformed()
        return raw.toLongOrNull()?.takeIf { it <= 9_007_199_254_740_991L } ?: malformed()
    }
    private fun capabilities(expected: Account?) = Capabilities(count = Capability.OBSERVED,
        background = expected?.capabilities?.background?.takeUnless { it == Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED,
        followers = Capability.UNAVAILABLE, following = Capability.UNAVAILABLE)
    private fun malformed(): Nothing = throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
}
