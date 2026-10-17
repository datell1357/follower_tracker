package dev.datell.followertracker.sync

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.datell.followertracker.BuildConfig
import java.util.UUID

/** Local debug entitlement only. Release builds always use the ordinary tracking policy. */
internal object PremiumWidgetTestAccess {
    const val PREFERENCES = "premium_widget_test"

    fun mode(context: Context): TrackingMode = TrackingSessionPolicy.mode(BuildConfig.DEBUG,
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", false), Build.VERSION.SDK_INT)

    fun isEnabled(context: Context): Boolean = mode(context) == TrackingMode.PREMIUM_WIDGET_TEST

    fun isRequested(context: Context): Boolean = isEnabled(context) &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("requested", false)

    fun beginRequest(context: Context, newSession: Boolean = false) {
        if (!isEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getBoolean("requested", false) && !newSession) return
        prefs.edit(commit = true) {
            putBoolean("requested", true)
            putString("runId", UUID.randomUUID().toString())
            remove("startedAt")
        }
    }

    fun markStarted(context: Context) {
        if (!isRequested(context)) return
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.contains("startedAt")) prefs.edit(commit = true) { putLong("startedAt", System.currentTimeMillis()) }
    }

    fun endRequest(context: Context) {
        if (BuildConfig.DEBUG) context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit(commit = true) { putBoolean("requested", false) }
    }
}
