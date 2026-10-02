package dev.datell.followertracker

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only diagnostic. Never emits descriptions, raw traces, session data or account data. */
@RunWith(AndroidJUnit4::class)
class StartupExitDiagnostic {
    @Test fun recordRecentStartupExitReasons() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeStartupExit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val output = JSONArray()
        for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
            .filter { it.processName == context.packageName }) {
            val result = JSONObject().put("reason", exit.reason).put("timestamp", exit.timestamp)
                .put("importance", exit.importance).put("status", exit.status)
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                val frames = exit.traceInputStream?.bufferedReader()?.use {
                    mainFrames(it.lineSequence().take(10_000).toList(), context.packageName)
                }
                result.put("traceAvailable", frames != null)
                    .put("mainJavaFrames", JSONArray(frames.orEmpty()))
            }
            output.put(result)
        }
        println("STARTUP_EXIT_DIAGNOSTIC=$output")
    }

    @Test fun traceFilterOnlyEmitsOwnMainThreadMethodFrames() {
        val lines = listOf("----- pid 1 at 2026-10-02 -----", "Cmd line: fixture.app",
            "\"main\" prio=5 tid=1 Native", "  at android.os.Looper.loop(Looper.java:200)",
            "sessionid=redacted-fixture", "  at example.Capture.read(https://private.invalid/)",
            "\"worker\" prio=5 tid=2 Runnable", "  at other.Hidden.read(Hidden.java:1)",
            "----- pid 2 at 2026-10-02 -----", "Cmd line: other.app",
            "\"main\" prio=5 tid=1 Native", "  at other.Main.read(Main.java:2)")
        assertEquals(listOf("android.os.Looper.loop(Looper.java:200)"), mainFrames(lines, "fixture.app"))
    }

    private fun mainFrames(lines: List<String>, packageName: String): List<String> {
        var ownProcess = false
        var main = false
        val frames = mutableListOf<String>()
        val method = Regex("^at ([A-Za-z0-9_.$<>]+\\((?:[A-Za-z0-9_.$:-]+|Native method|Unknown Source)\\))$")
        for (line in lines) {
            if (line.startsWith("----- pid ")) { ownProcess = false; main = false }
            if (line.startsWith("Cmd line: ")) ownProcess = line == "Cmd line: $packageName"
            if (ownProcess && line.startsWith("\"main\" ")) main = true
            else if (line.startsWith("\"")) main = false
            if (main) method.matchEntire(line.trim())?.groupValues?.get(1)?.let {
                if (frames.size < 40) frames += it
            }
        }
        return frames
    }
}
