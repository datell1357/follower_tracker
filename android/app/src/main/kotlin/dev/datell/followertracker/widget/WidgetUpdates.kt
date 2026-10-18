package dev.datell.followertracker.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*

object WidgetUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updater: CoalescingUpdater? = null

    @Synchronized
    fun request(context: Context) {
        val app = context.applicationContext
        val active = updater ?: CoalescingUpdater(scope, { delay(2_000) },
            { Log.w("FollowerWidget", "widget_update_failed") },
            {
                // Isolate provider failures so the other widget family can still update.
                supervisorScope {
                    val updates = listOf(TrackerWidget(), OverviewWidget()).map { widget ->
                        async { runCatching { widget.updateAll(app) }.onFailure { if (it is CancellationException) throw it } }
                    }.awaitAll()
                    updates.firstOrNull { it.isFailure }?.getOrThrow()
                }
            }).also { updater = it }
        active.request()
    }
}
