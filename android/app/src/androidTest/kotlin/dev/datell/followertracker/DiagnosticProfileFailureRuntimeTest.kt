package dev.datell.followertracker

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import dev.datell.followertracker.diagnostics.*
import dev.datell.followertracker.sync.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local HTML only. Synthetic cookies are permitted exclusively in an empty isolated QA package. */
@RunWith(AndroidJUnit4::class)
class DiagnosticProfileFailureRuntimeTest {
    @Test fun missingFacebookDataPersistsTheCaptureReasonAfterTheRealLocalReadTimeout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.datell.followertracker.loginvalidation")
        check(context.appGraph.repository.accounts().isEmpty()) { "The QA app must have no connected accounts" }
        check(context.appGraph.sessions.metadata(Provider.FACEBOOK) == null)
        val manager = CookieManager.getInstance()
        val before = manager.getCookie(Provider.FACEBOOK.loginUrl).orEmpty().split(';').map { it.trim().substringBefore('=') }
        check(before.none { it in setOf("c_user", "xs") }) { "Existing authentication must never be replaced" }
        suspend fun cookie(value: String) = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                manager.setCookie(Provider.FACEBOOK.loginUrl, value) { if (continuation.isActive) continuation.resume(it) }
            }
        }
        val sessions = SessionStore(context, DataCipher(), "diagnostic-profile-${UUID.randomUUID()}")
        val account = Account(Provider.FACEBOOK, "42", "synthetic-profile",
            profileUrl = "https://www.facebook.com/profile.php?id=42", connectedAt = 1,
            countTransport = CountTransport.PROFILE_BROWSER)
        val reader = ProfilePageCollector(context, sessions) { ctx: Context ->
            object : WebView(ctx) {
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
                    // No resources, network fetches, authentication page or SNS response.
                    settings.blockNetworkLoads = true
                    loadDataWithBaseURL(account.profileUrl, "<html><body></body></html>", "text/html", "UTF-8", account.profileUrl)
                }
            }
        }
        try {
            assertTrue(cookie("c_user=42; Domain=.facebook.com; Path=/; Secure"))
            assertTrue(cookie("xs=synthetic-only; Domain=.facebook.com; Path=/; Secure"))
            sessions.save(Provider.FACEBOOK, SessionMetadata("synthetic-diagnostic", "42", 1))
            var attempt: Long? = null
            try {
                traceCollection(account, false, DiagnosticOperation.COUNT) {
                    attempt = currentCoroutineContext()[DiagnosticAttempt]?.id
                    reader.read(account, "synthetic-diagnostic")
                }
                fail("A missing exact value must not succeed")
            } catch (failure: CollectionFailure) { assertEquals(SyncStatus.FORMAT_CHANGED, failure.status) }
            withTimeout(5_000) { DebugDiagnostics.flush() }
            val records = File(context.noBackupFilesDir, "diagnostics").listFiles().orEmpty()
                .filter { it.name.matches(Regex("collection-[0-3]\\.jsonl")) }
                .map { file ->
                    val lines = file.readText().substringBeforeLast('\n', "").lines()
                    Json.parseToJsonElement(lines.first()).jsonObject.getValue("generation").jsonPrimitive.long to
                        lines.drop(1).filter(String::isNotBlank).map { Json.decodeFromString<DiagnosticRecord>(it) }
                }.sortedBy { it.first }.flatMap { it.second }
            checkNotNull(attempt)
            val start = records.last { it.entry.kind == DiagnosticKind.ATTEMPT_START && it.entry.attempt == attempt }
            assertTrue(records.any { it.run == start.run && it.entry.attempt == attempt &&
                it.entry.kind == DiagnosticKind.PROFILE_FAILURE &&
                it.entry.stage == DiagnosticStage.CAPTURE_TIMEOUT &&
                it.entry.captureError == DiagnosticCaptureError.EXACT_COUNT_MISSING &&
                it.entry.status == SyncStatus.FORMAT_CHANGED })
        } finally {
            withContext(Dispatchers.Main) { reader.release() }
            sessions.invalidate(Provider.FACEBOOK)
            // The precondition proved these exact two cookie names were absent before this fixture.
            assertTrue(cookie("c_user=; Domain=.facebook.com; Path=/; Max-Age=0; Secure"))
            assertTrue(cookie("xs=; Domain=.facebook.com; Path=/; Max-Age=0; Secure"))
            manager.flush()
        }
    }
}
