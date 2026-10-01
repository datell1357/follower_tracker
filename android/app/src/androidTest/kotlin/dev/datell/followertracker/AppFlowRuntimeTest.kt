package dev.datell.followertracker

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.ui.MainActivity
import dev.datell.followertracker.ui.TrackerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFlowRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun emptyDashboardShowsConnectionAndWidgetGuidance() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("내 계정부터 연결해보세요").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SNS 연결하기").assertIsDisplayed()
        rule.onNodeWithText("홈 화면에서 바로 확인").assertIsDisplayed()
        capture("empty-dashboard.png")
    }
    @Test fun providerPickerIncludesAllFivePlatforms() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SNS 연결하기").performClick()
        listOf("Instagram", "TikTok", "X", "Facebook", "Reddit").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        capture("provider-picker.png")
    }
    @Test fun relationshipAndSettingsRemainUsableWithoutAnAccount() {
        rule.onNodeWithText("관계").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("연결된 계정이 없어요").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("연결된 계정이 없어요").assertIsDisplayed()
        capture("empty-relationships.png")
        rule.onNodeWithText("설정").performClick()
        rule.onNodeWithText("수집 요청 간격").assertIsDisplayed()
        rule.onNodeWithText("30분").assertIsDisplayed()
        rule.onNodeWithText("60분").assertIsDisplayed()
        rule.onNodeWithText("2시간").assertIsDisplayed()
        capture("settings.png")
    }
    @Test fun repeatedActionsShareOneCancelableJob() {
        lateinit var model: TrackerViewModel
        lateinit var job: Job
        rule.runOnIdle {
            model = ViewModelProvider(rule.activity)[TrackerViewModel::class.java]
            job = model.refresh()
            assertSame("A second tap must preserve the job canceled when the login sheet closes", job, model.refresh())
            job.cancel()
        }
        runBlocking { job.join() }
        rule.waitUntil(15_000) { !model.state.value.busy }
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(rule.activity.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
