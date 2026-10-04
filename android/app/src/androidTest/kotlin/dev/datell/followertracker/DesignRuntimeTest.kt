package dev.datell.followertracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.sync.RapidTracking
import dev.datell.followertracker.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic screen fixtures only: never writes a user account, session, or metric to storage. */
@RunWith(AndroidJUnit4::class)
class DesignRuntimeTest {
    @get:Rule val rule = createComposeRule()
    private val now = System.currentTimeMillis()
    private fun row(provider: Provider = Provider.INSTAGRAM, status: SyncStatus = SyncStatus.READY): AccountOverview {
        val account = Account(provider, "design_fixture_${provider.name}", "sample_studio", profileUrl = "https://${provider.domain}/sample_studio/",
            status = status, connectedAt = now - 3_600_000)
        return AccountOverview(account, listOf(12_100L, 12_300L, 12_480L).mapIndexed { i, count ->
            MetricSnapshot(account.key, now - (2 - i) * 600_000, count, 320, source = "synthetic-design-fixture")
        })
    }
    private fun state(vararg rows: AccountOverview) = TrackerState(accounts = rows.toList(), loading = false, selectedKey = rows.firstOrNull()?.account?.key)

    @Test fun dashboardShowsRealObservationAndOpensTheSelectedAccount() {
        val row = row()
        var selected: String? = null
        rule.setContent { Frame("계정") { Dashboard(state(row), {}, { selected = it }, {}) } }
        rule.onNodeWithText(formatCount(12_480)).assertIsDisplayed()
        rule.onNodeWithText("갱신 0분 전").assertIsDisplayed()
        rule.onNodeWithText("마지막 수집", substring = true).assertDoesNotExist()
        rule.onNodeWithText("방금 갱신").assertDoesNotExist()
        val followers = rule.onNodeWithText("팔로워", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val recency = rule.onNodeWithText("갱신 0분 전", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Recency must sit to the right of the follower heading", recency.left > followers.right)
        assertTrue("Recency must share the follower heading row", recency.center.y in followers.top..followers.bottom)
        rule.onNodeWithContentDescription("계정 상세").performClick()
        assertEquals(row.account.key, selected)
        capture("design-dashboard-light.png")
        capture("design-account-card.png", card = true)
    }

    @Test fun darkDashboardKeepsDelayedObservationAndShowsTheStatus() {
        val row = row(status = SyncStatus.RATE_LIMITED).let { row ->
            row.copy(history = row.history.map { it.copy(observedAt = it.observedAt - 7 * 60_000) })
        }
        rule.setContent { Frame("계정", dark = true) { Dashboard(state(row), {}, {}, {}) } }
        rule.onNodeWithText("갱신 대기").assertIsDisplayed()
        rule.onNodeWithText("갱신 7분 전").assertIsDisplayed()
        rule.onNodeWithText("갱신 0분 전").assertDoesNotExist()
        rule.onNodeWithText("마지막 수집", substring = true).assertDoesNotExist()
        rule.waitUntil(timeoutMillis = 70_000) {
            rule.onAllNodesWithText("갱신 8분 전").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("갱신 대기").assertIsDisplayed()
        capture("design-dashboard-dark.png")
    }

    @Test fun accountsKeepAllFiveProviderRecordsAndDetailTargetsSeparate() {
        val rows = Provider.entries.mapIndexed { index, provider ->
            row(provider).let { row -> row.copy(history = row.history.map { it.copy(followers = it.followers + index * 10_000) }) }
        }
        var selected: String? = null
        rule.setContent { Frame("계정") { Dashboard(state(*rows.toTypedArray()), {}, { selected = it }, {}) } }
        rule.onNodeWithText("연결된 계정 5개").assertIsDisplayed()
        rule.onNodeWithText("1분 빠른 추적").assertDoesNotExist()
        rule.onNodeWithText("홈 화면에서 바로 확인").assertDoesNotExist()
        rule.onNodeWithText("기기에 기록 · 기본 기능 무료").assertDoesNotExist()
        capture("design-accounts-multiple.png")
        for (row in rows) {
            val card = hasText(row.account.provider.title) and hasClickAction()
            rule.onNode(hasScrollAction()).performScrollToNode(card)
            rule.onNode(card).assert(hasText(formatCount(row.latest!!.followers))).performClick()
            assertEquals(row.account.key, selected)
        }
    }

    @Test fun widgetAccountChoiceChangesThePreviewWithoutMixingProviders() {
        val instagram = row()
        val tiktok = row(Provider.TIKTOK).let { it.copy(account = it.account.copy(username = "sample_music")) }
        rule.setContent { Frame("홈 위젯") { WidgetScreen(state(instagram, tiktok), {}) } }
        rule.onNodeWithText("sample_music", substring = true).performScrollTo()
        rule.onNodeWithTag("widget-option-TikTok").performClick()
        val inPreview = hasAnyAncestor(hasTestTag("widget-preview"))
        rule.onNode(hasText("@sample_music") and inPreview, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("@sample_studio") and inPreview, useUnmergedTree = true).assertDoesNotExist()
        capture("design-widget-preview.png")
    }

    @Test fun smallWidthLargeTypeKeepsTrackingControlsWithoutIntervalChoices() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Frame("수집 설정", small = true) { SettingsScreen(TrackerState(loading = false)) }
            }
        }
        rule.onNodeWithText("수집 요청 간격").assertDoesNotExist()
        for (label in listOf("15분", "30분", "60분", "2시간")) rule.onNodeWithText(label).assertDoesNotExist()
        for (label in listOf("1분 빠른 추적", "빠른 추적 시작")) {
            val node = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            val root = rule.onNodeWithTag("design-frame").fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit the viewport", node.boundsInRoot.left >= root.left && node.boundsInRoot.right <= root.right)
        }
        capture("design-fixed-interval-small-large-type.png")
        rule.onNodeWithText("빠른 추적 시작").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unsupportedRelationshipProviderCannotStartAListRequest() {
        val row = row(Provider.TIKTOK)
        var refreshed = false
        rule.setContent { Frame("관계 분석") { RelationshipScreen(state(row), {}, { refreshed = true }, {}) } }
        rule.onNodeWithText("명단 수집을 확인 중이에요").assertIsDisplayed()
        rule.onNodeWithText("명단 갱신").assertIsNotEnabled()
        assertFalse(refreshed)
    }

    @Test fun stoppedRapidTrackingKeepsTheReasonVisibleInCollectionSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = RapidTracking.state.value
        val message = "기기의 실행 시간 제한으로 중지됐어요. 앱에서 다시 시작해주세요."
        try {
            RapidTracking.update(context, false, message)
            rule.setContent { Frame("수집 설정") { SettingsScreen(state(row())) } }
            rule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("빠른 추적 시작").assertIsEnabled()
        } finally {
            RapidTracking.update(context, previous.running, previous.message, previous.lastSuccessAt)
        }
    }

