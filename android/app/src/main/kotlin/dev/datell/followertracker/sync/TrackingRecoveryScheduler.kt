package dev.datell.followertracker.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import dev.datell.followertracker.BuildConfig
import java.util.concurrent.TimeUnit

/** Requested debug premium sessions only. These jobs check health; they never poll SNS themselves. */
internal object TrackingRecoveryScheduler {
    fun schedule(context: Context) {
        if (!PremiumWidgetTestAccess.isRequested(context)) return
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrackingRecoveryWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).build())
        manager.enqueueUniqueWork(INITIAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TrackingRecoveryWorker>().setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    fun cancel(context: Context) {
        try {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(INITIAL)
        } catch (failure: RuntimeException) {
            // Clearing the execution request and stopping the service must still work if job storage fails.
            if (BuildConfig.DEBUG) Log.w("FollowerCollection", "trackingHealthCancelFailure=${failure.javaClass.simpleName}")
        }
    }

    private const val PERIODIC = "premium-tracking-health"
    private const val INITIAL = "premium-tracking-health-initial"
}

class TrackingRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return when (TrackingRecovery(
            mode = { PremiumWidgetTestAccess.mode(context) },
            requested = { PremiumWidgetTestAccess.isRequested(context) },
            running = { RapidTracking.state.value.running },
            start = { context.startForegroundService(Intent(context, RapidTrackingService::class.java)
                .setAction(RapidTrackingService.ACTION_RECOVER)) },
        ).run()) {
            TrackingRecoveryResult.INACTIVE -> Result.success()
            TrackingRecoveryResult.HEALTHY, TrackingRecoveryResult.START_REQUESTED -> Result.success()
            TrackingRecoveryResult.DEFERRED -> {
                // A service may have started concurrently. Never overwrite its actual running/success state.
                if (BuildConfig.DEBUG) Log.w("FollowerCollection", "trackingRecovery=deferred")
                if (TrackingRecovery.retryDeferred(runAttemptCount)) Result.retry() else Result.failure()
            }
        }
    }
}
