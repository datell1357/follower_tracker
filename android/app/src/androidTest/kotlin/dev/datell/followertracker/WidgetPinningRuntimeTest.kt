package dev.datell.followertracker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.widget.WidgetPinnedReceiver
import dev.datell.followertracker.widget.WidgetPinning
import dev.datell.followertracker.widget.TrackerWidgetReceiver
import dev.datell.followertracker.widget.OverviewWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetPinningRuntimeTest {
    @Test fun launcherOffersExactlyTheSingleAndOverviewProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providers = AppWidgetManager.getInstance(context).installedProviders.filter { it.provider.packageName == context.packageName }
        assertEquals(setOf(ComponentName(context, TrackerWidgetReceiver::class.java), ComponentName(context, OverviewWidgetReceiver::class.java)), providers.map { it.provider }.toSet())
        if (Build.VERSION.SDK_INT >= 31) {
            val single = providers.single { it.provider.className == TrackerWidgetReceiver::class.java.name }
            val overview = providers.single { it.provider.className == OverviewWidgetReceiver::class.java.name }
            assertEquals(2, single.targetCellWidth); assertEquals(1, single.targetCellHeight)
            assertEquals(4, overview.targetCellWidth); assertEquals(4, overview.targetCellHeight)
            assertEquals("Both widgets expose the name visibility option", single.configure, overview.configure)
        }
    }

    @Test fun pendingPinRequestsDoNotOverwriteOneAnothersCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val callbacks = listOf(WidgetPinning.callback(context, "fixture-first"),
            WidgetPinning.callback(context, "fixture-second"), WidgetPinning.callback(context, null))
        try {
            assertEquals(3, callbacks.toSet().size)
            assertTrue(callbacks.all { it.creatorPackage == context.packageName })
            if (Build.VERSION.SDK_INT >= 31) assertTrue(callbacks.none { it.isImmutable })
        } finally { callbacks.forEach { it.cancel() } }
    }

    @Test fun invalidWidgetIdsCannotChangeTheStoredDefaultSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("widget_preferences", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        assertFalse(WidgetPinning.configure(context, AppWidgetManager.INVALID_APPWIDGET_ID, "fixture-first"))
        assertFalse(WidgetPinning.configure(context, Int.MAX_VALUE, null))
        assertTrue("Invalid callbacks must preserve the default selection", preferences.all == before)
    }

    @Test fun nameVisibilityTravelsWithEachPinSelectionAndDefaultsToHidden() {
        val hidden = android.content.Intent().putExtra("accountKey", "fixture-first")
        val visible = android.content.Intent().putExtra("accountKey", "fixture-second").putExtra("showAccountName", true)
        assertFalse(WidgetPinning.showsAccountName(hidden))
        assertTrue(WidgetPinning.showsAccountName(visible))
        assertEquals("fixture-first", WidgetPinning.selection(hidden))
        assertEquals("fixture-second", WidgetPinning.selection(visible))
    }

    @Test fun pinCallbackReceiverIsPrivateToTheApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, WidgetPinnedReceiver::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getReceiverInfo(component, 0)
        assertFalse(info.exported)
    }
}
