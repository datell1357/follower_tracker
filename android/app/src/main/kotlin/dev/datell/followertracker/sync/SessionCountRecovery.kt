package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded recovery read takes priority over saving a request while the same login is valid. */
internal suspend fun collectWithSessionRecovery(
    account: Account?, now: Long, sessionStillCurrent: () -> Boolean,
    primary: suspend () -> Pair<Account, MetricSnapshot>,
    browser: (suspend (Account) -> Pair<Account, MetricSnapshot>)?,
): Pair<Account, MetricSnapshot> {
    try { return primary() }
    catch (failure: CollectionFailure) {
        currentCoroutineContext().ensureActive()
        if (browser == null || account == null || account.loggedOut || account.accountType != AccountType.PROFILE ||
            account.provider !in setOf(Provider.INSTAGRAM, Provider.TIKTOK) ||
            RefreshPolicy.usesProfileBrowser(account) || failure.status != SyncStatus.FORMAT_CHANGED ||
            (account.nextAllowedAt ?: 0) > now || !sessionStillCurrent()) throw failure
        val recovered = browser(account)
        currentCoroutineContext().ensureActive()
        if (!sessionStillCurrent() || recovered.first.key != account.key || recovered.second.accountKey != account.key ||
            recovered.second.precision != Precision.EXACT) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        return recovered
    }
}
