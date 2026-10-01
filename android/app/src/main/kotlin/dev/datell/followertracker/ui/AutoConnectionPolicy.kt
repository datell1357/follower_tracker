package dev.datell.followertracker.ui

import dev.datell.followertracker.core.*
import java.net.URI

class AutoConnectionPolicy {
    private var attemptedPage: String? = null
    private var retryCount = 0
    var nextAllowedAt: Long = 0
        private set

    fun begin(pageKey: String, now: Long): Boolean {
        if (attemptedPage == pageKey || now < nextAllowedAt) return false
        attemptedPage = pageKey
        return true
    }

    fun failed(failure: CollectionFailure, now: Long) {
        val wait = when (failure.status) {
            SyncStatus.RATE_LIMITED -> (failure.retryAfterSeconds ?: 900).coerceIn(60, 86_400)
            SyncStatus.OFFLINE -> 30L
            else -> return
        }
        nextAllowedAt = now + wait * 1_000
        if (failure.status == SyncStatus.RATE_LIMITED || retryCount++ < 2) attemptedPage = null
    }

    fun requestRetry() { attemptedPage = null; retryCount = 0 }
}

fun canAutoConnect(provider: Provider, url: String, authenticated: Boolean, loading: Boolean, busy: Boolean): Boolean {
    if (!authenticated || loading || busy || !provider.allows(url)) return false
    val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
    return !Regex("(^|/)(login|challenge|checkpoint|two_factor|two-factor|twofactor)(/|$)").containsMatchIn(path)
}

fun connectionFailureMessage(failure: CollectionFailure): String = when (failure.status) {
    SyncStatus.RATE_LIMITED -> "SNS가 데이터 요청을 잠시 제한했어요. 로그인 창을 유지하면 대기 시간이 지난 뒤 다시 확인해요."
    SyncStatus.OFFLINE -> "데이터를 읽지 못했어요. 인터넷 연결을 확인해주세요."
    SyncStatus.REAUTH_REQUIRED -> "로그인을 완료해주세요. 완료되면 계정을 자동으로 연결해요."
    SyncStatus.CHECK_REQUIRED -> "SNS의 추가 인증을 완료하거나 로그인한 내 계정의 프로필을 열어주세요."
    SyncStatus.FORMAT_CHANGED -> "로그인 페이지에서 정확한 팔로워 수를 읽지 못했어요. 내 프로필에서 다시 확인해주세요."
    SyncStatus.FOREGROUND_ONLY -> "이 SNS는 로그인 창에서 내 프로필을 열어 갱신해요."
    SyncStatus.LIST_INCOMPLETE -> "명단을 끝까지 읽지 못했어요. 기존 명단을 유지했어요."
    else -> failure.status.label
}
