package dev.datell.followertracker.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.datell.followertracker.diagnostics.*
import kotlinx.coroutines.*

/** Registered only in Debug, where the user can opt into the specialUse widget test. */
class TrackingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!PremiumWidgetTestAccess.isRequested(context)) return
        DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.RECOVERY,
            stage = if (intent.action == Intent.ACTION_BOOT_COMPLETED) DiagnosticStage.BOOT else DiagnosticStage.UPDATE))
        TrackingRecoveryScheduler.schedule(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { withTimeout(8_000) { RapidTracking.recoverRequested(context.applicationContext) } }
            catch (_: TimeoutCancellationException) {
                DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.RECOVERY, stage = DiagnosticStage.TIMEOUT))
                Log.w("FollowerCollection", "trackingRestore=timeout")
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.RECOVERY, stage = DiagnosticStage.DEFERRED))
                Log.w("FollowerCollection", "trackingRestore=deferred")
            }
            finally { pending.finish() }
        }
    }
}
