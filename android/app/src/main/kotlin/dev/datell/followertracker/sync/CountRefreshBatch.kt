package dev.datell.followertracker.sync

import dev.datell.followertracker.core.Account
import dev.datell.followertracker.core.Provider
import dev.datell.followertracker.core.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Preserve per-account failures while allowing later accounts to run. Cancellation and fatal errors propagate. */
internal suspend fun refreshCountBatch(accounts: Iterable<Account>, refresh: suspend (Account) -> Unit): Set<Provider> {
    val failures = mutableSetOf<Provider>()
    for (account in accounts) {
        currentCoroutineContext().ensureActive()
        try { refresh(account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures += account.provider }
    }
    currentCoroutineContext().ensureActive()
    return failures
}

/** A fresh result from one account must not hide another account's stored failure. */
internal fun countBatchMessage(statuses: List<SyncStatus>, fresh: Boolean, unexpectedFailure: Boolean): String = when {
    statuses.all { it.blocksAutomaticRetry } -> "연결 상태를 확인한 뒤 다시 시작해주세요."
    SyncStatus.RATE_LIMITED in statuses -> "SNS 요청 제한 · 대기 후 다시 확인해요"
    SyncStatus.OFFLINE in statuses -> "일시 오류 · 잠시 후 다시 확인해요"
    statuses.any { it.blocksAutomaticRetry } -> "일부 SNS 연결 확인 필요 · 가능한 계정은 계속 추적해요"
    unexpectedFailure || SyncStatus.FORMAT_CHANGED in statuses -> "일부 SNS 갱신 오류 · 다른 계정은 계속 추적해요"
    fresh -> "수집 완료 · 1분 간격으로 확인해요"
    else -> "마지막 기록을 유지하고 있어요"
}