    @Test fun relationSearchCanBeClearedAndRestoresTheFullVerifiedList() {
        val row = row()
        val report = RelationshipReport(notFollowingBack = listOf(Member("one", "sample_mina", "샘플 미나"), Member("two", "sample_jun", "샘플 준")),
            youDoNotFollowBack = emptyList(), mutual = emptyList(), unfollowCandidates = emptyList(), repeatedAbsences = emptyList(), comparedAt = now, baseline = false)
        val state = state(row).copy(report = report)
        rule.setContent { Frame("관계 분석") { RelationshipScreen(state, {}, {}, {}) } }
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("sample_mina")
        rule.onNodeWithText("1명").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("검색어 지우기").performClick()
        rule.onNodeWithText("2명").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("샘플 준").performScrollTo().assertIsDisplayed()
        capture("design-relationships.png")
    }

    @Test fun supportExplainsFreeFeaturesAndProvidesWorkingInformationLinks() {
        var destination: String? = null
        rule.setContent { Frame("앱 지원") { SupportScreen({ destination = "help" }, { destination = "privacy" }) } }
        rule.onNodeWithText("0원").assertIsDisplayed()
        capture("design-support.png")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("현재 결제나 구독은 제공하지 않아요."))
        rule.onNodeWithText("현재 결제나 구독은 제공하지 않아요.").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("이용 안내"))
        rule.onNodeWithText("이용 안내").performClick()
        assertEquals("help", destination)
        rule.onNodeWithText("데이터 처리 안내").performScrollTo().performClick()
        assertEquals("privacy", destination)
    }

    @Test fun lightAndDarkTextColorsRemainReadableOnTheirSurfaces() {
        for (dark in listOf(false, true)) {
            val colors = trackerColorScheme(dark)
            for ((foreground, background) in listOf(colors.onSurface to colors.surface, colors.onSurfaceVariant to colors.surface,
                colors.onSurfaceVariant to colors.background, colors.primary to colors.surface, colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer, colors.onSecondaryContainer to colors.secondaryContainer)) {
                assertTrue("Text contrast must be at least 4.5:1 (dark=$dark)", ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
            }
        }
    }

    @Composable
    private fun Frame(title: String, dark: Boolean = false, small: Boolean = false, content: @Composable () -> Unit) {
        TrackerTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.widthIn(max = if (small) 320.dp else 600.dp).fillMaxSize().systemBarsPadding().testTag("design-frame")) {
                    Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall)
                    Box(Modifier.weight(1f)) { content() }
                }
            }
        }
    }

    private fun capture(name: String, card: Boolean = false) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val node = if (card) rule.onNode(hasText("Instagram") and hasClickAction()) else rule.onRoot()
        val screenshot = node.captureToImage().asAndroidBitmap()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
