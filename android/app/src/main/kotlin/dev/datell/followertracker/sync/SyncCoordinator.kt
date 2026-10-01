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

class SyncCoordinator(private val context: Context, private val repository: TrackerRepository, private val collector: SessionCollector) {
    private val mutex = Mutex()
    suspend fun refresh(key: String, background: Boolean = false) = mutex.withLock {
        val account = repository.account(key) ?: return@withLock
        val now = System.currentTimeMillis()
        if (background && (account.status.blocksAutomaticRetry || account.status == SyncStatus.FOREGROUND_ONLY || (account.nextAllowedAt ?: 0) > now)) return@withLock
        repository.updateStatus(key, SyncStatus.REFRESHING, now, expectedConnectedAt = account.connectedAt)
        try {
            val (updated, metric) = collector.native(account.provider, account)
            val capabilities = if (background) updated.capabilities.copy(background = Capability.OBSERVED) else updated.capabilities
            repository.saveObservation(updated.copy(capabilities = capabilities), metric, requireExisting = true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { repository.updateStatus(key, account.status, now, expectedConnectedAt = account.connectedAt) }
            throw cancelled
        } catch (failure: CollectionFailure) {
            val retryAt = if (failure.status == SyncStatus.RATE_LIMITED) now + (failure.retryAfterSeconds ?: 900) * 1_000 else null
            repository.updateStatus(key, failure.status, now, retryAt, expectedConnectedAt = account.connectedAt)
        } catch (_: Exception) {
            repository.updateStatus(key, SyncStatus.FORMAT_CHANGED, now, expectedConnectedAt = account.connectedAt)
        }
        TrackerWidget().updateAll(context)
    }
    suspend fun relationships(key: String) = mutex.withLock {
        val account = repository.account(key) ?: return@withLock
        val now = System.currentTimeMillis()
        if (account.status.blocksAutomaticRetry || (account.nextAllowedAt ?: 0) > now) return@withLock
        repository.updateListStatus(key, SyncStatus.REFRESHING, expectedConnectedAt = account.connectedAt)
        try {
            val (followers, following) = collector.relationships(account)
            repository.saveScans(followers, following, expectedConnectedAt = account.connectedAt)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { repository.updateListStatus(key, account.relationshipStatus ?: SyncStatus.LIST_INCOMPLETE, expectedConnectedAt = account.connectedAt) }
            throw cancelled
        }
        catch (failure: CollectionFailure) {
            repository.updateListStatus(key, failure.status, expectedConnectedAt = account.connectedAt)
            if (failure.status.blocksAutomaticRetry || failure.status == SyncStatus.RATE_LIMITED) {
                val retryAt = if (failure.status == SyncStatus.RATE_LIMITED) now + (failure.retryAfterSeconds ?: 900) * 1_000 else null
                repository.updateStatus(key, failure.status, now, retryAt, expectedConnectedAt = account.connectedAt)
            }
        }
        catch (_: Exception) { repository.updateListStatus(key, SyncStatus.LIST_INCOMPLETE, expectedConnectedAt = account.connectedAt) }
        TrackerWidget().updateAll(context)
    }
}
