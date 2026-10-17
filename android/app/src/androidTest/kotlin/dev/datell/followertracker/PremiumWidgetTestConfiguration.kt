package dev.datell.followertracker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.datell.followertracker.sync.PremiumWidgetTestAccess
import dev.datell.followertracker.sync.RapidTracking
import dev.datell.followertracker.sync.RapidTrackingService
import dev.datell.followertracker.widget.TrackerWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in device setup, not a purchase or a production entitlement. Starts no service. */
@RunWith(AndroidJUnit4::class)
class PremiumWidgetTestConfiguration {
    @Test fun enablePremiumWidgetTrackingOnThisDebugDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("enablePremiumWidgetTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 34)
        assertFalse("Stop tracking before changing its test entitlement", RapidTracking.state.value.running)
        val widgets = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, TrackerWidgetReceiver::class.java)).size
        assertTrue("Install a home widget before the widget-only soak", widgets > 0)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelUniqueWork("qa-live-metrics-once").result.get(10, TimeUnit.SECONDS)
        }
        context.getSharedPreferences(PremiumWidgetTestAccess.PREFERENCES, Context.MODE_PRIVATE)
            .edit(commit = true) { putBoolean("enabled", true); putBoolean("requested", false) }
        assertTrue(PremiumWidgetTestAccess.isEnabled(context))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("PREMIUM_WIDGET_TEST", "enabled=true;started=false;widgetCount=$widgets;intervalMs=${RapidTrackingService.INTERVAL}")
        })
    }
}
