package dev.datell.followertracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.*
import java.util.UUID

internal object WidgetPinning {
    const val ACTION_PINNED = "dev.datell.followertracker.action.WIDGET_PINNED"
    private const val ACCOUNT_KEY = "accountKey"

    fun callback(context: Context, key: String?): PendingIntent {
        val intent = Intent(context, WidgetPinnedReceiver::class.java)
            .setAction(ACTION_PINNED)
            .setData(("followertracker://widget-pin/" + UUID.randomUUID()).toUri())
            .putExtra(ACCOUNT_KEY, key)
        // The launcher fills the widget ID. Keep this mutable token explicit and private.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    suspend fun request(context: Context, key: String?): Boolean {
        val state = emptyPreferences().toMutablePreferences().apply {
            key?.let { this[stringPreferencesKey(ACCOUNT_KEY)] = it }
        }
        val callback = callback(context, key)
        var sent = false
        try {
            sent = GlanceAppWidgetManager(context).requestPinGlanceAppWidget(
                TrackerWidgetReceiver::class.java, preview = TrackerWidget(),
                previewState = state, successCallback = callback)
            return sent
        } finally {
            if (!sent) callback.cancel()
        }
    }

    suspend fun configure(context: Context, id: Int, key: String?): Boolean {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider
        if (provider != ComponentName(context, TrackerWidgetReceiver::class.java)) return false
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(id)
        updateAppWidgetState(context, glanceId) { preferences ->
            val name = stringPreferencesKey(ACCOUNT_KEY)
            if (key == null) preferences.remove(name) else preferences[name] = key
        }
        TrackerWidget().update(context, glanceId)
        return true
    }

    fun selection(intent: Intent): String? = intent.getStringExtra(ACCOUNT_KEY)
    fun hasSelection(intent: Intent): Boolean = intent.hasExtra(ACCOUNT_KEY)
}

/** Normal pinning skips configuration UI; the launcher invokes this capability after adding. */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetPinning.ACTION_PINNED || !WidgetPinning.hasSelection(intent)) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val key = WidgetPinning.selection(intent)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) { WidgetPinning.configure(context, id, key) }
            } catch (_: TimeoutCancellationException) {
                Log.w("FollowerWidget", "Pinned widget configuration timed out")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Log.w("FollowerWidget", "Pinned widget configuration failed") }
            finally { pending.finish() }
        }
    }
}
