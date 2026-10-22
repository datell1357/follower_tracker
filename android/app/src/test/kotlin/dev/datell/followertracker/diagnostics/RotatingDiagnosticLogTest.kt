package dev.datell.followertracker.diagnostics

import dev.datell.followertracker.core.*
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { explicitNulls = false }
    private val allFieldsJson = Json { encodeDefaults = true }
    private val run = "a".repeat(32)
    private fun record(id: Int) = DiagnosticRecord(id.toLong(), run,
        DiagnosticEntry(DiagnosticKind.STATUS_SAVED, Provider.FACEBOOK, AccountType.PROFILE,
            attempt = id.toLong() + 1, status = SyncStatus.FORMAT_CHANGED, localRetryAt = 60_000L + id))

    private fun records(directory: File): List<DiagnosticRecord> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("collection-[0-7]\\.jsonl")) }
        .map { file ->
            val lines = file.readLines()
            val generation = json.parseToJsonElement(lines.first()).jsonObject.getValue("generation").jsonPrimitive.long
            generation to lines.drop(1).map { json.decodeFromString<DiagnosticRecord>(it) }
        }.sortedBy { it.first }.flatMap { it.second }

    @Test fun rotationKeepsACompleteBoundedSuffixAndDoesNotTouchOtherFiles() {
        val directory = temporary.newFolder()
        val other = File(directory, "keep.txt").apply { writeText("unrelated") }
        val log = RotatingDiagnosticLog(directory, slotBytes = 1_024, slots = 3)
        repeat(100) { log.append(record(it)) }
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 1_024 })
        val saved = records(directory)
        assertTrue(saved.isNotEmpty() && saved.size < 100)
        assertEquals((saved.first().atMillis..99L).toList(), saved.map { it.atMillis })
        assertEquals("unrelated", other.readText())
    }

    @Test fun processRecreationAppendsAfterTheLatestGenerationEvenWhenWallTimeMovesBackwards() {
        val directory = temporary.newFolder()
        val log = RotatingDiagnosticLog(directory, slotBytes = 1_024, slots = 3)
        repeat(10) { log.append(record(it)) }
        directory.listFiles().orEmpty().forEachIndexed { index, file -> file.setLastModified(10_000L - index * 1_000) }
        RotatingDiagnosticLog(directory, slotBytes = 1_024, slots = 3).append(record(10).copy(atMillis = -1))
        val saved = records(directory)
        assertEquals(-1L, saved.last().atMillis)
        assertEquals(9L, saved[saved.lastIndex - 1].atMillis)
        assertTrue(directory.listFiles().orEmpty().all { it.length() <= 1_024 })
    }

    @Test fun aPartialLastLineFromProcessDeathDoesNotDestroyEarlierRecordsOrJoinTheNextEntry() {
        val directory = temporary.newFolder()
        RotatingDiagnosticLog(directory).append(record(1))
        File(directory, "collection-0.jsonl").appendText("{\"entry\":")
        RotatingDiagnosticLog(directory).append(record(2))
        assertEquals(listOf(record(1), record(2)), records(directory))
    }

    @Test fun profileDiagnosticsDropUnknownTextInsteadOfRetainingAUrlOrSecret() {
        val unsafe = "https://www.facebook.com/private-profile?token=synthetic-secret\nCookie: synthetic-session"
        val entry = DiagnosticEntry(DiagnosticKind.PROFILE_FAILURE, Provider.FACEBOOK,
            stage = DiagnosticStage.profile(unsafe), status = SyncStatus.CHECK_REQUIRED)
        val encoded = json.encodeToString(DiagnosticRecord(1, run, entry))
        assertFalse(encoded.contains("synthetic")); assertFalse(encoded.contains("private-profile"))
        assertEquals(DiagnosticStage.CAPTURE, entry.stage)
        assertEquals(DiagnosticStage.HTTP, DiagnosticStage.profile("HTTP_429"))
        assertEquals(DiagnosticStage.CAPTURE, DiagnosticStage.profile("HTTP_999"))
        val fields = allFieldsJson.encodeToJsonElement(entry).jsonObject.keys
        assertEquals(emptySet<String>(), fields.intersect(setOf("accountKey", "stableId", "username", "name", "url",
            "message", "exception", "cookie", "token", "body", "followers", "following")))
    }

    @Test fun untrustedRunLabelsAndOutOfRangeValuesCannotBecomeLogContent() {
        try { DiagnosticRecord(1, "arbitrary secret", record(1).entry); fail("Run labels must be generated identifiers") }
        catch (_: IllegalArgumentException) { }
        try { DiagnosticEntry(DiagnosticKind.HTTP_RESPONSE, httpStatus = 999); fail("Invalid HTTP status") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun oldProcessRecordsRemainReadableAfterASecondRun() {
        val directory = temporary.newFolder()
        RotatingDiagnosticLog(directory).append(record(1))
        val second = record(2).copy(run = "b".repeat(32))
        RotatingDiagnosticLog(directory).append(second)
        assertEquals(listOf(record(1), second), records(directory))
    }

    @Test fun captureTimeoutsKeepOnlyTheAllowlistedReasonAndNeverTheOriginalPayload() {
        val directory = temporary.newFolder()
        val known = record(1).copy(entry = DiagnosticEntry(DiagnosticKind.PROFILE_FAILURE,
            stage = DiagnosticStage.CAPTURE_TIMEOUT, captureError = DiagnosticCaptureError.from("exact_count_missing")))
        val unknown = record(2).copy(entry = known.entry.copy(captureError =
            DiagnosticCaptureError.from("https://private.example/?cookie=synthetic-hidden")))
        val log = RotatingDiagnosticLog(directory)
        log.append(known); log.append(unknown)
        val saved = records(directory)
        assertEquals(DiagnosticCaptureError.EXACT_COUNT_MISSING, saved.first().entry.captureError)
        assertEquals(DiagnosticCaptureError.UNKNOWN, saved.last().entry.captureError)
        assertFalse(directory.listFiles().orEmpty().any { it.readText().contains("synthetic-hidden") })
    }
}
