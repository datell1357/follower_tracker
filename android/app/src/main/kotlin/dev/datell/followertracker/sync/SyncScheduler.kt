package dev.datell.followertracker.sync

import android.content.Context
import androidx.work.*
import dev.datell.followertracker.appGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object SyncScheduler {
    private const val METRICS_INTERVAL_MINUTES = 15

    fun schedule(context: Context) {
        // Normalize earlier configurable intervals when the app starts after an update.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("interval", METRICS_INTERVAL_MINUTES).apply()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val metrics = PeriodicWorkRequestBuilder<MetricsWorker>(METRICS_INTERVAL_MINUTES.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("metrics-refresh", ExistingPeriodicWorkPolicy.UPDATE, metrics)
        val lists = PeriodicWorkRequestBuilder<RelationshipsWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("relationships-refresh", ExistingPeriodicWorkPolicy.UPDATE, lists)
    }
    fun initialLists(context: Context, accountKey: String) {
        val request = OneTimeWorkRequestBuilder<RelationshipsWorker>().setInputData(workDataOf("accountKey" to accountKey))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("initial-list-$accountKey", ExistingWorkPolicy.KEEP, request)
    }
}

class MetricsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val graph = applicationContext.appGraph
        val failures = refreshCountBatch(graph.repository.accounts()) { account ->
            if (!RapidTracking.state.value.running || !dev.datell.followertracker.core.RefreshPolicy.supportsCountRefresh(account))
                graph.coordinator.refresh(account.key, background = true)
        }
        if (failures.isEmpty()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
class RelationshipsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val graph = applicationContext.appGraph
        val requested = inputData.getString("accountKey")
        for (account in graph.repository.accounts().filter { it.provider == dev.datell.followertracker.core.Provider.INSTAGRAM && (requested == null || it.key == requested) })
            graph.coordinator.relationships(account.key, background = true)
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
