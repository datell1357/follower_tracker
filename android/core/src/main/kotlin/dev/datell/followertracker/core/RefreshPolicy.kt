package dev.datell.followertracker.core

object RefreshPolicy {
    fun usesProfileBrowser(account: Account): Boolean = account.provider == Provider.INSTAGRAM &&
        (account.countTransport == CountTransport.PROFILE_BROWSER || account.status == SyncStatus.FOREGROUND_ONLY ||
            account.capabilities.count == Capability.FOREGROUND_ONLY)

    fun canRefresh(account: Account, now: Long, background: Boolean): Boolean {
        if ((account.nextAllowedAt ?: 0) > now) return false
        if (!background) return true
        if (account.status.blocksAutomaticRetry) return false
        if (account.status == SyncStatus.FOREGROUND_ONLY && !usesProfileBrowser(account)) return false
        return account.provider !in setOf(Provider.X, Provider.FACEBOOK)
    }
}
