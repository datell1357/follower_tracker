package dev.datell.followertracker.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.datell.followertracker.BuildConfig
import dev.datell.followertracker.R
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.*
import dev.datell.followertracker.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
        val restoredMessage = if (prefs.getBoolean("running", false) || legacyRunning) {
            if (PremiumWidgetTestAccess.isEnabled(context)) "위젯 추적이 중단됐어요. 앱에서 다시 시작해주세요."
            else "빠른 추적이 중단됐어요. 앱에서 다시 시작해주세요."
        } else message
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
        PremiumWidgetTestAccess.beginRequest(context, newSession = !state.value.running)
        try { context.startForegroundService(Intent(context, RapidTrackingService::class.java)) }
        catch (failure: RuntimeException) { PremiumWidgetTestAccess.endRequest(context); throw failure }
    }
    fun stop(context: Context) {
        PremiumWidgetTestAccess.endRequest(context)
        context.stopService(Intent(context, RapidTrackingService::class.java))
        update(context, false, "중지됨")
    }
}

/** User-started tracking. Only the opt-in debug entitlement can use the persistent widget test. */
class RapidTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tracking: Job? = null
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotificationMessage: String? = null
    private var mode = TrackingMode.FAST
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { signalRecovery() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { signalRecovery() }
            private fun signalRecovery() {
                if (!NetworkAvailability.isDefinitelyOffline(this@RapidTrackingService)) networkAvailable.trySend(Unit)
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish("중지됨"); return START_NOT_STICKY }
        if (tracking?.isActive == true) return restartPolicy()
        mode = PremiumWidgetTestAccess.mode(this)
        if (!TrackingSessionPolicy.canStart(mode, intent != null, PremiumWidgetTestAccess.isRequested(this))) {
            finish("중지됨"); return START_NOT_STICKY
        }
        if (mode == TrackingMode.PREMIUM_WIDGET_TEST) PremiumWidgetTestAccess.beginRequest(this)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "빠른 팔로워 추적", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification("1분 간격으로 확인해요")
            lastNotificationMessage = "1분 간격으로 확인해요"
            if (Build.VERSION.SDK_INT >= 34 && mode == TrackingMode.PREMIUM_WIDGET_TEST)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            finish("빠른 추적을 시작하지 못했어요. 앱에서 다시 시작해주세요.")
            return START_NOT_STICKY
        }
        getSharedPreferences("rapid_tracking_runtime", MODE_PRIVATE).edit { putString("trackingMode", mode.name) }
        PremiumWidgetTestAccess.markStarted(this)
        RapidTracking.update(this, true, "첫 수집을 확인하고 있어요")
        tracking = scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                while (isActive && TrackingSessionPolicy.canContinue(mode, SystemClock.elapsedRealtime() - started)) {
                    if (NetworkAvailability.isDefinitelyOffline(this@RapidTrackingService)) {
                        val message = "인터넷 연결을 기다리고 있어요 · 마지막 기록 유지"
                        if (RapidTracking.state.value.message != message) RapidTracking.update(this@RapidTrackingService, true, message)
                        publishNotification(message)
                        val remaining = TrackingSessionPolicy.remainingMillis(mode, SystemClock.elapsedRealtime() - started)
                        // Callback failures use a bounded fallback; successful callbacks need no polling while offline.
                        val wait = if (networkCallback == null) minOf(INTERVAL, remaining ?: INTERVAL) else remaining
                        if (wait == null) networkAvailable.receive()
                        else withTimeoutOrNull(wait.coerceAtLeast(1)) { networkAvailable.receive() }
                        continue
                    }
                    val cycleStarted = SystemClock.elapsedRealtime()
                    val rows = appGraph.repository.widgetOverviews().filter { RefreshPolicy.supportsCountRefresh(it.account) }
                    val available = rows.filter { !it.account.status.blocksAutomaticRetry }
                    if (available.isEmpty()) {
                        finish(if (rows.isEmpty()) "추적할 SNS 계정을 연결한 뒤 시작해주세요." else "연결 상태를 확인한 뒤 다시 시작해주세요.")
                        return@launch
                    }
                    val failedProviders = refreshCountBatch(available.map { it.account }) {
                        appGraph.coordinator.refresh(it.key, background = true)
                    }
                    val after = appGraph.repository.widgetOverviews().filter { RefreshPolicy.supportsCountRefresh(it.account) }
                    val successful = after.filter { row ->
                        row.account.capabilities.background == Capability.OBSERVED && row.latest != null &&
                            row.latest!!.observedAt > (available.firstOrNull { it.account.key == row.account.key }?.latest?.observedAt ?: Long.MAX_VALUE)
                    }
                    val freshAt = successful.maxOfOrNull { it.latest!!.observedAt }
                    if (BuildConfig.DEBUG) for (row in after) Log.d("FollowerCollection",
                        "provider=${row.account.provider.name} rapidFresh=${row in successful} " +
                            "status=${row.account.status.name} batchFailure=${row.account.provider in failedProviders} " +
                            "cycleElapsedMs=${cycleStarted - started}")
                    val message = countBatchMessage(after.map { it.account.status }, freshAt != null, failedProviders.isNotEmpty())
                    if (after.isEmpty() || after.all { it.account.status.blocksAutomaticRetry }) {
                        finish(message); return@launch
                    }
                    RapidTracking.update(this@RapidTrackingService, true, message, freshAt ?: RapidTracking.state.value.lastSuccessAt)
                    publishNotification(message)
                    val now = System.currentTimeMillis()
                    val cooldown = after.minOfOrNull { maxOf(it.account.nextAllowedAt ?: 0,
                        it.account.transientRetry?.nextAttemptAt ?: 0) - now } ?: 0
                    val delayMs = maxOf(1_000L, INTERVAL - (SystemClock.elapsedRealtime() - cycleStarted), cooldown)
                    val remaining = TrackingSessionPolicy.remainingMillis(mode, SystemClock.elapsedRealtime() - started)
                    delay(minOf(delayMs, (remaining ?: delayMs).coerceAtLeast(1)))
                }
                finish("빠른 추적 시간이 끝났어요. 앱에서 다시 시작할 수 있어요.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish("빠른 추적을 중지했어요. 마지막 기록을 보존했어요.") }
        }
        return restartPolicy()
    }

    private fun restartPolicy(): Int = if (mode == TrackingMode.PREMIUM_WIDGET_TEST) START_STICKY else START_NOT_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish("기기의 실행 시간 제한으로 중지됐어요. 앱에서 다시 시작해주세요.")
    }

    private fun finish(message: String) {
        PremiumWidgetTestAccess.endRequest(this)
        RapidTracking.update(this, false, message)
        tracking?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        networkAvailable.close()
        appGraph.collector.releaseProfileResources()
        if (RapidTracking.state.value.running) RapidTracking.update(this, false, "중지됨")
        super.onDestroy()
    }

    private fun publishNotification(message: String) {
        if (lastNotificationMessage == message) return
        notifications.notify(NOTIFICATION, notification(message))
        lastNotificationMessage = message
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_TRACKING), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RapidTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tracker)
            .setContentTitle(if (mode == TrackingMode.PREMIUM_WIDGET_TEST) "팔로워 위젯 추적 · 프리미엄 테스트" else "팔로워 빠른 추적")
            .setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
    }

    companion object {
        const val INTERVAL = 60_000L
        private const val CHANNEL = "rapid-follower-tracking"
        private const val NOTIFICATION = 901
        private const val ACTION_STOP = "dev.datell.followertracker.STOP_RAPID_TRACKING"
    }
}
