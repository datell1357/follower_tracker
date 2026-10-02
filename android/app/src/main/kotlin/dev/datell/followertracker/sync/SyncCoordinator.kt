package dev.datell.followertracker.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.TrackerRepository
import dev.datell.followertracker.widget.TrackerWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncCoordinator(context: Context, private val repository: TrackerRepository, private val collector: SessionCollecting,
    private val publishWidgets: suspend () -> Unit = { TrackerWidget().updateAll(context) }) {
    private val mutex = Mutex()
    suspend fun refresh(key: String, background: Boolean = false) = mutex.withLock {
        val account = repository.account(key) ?: return@withLock
        val now = System.currentTimeMillis()
        if (!RefreshPolicy.canRefresh(account, now, background)) return@withLock
        try {
            repository.updateStatus(key, SyncStatus.REFRESHING, now, account.nextAllowedAt,
                expectedConnectedAt = account.connectedAt,
                transientRetry = account.transientRetry)
            try {
                val (updated, metric) = collector.native(account.provider, account)
                val capabilities = if (background) updated.capabilities.copy(background = Capability.OBSERVED) else updated.capabilities
                repository.saveObservation(updated.copy(capabilities = capabilities), metric, requireExisting = true)
            } catch (failure: CollectionFailure) {
                val failedAt = System.currentTimeMillis()
                repository.updateStatus(key, failure.status, now, RefreshPolicy.serviceRetryAt(failure, failedAt),
                    expectedConnectedAt = account.connectedAt,
                    transientRetry = if (failure.status == SyncStatus.OFFLINE) RefreshPolicy.nextTransientRetry(account, failedAt) else null)
            }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    repository.updateStatus(key, account.status, now, account.nextAllowedAt,
                        expectedConnectedAt = account.connectedAt, transientRetry = account.transientRetry)
                }
            } catch (restorationFailure: Exception) { failure.addSuppressed(restorationFailure) }
            throw failure
        }
        publishWidgets()
    }
    suspend fun relationships(key: String, background: Boolean = false) = mutex.withLock {
        val account = repository.account(key) ?: return@withLock
        val now = System.currentTimeMillis()
        if (account.status.blocksAutomaticRetry || !RefreshPolicy.canRefresh(account, now, background)) return@withLock
        try {
            repository.updateListStatus(key, SyncStatus.REFRESHING, expectedConnectedAt = account.connectedAt)
            try {
                val (followers, following) = collector.relationships(account)
                repository.saveScans(followers, following, expectedConnectedAt = account.connectedAt)
            } catch (failure: CollectionFailure) {
                repository.updateListStatus(key, failure.status, expectedConnectedAt = account.connectedAt)
                if (failure.status.blocksAutomaticRetry || failure.status in setOf(SyncStatus.RATE_LIMITED, SyncStatus.OFFLINE)) {
                    val failedAt = System.currentTimeMillis()
                    repository.updateStatus(key, failure.status, now, RefreshPolicy.serviceRetryAt(failure, failedAt),
                        expectedConnectedAt = account.connectedAt,
                        transientRetry = if (failure.status == SyncStatus.OFFLINE) RefreshPolicy.nextTransientRetry(account, failedAt) else null)
                }
            }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    val previousListStatus = if (failure is CancellationException)
                        account.relationshipStatus ?: SyncStatus.LIST_INCOMPLETE else account.relationshipStatus
                    repository.updateListStatus(key, previousListStatus, expectedConnectedAt = account.connectedAt)
                    if (failure !is CancellationException) repository.updateStatus(key, account.status, now, account.nextAllowedAt,
                        expectedConnectedAt = account.connectedAt, transientRetry = account.transientRetry)
                }
            } catch (restorationFailure: Exception) { failure.addSuppressed(restorationFailure) }
            throw failure
        }
        publishWidgets()
    }
}
