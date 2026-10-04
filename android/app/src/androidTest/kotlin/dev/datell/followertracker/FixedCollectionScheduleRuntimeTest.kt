package dev.datell.followertracker

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Reads the startup schedule only; never writes accounts, sessions, metrics, or settings. */
@RunWith(AndroidJUnit4::class)
class FixedCollectionScheduleRuntimeTest {
    @Test fun startupUsesFifteenMinutesAndKeepsDailyRelationshipCollection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = WorkManager.getInstance(context)
        val metrics = work.getWorkInfosForUniqueWork("metrics-refresh").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        val lists = work.getWorkInfosForUniqueWork("relationships-refresh").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        assertEquals(15, context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("interval", -1))
        assertEquals(TimeUnit.MINUTES.toMillis(15), checkNotNull(metrics.periodicityInfo).repeatIntervalMillis)
        assertEquals(TimeUnit.HOURS.toMillis(24), checkNotNull(lists.periodicityInfo).repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, metrics.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, lists.constraints.requiredNetworkType)
    }
}
