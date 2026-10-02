package dev.datell.followertracker

import android.content.Intent
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
    @Test fun choosingAProviderOpensLoginAndClosingItReturnsToTracking() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SNS 연결하기").performClick()
        rule.onNodeWithText("Instagram").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Instagram 연결").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Instagram 연결").assertIsDisplayed()
        rule.onNodeWithContentDescription("페이지 새로고침").assertIsDisplayed()
        rule.onNodeWithContentDescription("닫기").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SNS 연결하기").assertIsDisplayed()
    }
    @Test fun relationshipAndSettingsRemainUsableWithoutAnAccount() {
        rule.onNodeWithText("관계").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("연결된 계정이 없어요").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("연결된 계정이 없어요").assertIsDisplayed()
        capture("empty-relationships.png")
        rule.onAllNodesWithText("더보기").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("수집 설정").performClick()
        rule.onNodeWithText("1분 빠른 추적").assertIsDisplayed()
        rule.onNodeWithText("빠른 추적 시작").assertIsNotEnabled()
        rule.onNodeWithText("수집 요청 간격").assertIsDisplayed()
        rule.onNodeWithText("15분").assertIsDisplayed()
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
    @Test fun repeatedWidgetIntentsReturnToTracking() {
        listOf("더보기" to "수집 요청 간격", "관계" to "연결된 계정이 없어요", "위젯" to "첫 계정을 기다려요").forEach { (tab, content) ->
            rule.onAllNodesWithText(tab).filter(hasClickAction()).onFirst().performClick()
            if (tab == "더보기") rule.onNodeWithText("수집 설정").performClick()
            rule.onNodeWithText(content).assertIsDisplayed()
            rule.runOnIdle {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity,
                    Intent(rule.activity, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_TRACKING)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("내 계정부터 연결해보세요").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("내 계정부터 연결해보세요").assertIsDisplayed()
            rule.onNodeWithText(content).assertDoesNotExist()
        }
    }
    @Test fun supportHelpAndBackReturnToThePageThatOpenedThem() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("더보기").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("앱 지원").performClick()
        rule.onNodeWithText("0원").assertIsDisplayed()
        capture("support.png")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("이용 안내"))
        rule.onNodeWithText("이용 안내").performClick()
        rule.onNodeWithText("1분마다 항상 갱신되나요?").assertIsDisplayed().performClick()
        rule.onNodeWithText("홈 또는 수집 설정에서 빠른 추적을 시작하면", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("이전 화면").performClick()
        rule.onNodeWithText("이용 안내").assertIsDisplayed()
        rule.onNodeWithContentDescription("이전 화면").performClick()
        rule.onNodeWithText("무료 기능과 운영 방향 알아보기").assertIsDisplayed()
        capture("more.png")
    }
    @Test fun widgetTabOffersAConnectionWhenNoAccountExists() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("위젯").performClick()
        rule.onNodeWithText("첫 계정을 기다려요").assertIsDisplayed()
        capture("widget-preview-empty.png")
        rule.onNodeWithText("SNS 연결하기").performScrollTo().performClick()
        rule.onNodeWithText("어떤 SNS를 연결할까요?").assertIsDisplayed()
    }
    @Test fun configurationChangePreservesTheOpenPage() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("SNS 연결하기").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("더보기").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("앱 지원").performClick()
        rule.runOnIdle { rule.activity.recreate() }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("0원").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0원").assertIsDisplayed()
        rule.onNodeWithContentDescription("이전 화면").performClick()
        rule.onNodeWithText("무료 기능과 운영 방향 알아보기").assertIsDisplayed()
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(rule.activity.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
