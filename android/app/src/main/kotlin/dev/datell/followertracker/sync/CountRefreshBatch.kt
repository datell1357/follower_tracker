package dev.datell.followertracker.sync

import dev.datell.followertracker.core.Account
import dev.datell.followertracker.core.Provider
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
