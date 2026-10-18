package dev.datell.followertracker.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*

/** Registered only in Debug, where the user can opt into the specialUse widget test. */
class TrackingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!PremiumWidgetTestAccess.isRequested(context)) return
        TrackingRecoveryScheduler.schedule(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { withTimeout(8_000) { RapidTracking.recoverRequested(context.applicationContext) } }
            catch (_: TimeoutCancellationException) { Log.w("FollowerCollection", "trackingRestore=timeout") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Log.w("FollowerCollection", "trackingRestore=deferred") }
            finally { pending.finish() }
        }
    }
}
