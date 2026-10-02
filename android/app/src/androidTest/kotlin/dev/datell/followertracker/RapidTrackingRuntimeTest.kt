package dev.datell.followertracker

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.sync.RapidTracking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated runtime preferences only. Never starts a service or writes account/session data. */
@RunWith(AndroidJUnit4::class)
class RapidTrackingRuntimeTest {
    private val collectedAt = 1_790_800_000_000L
    private val stoppedReason = "빠른 추적 시간이 끝났어요. 앱에서 다시 시작할 수 있어요."
    private val interruptedReason = "빠른 추적이 중단됐어요. 앱에서 다시 시작해주세요."

    @Test fun restoresStoppedReasonAndLastCollectionWithoutCountingAnotherRead() = withPreferences { context, prefs ->
        prefs.edit(commit = true) {
            putBoolean("running", false); putString("status", stoppedReason)
            putLong("lastSuccessAt", collectedAt); putInt("successfulReads", 9)
        }
        RapidTracking.restore(context)
        assertFalse(RapidTracking.state.value.running)
        assertEquals(stoppedReason, RapidTracking.state.value.message)
        assertEquals(collectedAt, RapidTracking.state.value.lastSuccessAt)
        assertEquals(9, prefs.getInt("successfulReads", 0))
    }

    @Test fun interruptedServiceRestoresStoppedStateAndPreservesTheLastCollection() = withPreferences { context, prefs ->
        prefs.edit(commit = true) {
            putBoolean("running", true); putString("status", "수집 완료 · 1분 간격으로 확인해요")
            putLong("lastSuccessAt", collectedAt); putInt("successfulReads", 9)
        }
        RapidTracking.restore(context)
        assertFalse(RapidTracking.state.value.running)
        assertEquals(interruptedReason, RapidTracking.state.value.message)
        assertEquals(collectedAt, RapidTracking.state.value.lastSuccessAt)
        assertFalse(prefs.getBoolean("running", true))
        assertEquals(interruptedReason, prefs.getString("status", null))
        assertEquals(9, prefs.getInt("successfulReads", 0))
        RapidTracking.restore(context)
        assertEquals(interruptedReason, RapidTracking.state.value.message)
        assertEquals(9, prefs.getInt("successfulReads", 0))
    }

    @Test fun legacyStoppedRecordKeepsItsReason() = withPreferences { context, prefs ->
        prefs.edit(commit = true) { putString("status", stoppedReason); putLong("lastSuccessAt", collectedAt) }
        RapidTracking.restore(context)
        assertEquals(stoppedReason, RapidTracking.state.value.message)
        assertEquals(collectedAt, RapidTracking.state.value.lastSuccessAt)
        assertFalse(prefs.getBoolean("running", true))
    }

    @Test fun legacyActiveMessagesBecomeAnInterruptionInsteadOfRunningAgain() = withPreferences { context, prefs ->
        for (message in listOf("첫 수집을 확인하고 있어요", "수집 완료 · 1분 간격으로 확인해요",
            "SNS 요청 제한 · 대기 후 다시 확인해요", "일시 오류 · 잠시 후 다시 확인해요", "마지막 기록을 유지하고 있어요")) {
            prefs.edit(commit = true) { remove("running"); putString("status", message); putLong("lastSuccessAt", collectedAt) }
            RapidTracking.restore(context)
            assertFalse(RapidTracking.state.value.running)
            assertEquals(interruptedReason, RapidTracking.state.value.message)
            assertEquals(collectedAt, RapidTracking.state.value.lastSuccessAt)
        }
    }

    @Test fun emptyPreferencesDoNotInventACollection() = withPreferences { context, prefs ->
        RapidTracking.restore(context)
        assertFalse(RapidTracking.state.value.running)
        assertEquals("중지됨", RapidTracking.state.value.message)
        assertNull(RapidTracking.state.value.lastSuccessAt)
        assertEquals(0, prefs.getInt("successfulReads", 0))
    }

    @Test fun runningAndStoppedUpdatesKeepOneReadPerNewCollection() = withPreferences { context, prefs ->
        RapidTracking.update(context, true, "첫 수집을 확인하고 있어요", null)
        assertTrue(prefs.getBoolean("running", false))
        assertEquals(0, prefs.getInt("successfulReads", 0))
        RapidTracking.update(context, true, "수집 완료 · 1분 간격으로 확인해요", collectedAt)
        RapidTracking.update(context, true, "SNS 요청 제한 · 대기 후 다시 확인해요", collectedAt)
        RapidTracking.update(context, false, stoppedReason)
        RapidTracking.restore(context)
        assertFalse(prefs.getBoolean("running", true))
        assertEquals(stoppedReason, prefs.getString("status", null))
        assertEquals(collectedAt, RapidTracking.state.value.lastSuccessAt)
        assertEquals(1, prefs.getInt("successfulReads", 0))
    }

    private fun withPreferences(test: (Context, SharedPreferences) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = target.getSharedPreferences("rapid-runtime-restore-test", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val previous = RapidTracking.state.value
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        prefs.edit(commit = true) { clear() }
        try { test(context, prefs) }
        finally {
            RapidTracking.update(context, previous.running, previous.message, previous.lastSuccessAt)
            prefs.edit(commit = true) {
                clear()
                for ((key, value) in saved) when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }
    }
}
