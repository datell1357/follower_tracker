package dev.datell.followertracker

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.sync.PremiumWidgetTestAccess
import dev.datell.followertracker.sync.RapidTrackingService
import dev.datell.followertracker.sync.TrackingSessionPolicy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manifest and isolated preferences only; never starts collection or changes the real entitlement. */
@RunWith(AndroidJUnit4::class)
class PremiumWidgetPolicyRuntimeTest {
    @Test fun debugManifestDeclaresTheSpecificServiceTypeAndStopCapablePrivateService() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.DEBUG)
        val component = ComponentName(context, RapidTrackingService::class.java)
        val info = context.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        val purpose = context.packageManager.getProperty("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", component)
        assertTrue(purpose.string?.contains("Debug-only") == true)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    @Test fun stickyRequestPreservesItsRunAndStopRevokesTheRestart() = withPreferences { context, prefs ->
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        PremiumWidgetTestAccess.beginRequest(context)
        assertFalse(prefs.contains("runId"))
        prefs.edit(commit = true) { putBoolean("enabled", true) }
        PremiumWidgetTestAccess.beginRequest(context)
        PremiumWidgetTestAccess.markStarted(context)
        val run = prefs.getString("runId", null)
        val startedAt = prefs.getLong("startedAt", 0)
        assertNotNull(run)
        assertTrue(startedAt > 0)
        PremiumWidgetTestAccess.beginRequest(context)
        PremiumWidgetTestAccess.markStarted(context)
        assertEquals(run, prefs.getString("runId", null))
        assertEquals(startedAt, prefs.getLong("startedAt", 0))
        assertTrue(PremiumWidgetTestAccess.isRequested(context))
        PremiumWidgetTestAccess.endRequest(context)
        assertFalse(PremiumWidgetTestAccess.isRequested(context))
        assertFalse(TrackingSessionPolicy.canStart(PremiumWidgetTestAccess.mode(context), false,
            PremiumWidgetTestAccess.isRequested(context)))
        assertEquals(startedAt, prefs.getLong("startedAt", 0))
    }

    @Test fun aNewUserSessionCannotReuseThePreviousSoakStart() = withPreferences { context, prefs ->
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        prefs.edit(commit = true) { putBoolean("enabled", true) }
        PremiumWidgetTestAccess.beginRequest(context)
        PremiumWidgetTestAccess.markStarted(context)
        val previousRun = prefs.getString("runId", null)
        assertTrue(prefs.contains("startedAt"))
        PremiumWidgetTestAccess.beginRequest(context, newSession = true)
        assertNotEquals(previousRun, prefs.getString("runId", null))
        assertFalse(prefs.contains("startedAt"))
        assertTrue(PremiumWidgetTestAccess.isRequested(context))
    }

    private fun withPreferences(test: (Context, SharedPreferences) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = target.getSharedPreferences("premium-widget-access-test", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        prefs.edit(commit = true) { clear() }
        try { test(context, prefs) }
        finally {
            prefs.edit(commit = true) {
                clear()
                for ((key, value) in saved) when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }
    }
}
