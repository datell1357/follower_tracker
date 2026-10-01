package dev.datell.followertracker

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RelationshipHistoryUiRuntimeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun historyShowsObservationDatesAndSearchesReobservedAsWellAsMissingMembers() {
        val owner = Account(Provider.INSTAGRAM, "ui_fixture_owner", "fixture_owner",
            profileUrl = "https://www.instagram.com/fixture_owner/", connectedAt = 1)
        val base = 1_790_788_800_000L
        fun scan(ids: List<String>, index: Int, direction: Direction = Direction.FOLLOWERS) =
            RelationshipSnapshot(owner.key, direction, base + index * 60_000, base + index * 60_000 + 1,
                ids.map { Member(it, "fixture_$it", "합성 계정 $it") }, true, true, "synthetic-ui-fixture")
        val scans = listOf(scan(listOf("a", "b", "c"), 1), scan(listOf("b", "c"), 2), scan(listOf("c"), 3), scan(listOf("a"), 4))
        var events = emptyList<RelationshipChange>()
        scans.forEachIndexed { index, current -> events = RelationshipHistory.observe(current, scans.getOrNull(index - 1), events) }
        val state = TrackerState(accounts = listOf(AccountOverview(owner, emptyList())), loading = false, selectedKey = owner.key,
            report = RelationshipAnalyzer.compare(scans.last(), scan(listOf("a"), 4, Direction.FOLLOWING), scans[2], scans[1]),
            relationshipChanges = events.sortedByDescending { it.detectedAt })
        // Fixtures are passed straight to the screen; no user account or session is stored.
        rule.setContent { TrackerTheme { RelationshipScreen(state, {}, {}, {}) } }
        rule.onNodeWithText("언팔로우 추정").performClick()
        rule.onNodeWithText("3개 기록").performScrollTo().assertIsDisplayed()
        val search = rule.onNode(hasSetTextAction())
        for ((id, label) in listOf("c" to "처음 미관측", "b" to "연속 미관측", "a" to "다시 관측됨")) {
            search.performTextReplacement("fixture_$id")
            rule.onNodeWithText("1개 기록").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val event = events.single { it.member.id == id }
            rule.onNodeWithText("미관측: ${observationTime(event.detectedAt)}").assertIsDisplayed()
            rule.onNodeWithText("확인: ${observationTime(event.checkedAt)} · ${event.absenceChecks}회 미관측").assertIsDisplayed()
            capture("relationship-history-$id.png")
        }
        search.performTextReplacement("missing_fixture")
        rule.onNodeWithText("0개 기록").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("검색어를 바꿔보세요.").assertIsDisplayed()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
