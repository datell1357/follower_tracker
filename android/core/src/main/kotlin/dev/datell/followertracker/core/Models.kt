package dev.datell.followertracker.core

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
enum class Provider(val title: String, val loginUrl: String, val domain: String) {
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", "instagram.com"),
    TIKTOK("TikTok", "https://www.tiktok.com/login", "tiktok.com"),
    X("X", "https://x.com/i/flow/login", "x.com"),
    FACEBOOK("Facebook", "https://www.facebook.com/login/", "facebook.com"),
    REDDIT("Reddit", "https://www.reddit.com/login/", "reddit.com");

    fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == domain || host.endsWith(".$domain"))
    }.getOrDefault(false)
}

@Serializable
enum class SyncStatus(val label: String) {
    READY("갱신 완료"), REFRESHING("갱신 중"), REAUTH_REQUIRED("다시 로그인 필요"),
    CHECK_REQUIRED("연결 확인 필요"), RATE_LIMITED("갱신 대기"), OFFLINE("일시 오류"),
    FORMAT_CHANGED("수집 경로 확인 필요"), FOREGROUND_ONLY("앱에서 갱신"),
    LIST_INCOMPLETE("명단 갱신 미완료");

    val blocksAutomaticRetry: Boolean
        get() = this in setOf(REAUTH_REQUIRED, CHECK_REQUIRED, FORMAT_CHANGED)
}

@Serializable
enum class Precision { EXACT, ROUNDED, ESTIMATED }

@Serializable
enum class Capability { UNVERIFIED, OBSERVED, FOREGROUND_ONLY, UNAVAILABLE }

@Serializable
enum class CountTransport { SESSION_HTTP, PROFILE_BROWSER }

@Serializable
data class TransientRetryState(val failureCount: Int, val nextAttemptAt: Long)

@Serializable
data class Capabilities(
    val count: Capability = Capability.UNVERIFIED,
    val followers: Capability = Capability.UNVERIFIED,
    val following: Capability = Capability.UNVERIFIED,
    val background: Capability = Capability.UNVERIFIED,
)

@Serializable
data class Account(
    val provider: Provider,
    val stableId: String,
    val username: String,
    val displayName: String = username,
    val profileUrl: String,
    val status: SyncStatus = SyncStatus.READY,
    val capabilities: Capabilities = Capabilities(),
    val connectedAt: Long,
    val lastAttemptAt: Long? = null,
    val nextAllowedAt: Long? = null,
    val relationshipStatus: SyncStatus? = null,
    val countTransport: CountTransport = CountTransport.SESSION_HTTP,
    val transientRetry: TransientRetryState? = null,
) {
    val key: String get() = "${provider.name}:$stableId"
    init {
        require(stableId.isNotBlank() && username.isNotBlank())
        require(provider.allows(profileUrl))
    }
}

@Serializable
data class MetricSnapshot(
    val accountKey: String,
    val observedAt: Long,
    val followers: Long,
    val following: Long?,
    val precision: Precision = Precision.EXACT,
    val source: String,
    val adapterVersion: Int = 1,
) {
    init { require(followers >= 0 && (following == null || following >= 0)) }
}

@Serializable
enum class Direction { FOLLOWERS, FOLLOWING }

@Serializable
data class Member(val id: String, val username: String, val displayName: String = username) {
    init { require(id.isNotBlank() && username.isNotBlank()) }
}

@Serializable
data class RelationshipSnapshot(
    val accountKey: String,
    val direction: Direction,
    val startedAt: Long,
    val finishedAt: Long,
    val members: List<Member>,
    val complete: Boolean,
    val consistent: Boolean,
    val endReason: String,
) {
    init { require(finishedAt >= startedAt) }
}

data class RelationshipPage(
    val ownerKey: String,
    val members: List<Member>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

class CollectionFailure(val status: SyncStatus, val retryAfterSeconds: Long? = null) :
    Exception(status.name)

fun statusForHttp(code: Int, loginResponse: Boolean = false): SyncStatus? = when {
    loginResponse || code == 401 -> SyncStatus.REAUTH_REQUIRED
    code == 403 -> SyncStatus.CHECK_REQUIRED
    code == 429 -> SyncStatus.RATE_LIMITED
    code >= 500 -> SyncStatus.OFFLINE
    code !in 200..299 -> SyncStatus.CHECK_REQUIRED
    else -> null
}

fun exactDisplayedCount(text: String): Long? {
    // A shortened or translated magnitude must never become an exact count.
    if (!Regex("^[0-9][0-9,\\s\\u00A0\\u202F]*$").matches(text.trim())) return null
    return text.filter(Char::isDigit).toLongOrNull()
}
