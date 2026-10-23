package dev.datell.followertracker.sync

import android.os.SystemClock
import android.content.Context
import dev.datell.followertracker.core.*
import dev.datell.followertracker.diagnostics.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

interface SessionCollecting {
    suspend fun native(provider: Provider, expected: Account? = null): Pair<Account, MetricSnapshot>
    suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot>
}

class SessionCollector(private val sessions: SessionStore, private val context: Context? = null,
    private val officialStore: OfficialApiStore? = null, private val officialApi: OfficialApiClient = OfficialApiClient(),
    private val managedStore: ManagedApiStore? = null, private val managedApi: ManagedApiClient? = null) : SessionCollecting {
    private val http = SessionHttpClient(sessions)
    private val publicYouTube = PublicYouTubeClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val profileBrowser by lazy { context?.let { ProfilePageCollector(it, sessions) } }

    fun releaseProfileResources(provider: Provider? = null) { profileBrowser?.release(provider) }
    suspend fun cancelProfileSession(provider: Provider) { profileBrowser?.cancelSession(provider) }

    override suspend fun native(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> =
        CollectionMeter.measure(provider, "count") { collectNative(provider, expected) }

    suspend fun connectPublicYouTube(address: String, expected: Account?): Pair<Account, MetricSnapshot> =
        CollectionMeter.measure(Provider.YOUTUBE, "count") {
            val key = officialStore?.youtubeKey()
            if (key != null) officialApi.youtube(address, key, expected)
            else if (expected?.countTransport == CountTransport.OFFICIAL_API) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            else publicYouTube.read(address, expected)
        }

    suspend fun connectOfficialYouTube(address: String, key: String, expected: Account?) =
        CollectionMeter.measure(Provider.YOUTUBE, "count") { officialApi.youtube(address, key, expected) }

    suspend fun connectOfficialFacebook(token: String, expected: Account?) =
        CollectionMeter.measure(Provider.FACEBOOK, "count") { officialApi.facebookPage(token, expected, connecting = true) }

    private suspend fun collectNative(provider: Provider, expected: Account?): Pair<Account, MetricSnapshot> {
        if (expected?.countTransport == CountTransport.OFFICIAL_API) {
            if (expected.loggedOut || expected.provider != provider) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            if (managedStore?.owns(expected) == true)
                return managedApi?.read(expected) ?: throw CollectionFailure(SyncStatus.OFFLINE)
            // No HTML/cookie fallback: it would bypass an API quota and double the network work.
            return when (provider) {
                Provider.YOUTUBE -> officialApi.youtube(expected.profileUrl,
                    officialStore?.youtubeKey() ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED), expected)
                Provider.FACEBOOK -> officialApi.facebookPage(
                    officialStore?.pageToken(expected.stableId) ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED), expected)
                else -> throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            }
        }
        if (provider == Provider.YOUTUBE && expected?.countTransport == CountTransport.PUBLIC_CHANNEL) {
            if (expected.loggedOut) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            return publicYouTube.read(expected.profileUrl, expected)
        }
        if (provider == Provider.FACEBOOK && expected?.accountType == AccountType.PAGE && profileBrowser != null) {
            if (expected.loggedOut) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            try {
                val payload = profileBrowser!!.readPublicPage(expected)
                val captured = ResponseParser.facebookPageCapture(payload, expected.authenticationOwnerId,
                    expected, System.currentTimeMillis())
                return captured.first.copy(status = SyncStatus.READY,
                    capabilities = captured.first.capabilities.copy(count = Capability.OBSERVED,
                        background = expected.capabilities.background.takeUnless { it == Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED)) to
                    captured.second.copy(source = "facebook-page-public-document")
            } catch (failure: CollectionFailure) {
                // Preserve the authenticated collector for restricted Pages, but always honor server cooldowns.
                if (failure.status == SyncStatus.RATE_LIMITED) throw failure
            }
        }
        val attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
        fun rejectSession(stage: DiagnosticStage): Nothing {
            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.READ_FAILURE, provider, expected?.accountType,
                attempt = attempt, stage = stage, status = SyncStatus.REAUTH_REQUIRED))
            throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        }
        val metadata = sessions.metadata(provider) ?: rejectSession(DiagnosticStage.SESSION_METADATA)
        if (!sessions.hasAuthentication(provider)) rejectSession(DiagnosticStage.SESSION_AUTH)
        val now = System.currentTimeMillis()
        suspend fun readProfile(account: Account): Pair<Account, MetricSnapshot> {
            val browser = profileBrowser ?: throw CollectionFailure(SyncStatus.FOREGROUND_ONLY)
            val captured = captured(provider, browser.read(account, metadata.userAgent,
                retain = RapidTracking.state.value.running), account, facebookPage = account.accountType == AccountType.PAGE)
            currentCoroutineContext().ensureActive()
            return captured.first.copy(status = SyncStatus.READY, countTransport = CountTransport.PROFILE_BROWSER,
                capabilities = captured.first.capabilities.copy(count = Capability.OBSERVED,
                    background = account.capabilities.background.takeIf { it != Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED)) to
                captured.second.copy(observedAt = System.currentTimeMillis(), source =
                    if (account.accountType == AccountType.PAGE) "facebook-page-browser" else "${provider.name.lowercase()}-profile-browser")
        }
        if (expected != null && RefreshPolicy.usesProfileBrowser(expected)) return readProfile(expected)
        val observation = collectWithSessionRecovery(expected, now,
            sessionStillCurrent = { sessions.metadata(provider)?.savedAt == metadata.savedAt && sessions.hasAuthentication(provider) &&
                expected != null && sessions.identity(provider) == expected.authenticationOwnerId },
            browser = if (profileBrowser == null) null else ::readProfile,
            primary = { when (provider) {
            Provider.INSTAGRAM -> {
                val id = sessions.identity(provider) ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
                if (expected != null && id != expected.stableId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
                if (expected != null) {
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
            Provider.X, Provider.FACEBOOK, Provider.YOUTUBE -> throw CollectionFailure(SyncStatus.FOREGROUND_ONLY)
        } })
        currentCoroutineContext().ensureActive()
        if (observation.first.countTransport == CountTransport.PROFILE_BROWSER) return observation
        val account = observation.first.copy(
            connectedAt = expected?.connectedAt ?: observation.first.connectedAt,
            status = SyncStatus.READY, lastAttemptAt = now,
            capabilities = (expected?.capabilities ?: Capabilities()).copy(count = Capability.OBSERVED,
                background = expected?.capabilities?.background?.takeIf { it != Capability.FOREGROUND_ONLY } ?: Capability.UNVERIFIED))
        return account to observation.second.copy(observedAt = System.currentTimeMillis())
    }

    fun captured(provider: Provider, payload: String, expected: Account?, facebookPage: Boolean = false): Pair<Account, MetricSnapshot> {
        if (provider == Provider.YOUTUBE) {
            if (facebookPage || !sessions.hasAuthentication(provider)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
            return ResponseParser.youtubeStudioCapture(payload, expected, System.currentTimeMillis())
        }
        if (facebookPage) {
            if (provider != Provider.FACEBOOK) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            if (!sessions.hasAuthentication(provider)) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
            return ResponseParser.facebookPageCapture(payload, sessions.identity(provider), expected, System.currentTimeMillis())
        }
        val root = ResponseParser.objectBody(payload)
        if (root["error"] != null) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (root["accountType"]?.jsonPrimitive?.contentOrNull == AccountType.PAGE.name || expected?.accountType == AccountType.PAGE)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
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
            countTransport = if (!sessionResponse) CountTransport.PROFILE_BROWSER else CountTransport.SESSION_HTTP)
        val followers = (root["followers"] as? JsonPrimitive)?.longOrNull ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val following = (root["following"] as? JsonPrimitive)?.longOrNull
        val metric = MetricSnapshot(account.key, now, followers, following, source = source)
        return account to metric
    }

    override suspend fun relationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> =
        CollectionMeter.measure(account.provider, "relationships") { collectRelationships(account) }

    private suspend fun collectRelationships(account: Account): Pair<RelationshipSnapshot, RelationshipSnapshot> {
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
