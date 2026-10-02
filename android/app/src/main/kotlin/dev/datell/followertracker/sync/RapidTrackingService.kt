package dev.datell.followertracker.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import dev.datell.followertracker.R
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.*
import dev.datell.followertracker.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

data class RapidTrackingState(val running: Boolean = false, val message: String = "중지됨", val lastSuccessAt: Long? = null)

object RapidTracking {
    private val mutableState = MutableStateFlow(RapidTrackingState())
    val state = mutableState.asStateFlow()
    internal fun restore(context: Context) {
        val prefs = context.getSharedPreferences("rapid_tracking_runtime", Context.MODE_PRIVATE)
        val message = prefs.getString("status", "중지됨") ?: "중지됨"
        val legacyRunning = !prefs.contains("running") && message in setOf(
            "첫 수집을 확인하고 있어요", "수집 완료 · 1분 간격으로 확인해요",
            "SNS 요청 제한 · 대기 후 다시 확인해요", "일시 오류 · 잠시 후 다시 확인해요",
            "마지막 기록을 유지하고 있어요"
        )
        // A new process has no running service. Restore its record, never restart it.
        val restoredMessage = if (prefs.getBoolean("running", false) || legacyRunning)
            "빠른 추적이 중단됐어요. 앱에서 다시 시작해주세요." else message
        update(context, false, restoredMessage, prefs.getLong("lastSuccessAt", 0).takeIf { it > 0 })
    }
    internal fun update(context: Context, running: Boolean, message: String, successAt: Long? = state.value.lastSuccessAt) {
        val prefs = context.getSharedPreferences("rapid_tracking_runtime", Context.MODE_PRIVATE)
        val freshAt = successAt?.takeIf { it > prefs.getLong("lastSuccessAt", 0) }
        val reads = prefs.getInt("successfulReads", 0)
        prefs.edit {
            putBoolean("running", running)
            putString("status", message)
            if (freshAt != null) {
                putLong("lastSuccessAt", freshAt)
                putInt("successfulReads", reads + 1)
            }
        }
        mutableState.value = RapidTrackingState(running, message, successAt)
    }
    fun start(context: Context) {
        context.startForegroundService(Intent(context, RapidTrackingService::class.java))
    }
    fun stop(context: Context) {
        context.stopService(Intent(context, RapidTrackingService::class.java))
        update(context, false, "중지됨")
    }
}

/** User-started, finite dataSync session. The OS can suspend/stop it; it is not a 24-hour timer. */
class RapidTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tracking: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish("중지됨"); return START_NOT_STICKY }
        if (tracking?.isActive == true) return START_NOT_STICKY
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "빠른 팔로워 추적", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification("1분 간격으로 확인해요")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            finish("빠른 추적을 시작하지 못했어요. 앱에서 다시 시작해주세요.")
            return START_NOT_STICKY
        }
        RapidTracking.update(this, true, "첫 수집을 확인하고 있어요")
        tracking = scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                while (isActive && SystemClock.elapsedRealtime() - started < MAX_DURATION) {
                    val cycleStarted = SystemClock.elapsedRealtime()
                    val rows = appGraph.repository.overviews().filter { it.account.provider == Provider.INSTAGRAM }
                    val available = rows.filter { !it.account.status.blocksAutomaticRetry }
                    if (available.isEmpty()) {
                        finish(if (rows.isEmpty()) "Instagram을 연결한 뒤 시작해주세요." else "연결 상태를 확인한 뒤 다시 시작해주세요.")
                        return@launch
                    }
                    for (row in available) appGraph.coordinator.refresh(row.account.key, background = true)
                    val after = appGraph.repository.overviews().filter { it.account.provider == Provider.INSTAGRAM }
                    val successful = after.filter { row ->
                        row.account.capabilities.background == Capability.OBSERVED && row.latest != null &&
                            row.latest!!.observedAt > (available.firstOrNull { it.account.key == row.account.key }?.latest?.observedAt ?: Long.MAX_VALUE)
                    }
                    val freshAt = successful.maxOfOrNull { it.latest!!.observedAt }
                    val message = when {
                        after.all { it.account.status.blocksAutomaticRetry } -> "연결 상태를 확인한 뒤 다시 시작해주세요."
                        after.any { it.account.status == SyncStatus.RATE_LIMITED } -> "SNS 요청 제한 · 대기 후 다시 확인해요"
                        after.any { it.account.status == SyncStatus.OFFLINE } -> "일시 오류 · 잠시 후 다시 확인해요"
                        freshAt != null -> "수집 완료 · 1분 간격으로 확인해요"
                        else -> "마지막 기록을 유지하고 있어요"
                    }
                    if (after.isEmpty() || after.all { it.account.status.blocksAutomaticRetry }) {
                        finish(message); return@launch
                    }
                    RapidTracking.update(this@RapidTrackingService, true, message, freshAt ?: RapidTracking.state.value.lastSuccessAt)
                    notifications.notify(NOTIFICATION, notification(message))
                    val now = System.currentTimeMillis()
                    val cooldown = after.minOfOrNull { maxOf(it.account.nextAllowedAt ?: 0,
                        it.account.transientRetry?.nextAttemptAt ?: 0) - now } ?: 0
                    val delayMs = maxOf(1_000L, INTERVAL - (SystemClock.elapsedRealtime() - cycleStarted), cooldown)
                    val remaining = MAX_DURATION - (SystemClock.elapsedRealtime() - started)
                    delay(minOf(delayMs, remaining.coerceAtLeast(1)))
                }
                finish("빠른 추적 시간이 끝났어요. 앱에서 다시 시작할 수 있어요.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish("빠른 추적을 중지했어요. 마지막 기록을 보존했어요.") }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish("기기의 실행 시간 제한으로 중지됐어요. 앱에서 다시 시작해주세요.")
    }

    private fun finish(message: String) {
        RapidTracking.update(this, false, message)
        tracking?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (RapidTracking.state.value.running) RapidTracking.update(this, false, "중지됨")
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_TRACKING), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RapidTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tracker)
            .setContentTitle("팔로워 빠른 추적").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
    }

    companion object {
        const val INTERVAL = 60_000L
        private const val MAX_DURATION = 6 * 60 * 60_000L - 30_000
        private const val CHANNEL = "rapid-follower-tracking"
        private const val NOTIFICATION = 901
        private const val ACTION_STOP = "dev.datell.followertracker.STOP_RAPID_TRACKING"
    }
}
