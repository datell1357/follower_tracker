package dev.datell.followertracker.core

object RefreshPolicy {
    fun usesProfileBrowser(account: Account): Boolean = account.accountType == AccountType.PROFILE &&
        (account.countTransport == CountTransport.PROFILE_BROWSER || account.status == SyncStatus.FOREGROUND_ONLY ||
            account.capabilities.count == Capability.FOREGROUND_ONLY)

    fun supportsCountRefresh(account: Account): Boolean = usesProfileBrowser(account) ||
        (account.accountType == AccountType.PROFILE &&
            account.provider in setOf(Provider.INSTAGRAM, Provider.TIKTOK, Provider.REDDIT))

    fun canRefresh(account: Account, now: Long, background: Boolean): Boolean {
        if ((account.nextAllowedAt ?: 0) > now) return false
        if (!supportsCountRefresh(account)) return false
        if (!background) return true
        if ((account.transientRetry?.nextAttemptAt ?: 0) > now) return false
        if (account.status.blocksAutomaticRetry) return false
        if (account.status == SyncStatus.FOREGROUND_ONLY && !usesProfileBrowser(account)) return false
        return true
    }

    fun nextTransientRetry(account: Account, failedAt: Long): TransientRetryState {
        val count = (account.transientRetry?.failureCount ?: 0).coerceIn(0, 4) + 1
        val delay = minOf(900_000L, 60_000L shl (count - 1))
        return TransientRetryState(count, saturatedDeadline(failedAt, delay))
    }

    fun serviceRetryAt(failure: CollectionFailure, failedAt: Long): Long? {
        val seconds = when (failure.status) {
            SyncStatus.RATE_LIMITED -> failure.retryAfterSeconds ?: 900L
            SyncStatus.OFFLINE -> failure.retryAfterSeconds
            else -> null
        } ?: return null
        val delay = seconds.coerceAtLeast(0).let { if (it > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else it * 1_000 }
        return saturatedDeadline(failedAt, delay)
    }

    private fun saturatedDeadline(now: Long, delay: Long): Long =
        if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
}
