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
import dev.datell.followertracker.diagnostics.*
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
    internal val changes = Channel<Unit>(Channel.CONFLATED)
    internal fun signalChange() { changes.trySend(Unit) }
    internal fun restore(context: Context) {
        val prefs = context.getSharedPreferences("rapid_tracking_runtime", Context.MODE_PRIVATE)
        val message = prefs.getString("status", "중지됨") ?: "중지됨"
        val legacyRunning = !prefs.contains("running") && message in setOf(
            "첫 수집을 확인하고 있어요", "수집 완료 · 1분 간격으로 확인해요",
            "SNS 요청 제한 · 대기 후 다시 확인해요", "일시 오류 · 잠시 후 다시 확인해요",
            "마지막 기록을 유지하고 있어요"
        )
        // Restore state truthfully. Only a retained premium request can later resume the service.
        val restoredMessage = if (prefs.getBoolean("running", false) || legacyRunning) {
            if (PremiumWidgetTestAccess.isEnabled(context)) "위젯 추적이 중단됐어요. 앱에서 다시 시작해주세요."
            else "빠른 추적이 중단됐어요. 앱에서 다시 시작해주세요."
        } else message
        update(context, false, restoredMessage, prefs.getLong("lastSuccessAt", 0).takeIf { it > 0 })
    }
    internal suspend fun recoverRequested(context: Context): TrackingRecoveryResult {
        if (!PremiumWidgetTestAccess.isRequested(context)) return TrackingRecoveryResult.INACTIVE
        val eligible = context.appGraph.repository.accounts().any {
            RefreshPolicy.supportsCountRefresh(it) && !it.status.blocksAutomaticRetry
        }
        return TrackingRecovery({ PremiumWidgetTestAccess.mode(context) },
            { PremiumWidgetTestAccess.isRequested(context) }, { state.value.running },
            { context.startForegroundService(Intent(context, RapidTrackingService::class.java).setAction(RapidTrackingService.ACTION_RECOVER)) })
            .run(hasEligibleAccount = eligible).also { result ->
                DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.RECOVERY, stage = when (result) {
                    TrackingRecoveryResult.HEALTHY -> DiagnosticStage.HEALTHY
                    TrackingRecoveryResult.START_REQUESTED -> DiagnosticStage.RECOVERY_START
                    TrackingRecoveryResult.WAITING_FOR_CONNECTION -> DiagnosticStage.CONNECTION_REQUIRED
                    TrackingRecoveryResult.INACTIVE -> DiagnosticStage.USER_STOP
                    TrackingRecoveryResult.DEFERRED -> DiagnosticStage.DEFERRED
                }))
            }
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
        try {
            if (!state.value.running) TrackingRecoveryScheduler.schedule(context)
            context.startForegroundService(Intent(context, RapidTrackingService::class.java))
        } catch (failure: RuntimeException) {
            PremiumWidgetTestAccess.endRequest(context)
            TrackingRecoveryScheduler.cancel(context)
            throw failure
        }
    }
    fun stop(context: Context) {
        PremiumWidgetTestAccess.endRequest(context)
        TrackingRecoveryScheduler.cancel(context)
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
    private var finishing = false
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
        if (intent?.action == ACTION_STOP) { finish("중지됨", stage = DiagnosticStage.USER_STOP); return START_NOT_STICKY }
        val recovery = intent == null || intent.action == ACTION_RECOVER
        val requestedMode = PremiumWidgetTestAccess.mode(this)
        // Validate even a duplicate recovery intent: a queued start must not undo an explicit stop.
        if (!TrackingSessionPolicy.canStart(requestedMode, !recovery, PremiumWidgetTestAccess.isRequested(this))) {
            finish("중지됨", stage = DiagnosticStage.USER_STOP); return START_NOT_STICKY
        }
        if (tracking?.isActive == true) return restartPolicy()
        mode = requestedMode
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
            finish("추적을 시작하지 못했어요. 앱에서 다시 시작해주세요.", preserveRequest = recovery, stage = DiagnosticStage.START_DENIED)
            return START_NOT_STICKY
        }
        getSharedPreferences("rapid_tracking_runtime", MODE_PRIVATE).edit { putString("trackingMode", mode.name) }
        PremiumWidgetTestAccess.markStarted(this)
        DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.TRACKING_STARTED,
            stage = if (recovery) DiagnosticStage.RECOVERY_START else DiagnosticStage.USER_START,
            premium = mode == TrackingMode.PREMIUM_WIDGET_TEST))
        while (RapidTracking.changes.tryReceive().isSuccess) { /* Discard signals preceding this actual start. */ }
        RapidTracking.update(this, true, "첫 수집을 확인하고 있어요")
        finishing = false
        tracking = scope.launch {
            runTrackingSession(stopping = { finishing }, run = {
                CountTrackingLoop(mode, SystemClock::elapsedRealtime, System::currentTimeMillis,
                    isOffline = { NetworkAvailability.isDefinitelyOffline(this@RapidTrackingService) },
                    readCounts = { appGraph.repository.widgetOverviews().map { TrackingCount(it.account, it.latest?.observedAt) } },
                    refresh = { appGraph.coordinator.refresh(it.key, background = true) },
                    onCycle = { cycle ->
                        if (BuildConfig.DEBUG) for (row in cycle.rows) Log.d("FollowerCollection",
                            "provider=${row.account.provider.name} rapidFresh=${row.account.key in cycle.freshKeys} " +
                                "status=${row.account.status.name} batchFailure=${row.account.provider in cycle.failedProviders} " +
                                "cycleElapsedMs=${cycle.elapsedMillis}")
                        for (row in cycle.rows.filter { it.account.status != SyncStatus.READY }) {
                            val account = row.account
                            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.RETRY_WAIT, account.provider, account.accountType,
                                status = account.status, serviceRetryAt = account.nextAllowedAt,
                                localRetryAt = account.transientRetry?.nextAttemptAt,
                                failureCount = account.transientRetry?.failureCount?.coerceIn(0, 5)))
                        }
                        RapidTracking.update(this@RapidTrackingService, true, cycle.message,
                            cycle.freshAt ?: RapidTracking.state.value.lastSuccessAt)
                        publishNotification(cycle.message)
                    },
                    onOffline = {
                        val message = "인터넷 연결을 기다리고 있어요 · 마지막 기록 유지"
                        if (RapidTracking.state.value.message != message) {
                            DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.TRACKING_OFFLINE))
                            RapidTracking.update(this@RapidTrackingService, true, message)
                        }
                        publishNotification(message)
                    },
                    awaitNetwork = { remaining ->
                        // A registered callback can still be missed. Recheck availability without an SNS request.
                        val fallback = if (networkCallback == null) INTERVAL else 5 * INTERVAL
                        awaitTrackingSignal(RapidTracking.changes, networkAvailable, minOf(fallback, remaining ?: fallback))
                    }, wait = { awaitTrackingSignal(RapidTracking.changes, timeoutMillis = it) }).run()
            }, onStop = { stopped ->
                finish(when (stopped) {
                    TrackingLoopStop.TIME_LIMIT -> "빠른 추적 시간이 끝났어요. 앱에서 다시 시작할 수 있어요."
                    TrackingLoopStop.NO_ACCOUNTS -> "추적할 SNS 계정을 연결한 뒤 시작해주세요."
                    TrackingLoopStop.CONNECTION_REQUIRED -> "연결 상태를 확인한 뒤 다시 시작해주세요."
                }, preserveRequest = stopped == TrackingLoopStop.CONNECTION_REQUIRED, stage = when (stopped) {
                    TrackingLoopStop.TIME_LIMIT -> DiagnosticStage.TIME_LIMIT
                    TrackingLoopStop.NO_ACCOUNTS -> DiagnosticStage.NO_ACCOUNTS
                    TrackingLoopStop.CONNECTION_REQUIRED -> DiagnosticStage.CONNECTION_REQUIRED
                })
            }, onInterrupted = { finish("추적이 중단돼 자동 복구를 기다리고 있어요. 마지막 기록은 유지해요.",
                preserveRequest = true, stage = DiagnosticStage.INTERRUPTED) })
        }
        return restartPolicy()
    }

    private fun restartPolicy(): Int = if (mode == TrackingMode.PREMIUM_WIDGET_TEST) START_STICKY else START_NOT_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish("기기의 실행 시간 제한으로 중지됐어요. 앱에서 다시 시작해주세요.", stage = DiagnosticStage.OS_TIMEOUT)
    }

    private fun finish(message: String, preserveRequest: Boolean = false, stage: DiagnosticStage) {
        DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.TRACKING_STOPPED, stage = stage,
            premium = mode == TrackingMode.PREMIUM_WIDGET_TEST))
        finishing = true
        if (!preserveRequest || mode != TrackingMode.PREMIUM_WIDGET_TEST) {
            PremiumWidgetTestAccess.endRequest(this)
            TrackingRecoveryScheduler.cancel(this)
        } else {
            TrackingRecoveryScheduler.schedule(this)
        }
        RapidTracking.update(this, false, message)
        tracking?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val interrupted = !finishing && PremiumWidgetTestAccess.isRequested(this)
        DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.TRACKING_DESTROYED,
            stage = DiagnosticStage.INTERRUPTED.takeIf { interrupted }, premium = mode == TrackingMode.PREMIUM_WIDGET_TEST))
        finishing = true
        scope.cancel()
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        networkAvailable.close()
        appGraph.collector.releaseProfileResources()
        if (interrupted) {
            RapidTracking.update(this, false, "추적이 중단돼 자동 복구를 기다리고 있어요. 마지막 기록은 유지해요.")
            TrackingRecoveryScheduler.schedule(this)
        } else if (RapidTracking.state.value.running) RapidTracking.update(this, false, "중지됨")
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
        const val INTERVAL = CountTrackingLoop.INTERVAL
        internal const val ACTION_RECOVER = "dev.datell.followertracker.RECOVER_TRACKING"
        private const val CHANNEL = "rapid-follower-tracking"
        private const val NOTIFICATION = 901
        private const val ACTION_STOP = "dev.datell.followertracker.STOP_RAPID_TRACKING"
    }
}
