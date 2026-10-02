package dev.datell.followertracker.sync

import android.os.SystemClock
import android.content.Context
import dev.datell.followertracker.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

interface SessionCollecting {
    suspend fun native(provider: Provider, expected: Account? = null): Pair<Account, MetricSnapshot>
    suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot>
}

class SessionCollector(private val sessions: SessionStore, private val context: Context? = null) : SessionCollecting {
    private val http = SessionHttpClient(sessions)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
        val metadata = sessions.metadata(provider) ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (!sessions.hasAuthentication(provider)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        val now = System.currentTimeMillis()
        val observation = when (provider) {
            Provider.INSTAGRAM -> {
                val id = sessions.identity(provider) ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
                if (expected != null && id != expected.stableId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                if (expected != null && RefreshPolicy.usesProfileBrowser(expected)) {
                    val browser = ProfilePageCollector(context ?: throw CollectionFailure(SyncStatus.FOREGROUND_ONLY), sessions)
                    val captured = captured(provider, browser.read(expected, metadata.userAgent), expected)
                    captured.first.copy(countTransport = CountTransport.PROFILE_BROWSER) to
                        captured.second.copy(source = "instagram-profile-browser")
                } else if (expected != null) {
                    val url = "https://www.instagram.com/api/v1/users/web_profile_info/".toHttpUrl().newBuilder()
                        .addQueryParameter("username", expected.username).build()
                    ResponseParser.instagramWebProfile(http.read(provider, url, metadata.userAgent), id, now)
                } else {
                    val url = "https://www.instagram.com/api/v1/users/".toHttpUrl().newBuilder()
                        .addPathSegment(id).addPathSegment("info").addPathSegment("").build()
                    ResponseParser.instagramProfile(http.read(provider, url, metadata.userAgent), id, now)
                }
            }
            Provider.REDDIT -> ResponseParser.redditProfile(http.read(provider,
                "https://www.reddit.com/api/me.json".toHttpUrl(), metadata.userAgent), expected?.stableId, now)
            Provider.TIKTOK -> {
                val account = expected ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                ResponseParser.tiktokProfile(http.read(provider, account.profileUrl.toHttpUrl(), metadata.userAgent), account.stableId, now)
            }
            Provider.X, Provider.FACEBOOK -> throw CollectionFailure(SyncStatus.FOREGROUND_ONLY)
        }
        currentCoroutineContext().ensureActive()
        val account = observation.first.copy(
            connectedAt = expected?.connectedAt ?: observation.first.connectedAt,
            status = SyncStatus.READY, lastAttemptAt = now,
            capabilities = (expected?.capabilities ?: Capabilities()).copy(count = Capability.OBSERVED,
                background = expected?.capabilities?.background?.takeIf { it != Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED))
        return account to observation.second.copy(observedAt = System.currentTimeMillis())
    }

    fun captured(provider: Provider, payload: String, expected: Account?): Pair<Account, MetricSnapshot> {
        val root = ResponseParser.objectBody(payload)
        if (root["error"] != null) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        fun text(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
        val id = text("stableId") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        if (expected != null && id != expected.stableId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val cookieIdentity = sessions.identity(provider)
        if (provider in setOf(Provider.INSTAGRAM, Provider.X, Provider.FACEBOOK) && id != cookieIdentity)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (!sessions.hasAuthentication(provider)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (text("provider") != provider.name) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val username = text("username") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val now = System.currentTimeMillis()
        val source = text("source") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val sessionResponse = source == "instagram-webview-session" && provider == Provider.INSTAGRAM ||
            source == "reddit-webview-session" && provider == Provider.REDDIT
        val account = Account(provider, id, username, text("displayName") ?: username,
            text("profileURL") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED),
            status = if (sessionResponse) SyncStatus.READY else SyncStatus.FOREGROUND_ONLY,
            capabilities = (expected?.capabilities ?: Capabilities()).copy(count = if (sessionResponse) Capability.OBSERVED else Capability.FOREGROUND_ONLY,
                background = if (sessionResponse) Capability.UNVERIFIED else Capability.FOREGROUND_ONLY), connectedAt = expected?.connectedAt ?: now, lastAttemptAt = now,
            countTransport = if (provider == Provider.INSTAGRAM && !sessionResponse) CountTransport.PROFILE_BROWSER else CountTransport.SESSION_HTTP)
        val followers = (root["followers"] as? JsonPrimitive)?.longOrNull ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val following = (root["following"] as? JsonPrimitive)?.longOrNull
        val metric = MetricSnapshot(account.key, now, followers, following, source = source)
        return account to metric
    }

    override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> {
        if (account.provider != Provider.INSTAGRAM) throw CollectionFailure(SyncStatus.FOREGROUND_ONLY)
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val before = native(account.provider, account).second
        val metadata = sessions.metadata(account.provider) ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        suspend fun collect(direction: Direction): List<Member> {
            val accumulator = PageAccumulator(account.key)
            var cursor: String? = null
            do {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - startElapsed > 180_000) throw CollectionFailure(SyncStatus.LIST_INCOMPLETE)
                if (sessions.identity(account.provider) != account.stableId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                val url = "https://www.instagram.com/api/v1/friendships/".toHttpUrl().newBuilder()
                    .addPathSegment(account.stableId).addPathSegment(direction.name.lowercase()).addPathSegment("")
                    .addQueryParameter("count", "100").apply { cursor?.let { addQueryParameter("max_id", it) } }.build()
                val page = ResponseParser.instagramPage(http.read(account.provider, url, metadata.userAgent), account.key)
                accumulator.append(page)
                cursor = page.nextCursor
            } while (cursor != null)
            return accumulator.finish()
        }
        val followers = collect(Direction.FOLLOWERS)
        val following = collect(Direction.FOLLOWING)
        val after = native(account.provider, account).second
        val counterStable = before.followers == after.followers && before.following == after.following
        // Terminal cursors alone cannot prove an unfiltered, complete list.
        if (!counterStable || followers.size.toLong() != after.followers || following.size.toLong() != after.following)
            throw CollectionFailure(SyncStatus.LIST_INCOMPLETE)
        val finishedAt = System.currentTimeMillis()
        fun snapshot(direction: Direction, members: List<Member>) = RelationshipSnapshot(account.key,
            direction, startedAt, finishedAt, members, complete = true, consistent = true,
            endReason = "terminal-cursor; counters-stable; non-atomic")
        return snapshot(Direction.FOLLOWERS, followers) to snapshot(Direction.FOLLOWING, following)
    }
}
