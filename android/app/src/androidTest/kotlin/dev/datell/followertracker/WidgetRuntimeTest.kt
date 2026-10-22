package dev.datell.followertracker

import android.content.Context
import android.content.res.Configuration
import android.appwidget.AppWidgetHostView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ListView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.widget.TrackerWidgetContent
import dev.datell.followertracker.ui.compactObservationTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetRuntimeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sizes = listOf(DpSize(180.dp, 84.dp), DpSize(300.dp, 180.dp), DpSize(300.dp, 360.dp), DpSize(160.dp, 300.dp))

    @Test fun emptyWidgetsShowConnectionGuidanceAtEverySize() = runBlocking {
        sizes.forEachIndexed { index, size ->
            val text = render(emptyList(), size, "widget-empty-$index.png")
            assertTrue(text.any { it.contains("SNS 연결") })
            assertTrue(text.any { it.contains("앱 열기") })
            assertFalse(text.contains("0"))
        }
    }
    @Test fun storageFailureDoesNotRenderAnEmptyAccountOrZeroCount() = runBlocking {
        val text = render(null, sizes.first(), "widget-storage-error.png")
        assertTrue(text.any { it.contains("기록을 읽지 못했어요") })
        assertFalse(text.contains("SNS 연결하기"))
        assertFalse(text.contains("0"))
    }
    @Test fun oneCellEmptyAndUnreadableWidgetsKeepTheirGuidanceInsideTheHost() = runBlocking {
        for (scale in listOf(1f, 2f)) for (side in listOf(40, 64, 88)) {
            val size = DpSize(side.dp, side.dp)
            val empty = render(emptyList(), size, "widget-one-cell-empty-$side-$scale.png", fontScale = scale)
            assertEquals(listOf("SNS 연결", "앱 열기"), empty)
            val failed = render(null, size, "widget-one-cell-unreadable-$side-$scale.png", fontScale = scale)
            assertEquals(listOf("읽기 오류", "앱 열기"), failed)
        }
    }
    @Test fun failedRefreshKeepsTheLastCountAndActualTimeAtEverySize() = runBlocking {
        for (status in listOf(SyncStatus.REAUTH_REQUIRED, SyncStatus.OFFLINE)) {
            for ((index, size) in sizes.withIndex()) {
                val owner = row(Provider.INSTAGRAM, status)
                val text = render(listOf(owner), size, "widget-${status.name.lowercase()}-$index.png")
                assertTrue(text.contains("123,456"))
                assertFalse(text.any { it.contains("+3") })
                assertTrue(text.contains("!"))
                assertTrue(text.any { it.contains(compactObservationTime(owner.latest!!.observedAt)) })
            }
        }
    }

    @Test fun widgetsShowTheLastSuccessfulObservationTimeAfterTrackingStops() = runBlocking {
        val original = row(Provider.INSTAGRAM)
        val now = System.currentTimeMillis()
        val stale = original.copy(history = listOf(
            original.history.first().copy(observedAt = now - 8 * 3_600_000),
            original.history.last().copy(observedAt = now - 6 * 3_600_000)))
        val observed = "${compactObservationTime(stale.latest!!.observedAt)} 수집"
        for ((index, size) in sizes.withIndex()) {
            val text = render(listOf(stale), size, "widget-last-observation-$index.png")
            assertTrue(text.any { it.contains(observed.removeSuffix(" 수집")) })
            assertFalse(text.any { it.contains("방금 갱신") || it.contains("시간 전 갱신") })
        }
        val paused = render(listOf(stale.copy(account = stale.account.copy(status = SyncStatus.RATE_LIMITED))), sizes.first(), "widget-last-observation-paused.png")
        assertTrue(paused.any { it.contains(observed.removeSuffix(" 수집")) })
        assertTrue(paused.contains("!"))
    }
    @Test fun overviewShowsAllFiveProvidersWithoutAThreeAccountCutoff() = runBlocking {
        val rows = Provider.entries.map { row(it) }
        val large = render(rows, sizes[2], "widget-overview-five.png") { view, _, _ ->
            val descriptions = contentDescriptions(view)
            rows.forEach { assertTrue("Each SNS must keep its own icon", descriptions.contains(it.account.provider.title)) }
        }
        rows.forEach { assertFalse("Names are hidden by default", large.contains(it.account.identityLabel)) }
        assertTrue(large.contains("5개 계정"))
    }

    @Test fun oneCellWidgetsKeepTheProviderCountAndActualTimeWithoutClipping() = runBlocking {
        for (provider in Provider.entries) {
            for (size in listOf(DpSize(40.dp, 40.dp), DpSize(64.dp, 72.dp), DpSize(88.dp, 88.dp))) {
                val owner = row(provider, followers = 1_234_567_890)
                val text = render(listOf(owner), size,
                    "widget-one-cell-${provider.name}-${size.width.value.toInt()}.png")
                assertTrue(text.contains("12.34억"))
                assertTrue(text.contains(compactObservationTime(owner.latest!!.observedAt)))
            }
        }
    }

    @Test fun overviewKeepsTheFailureReasonAndLastActualObservationForEachAccount() = runBlocking {
        val rows = listOf(row(Provider.INSTAGRAM, SyncStatus.REAUTH_REQUIRED), row(Provider.TIKTOK, SyncStatus.OFFLINE))
        val text = render(rows, sizes[2], "widget-overview-last-success.png")
        rows.forEach { owner ->
            assertTrue(text.contains(owner.account.status.label))
            assertTrue(text.contains("${compactObservationTime(owner.latest!!.observedAt)} 수집"))
        }
    }
    @Test fun horizontalWidgetsPrioritizeFollowersAndActualTimeWithoutExtraMetrics() = runBlocking {
        val original = row(Provider.INSTAGRAM, followers = 950)
        val owner = original.copy(history = original.history.map { it.copy(following = 740) })
        for (size in listOf(DpSize(180.dp, 84.dp), DpSize(300.dp, 160.dp))) {
            val labels = render(listOf(owner), size, "widget-horizontal-${size.width.value}.png")
            assertTrue(labels.contains("950"))
            assertFalse(labels.any { it.startsWith("팔로잉") || it.startsWith("이전") || it.contains("+3") || it.contains(" 대비") })
            assertTrue(labels.any { it.contains(compactObservationTime(owner.latest!!.observedAt)) })
        }
    }

    @Test fun accountNameCanBeHiddenOrShownWithoutChangingTheFollowerObservation() = runBlocking {
        for (overview in listOf(false, true)) for (visible in listOf(false, true)) {
            val owner = row(Provider.TIKTOK, followers = 0)
            val rows = if (overview) listOf(owner, row(Provider.INSTAGRAM)) else listOf(owner)
            val labels = render(rows, if (overview) sizes[2] else DpSize(220.dp, 96.dp),
                "widget-name-$overview-$visible.png", showAccountName = visible)
            assertEquals(visible, labels.contains(owner.account.identityLabel))
            assertTrue(labels.contains("0"))
            assertTrue(labels.any { it.contains(compactObservationTime(owner.latest!!.observedAt)) })
        }
    }

    @Test fun largeSystemTypeKeepsSmallWidgetValuesInsideTheHost() = runBlocking {
        for (scale in listOf(1.5f, 2f)) for (size in listOf(DpSize(40.dp, 40.dp), DpSize(88.dp, 88.dp), sizes.first())) {
            val text = render(listOf(row(Provider.FACEBOOK, followers = 1_234_567_890)), size,
                "widget-font-$scale-${size.width.value.toInt()}.png", fontScale = scale)
            assertTrue(text.any { it == "12.34억" || it == "1,234,567,890" })
        }
    }

    @Test fun scrollingOverviewContainsEveryConnectionIncludingAccountsBeyondFive() = runBlocking {
        val rows = Provider.entries.map { row(it) } + (1..2).map { index ->
            val original = row(Provider.FACEBOOK)
            original.copy(account = original.account.copy(stableId = "fixture_extra_$index", username = "extra_$index"))
        }
        val size = DpSize(300.dp, 180.dp)
        val composition = GlanceRemoteViews().compose(context, size = size) { TrackerWidgetContent(rows, showAccountName = true) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = composition.remoteViews.apply(context, AppWidgetHostView(context))
            fun list(node: View): ListView? {
                if (node is ListView) return node
                if (node is ViewGroup) for (i in 0 until node.childCount) list(node.getChildAt(i))?.let { return it }
                return null
            }
            val collection = checkNotNull(list(root)) { "Short overview must use a scrolling collection" }
            val adapter = checkNotNull(collection.adapter) { "Collection data must be available" }
            assertEquals("No account may be truncated", rows.size, adapter.count)
            val expected = rows.map { it.account.identityLabel }.toSet()
            val labels = mutableSetOf<String>()
            fun read(node: View) {
                if (node is TextView) labels += node.text.toString()
                if (node is ViewGroup) for (i in 0 until node.childCount) read(node.getChildAt(i))
            }
            repeat(adapter.count) { index -> read(adapter.getView(index, null, FrameLayout(context))) }
            assertTrue("Every account must be reachable through the collection", labels.containsAll(expected))
        }
    }

    @Test fun longFollowerCountsAreNotEllipsizedAtAnySize() = runBlocking {
        val owner = row(Provider.INSTAGRAM, followers = 1_234_567_890)
        sizes.forEachIndexed { index, size ->
            val labels = render(listOf(owner), size, "widget-long-count-$index.png") { view, _, _ ->
                assertTrue("The accessibility value must remain exact", contentDescriptions(view).any { it.contains("1,234,567,890") })
            }
            assertTrue(labels.any { it == "1,234,567,890" || it == "12.34억" })
        }
    }

    @Test fun horizontalWidgetsPlaceTheFollowerNumberOnTheRight() = runBlocking {
        val owner = row(Provider.INSTAGRAM, followers = 1_234)
        for (size in listOf(DpSize(180.dp, 84.dp), DpSize(300.dp, 160.dp))) {
            render(listOf(owner), size, "widget-right-count-${size.width.value}.png") { view, width, _ ->
                val count = countView(view).first
                val location = IntArray(2); count.getLocationInWindow(location)
                val root = IntArray(2); view.getLocationInWindow(root)
                assertTrue("The count must occupy the right half", location[0] - root[0] + count.width / 2f > width * .6f)
                assertTrue("The primary count must remain readable", count.textSize / context.resources.displayMetrics.density >= 24f)
            }
        }
    }

    @Test fun widgetsRemoveChangesAndComparisonDatesWhileKeepingEachLatestObservation() = runBlocking {
        val rows = listOf(Provider.INSTAGRAM, Provider.TIKTOK, Provider.REDDIT).mapIndexed { index, provider ->
            val original = row(provider)
            original.copy(history = listOf(original.history.first().copy(observedAt = original.history.last().observedAt - (index + 1) * 86_400_000L), original.history.last()))
        }
        for (values in listOf(listOf(rows.first()), rows)) {
            val labels = render(values, sizes[2], "widget-no-comparison-${values.size}.png")
            assertFalse(labels.any { it.contains(" 대비") || it.contains("+3") })
            values.forEach { owner -> assertTrue(labels.any { it.contains(compactObservationTime(owner.latest!!.observedAt)) }) }
        }
    }

    private fun row(provider: Provider, status: SyncStatus = SyncStatus.READY, followers: Long = 123_456): AccountOverview {
        val account = Account(provider, "fixture_${provider.name}", "fixture_owner", profileUrl = provider.loginUrl,
            connectedAt = 1, status = status)
        val now = System.currentTimeMillis()
        return AccountOverview(account, listOf(
            MetricSnapshot(account.key, now - 3_600_000, (followers - 3).coerceAtLeast(0), following = null, source = "synthetic-widget-fixture"),
            MetricSnapshot(account.key, now - 60_000, followers, following = null, source = "synthetic-widget-fixture")))
    }

    private fun contentDescriptions(view: View): List<String> {
        val values = mutableListOf<String>()
        fun read(node: View) {
            node.contentDescription?.let { values += it.toString() }
            if (node is ViewGroup) for (index in 0 until node.childCount) read(node.getChildAt(index))
        }
        read(view)
        return values
    }

    private fun countView(view: View): Pair<TextView, Int> {
        fun find(node: View, parentTop: Int): Pair<TextView, Int>? {
            val top = parentTop + node.top
            if (node is TextView && node.text.toString() == "1,234") return node to top
            if (node is ViewGroup) for (index in 0 until node.childCount) {
                find(node.getChildAt(index), top)?.let { return it }
            }
            return null
        }
        return checkNotNull(find(view, 0)) { "Count is missing from the rendered widget" }
    }

    private suspend fun render(rows: List<AccountOverview>?, size: DpSize, name: String, fontScale: Float? = null, showAccountName: Boolean = false,
        verifyView: ((View, Int, Int) -> Unit)? = null): List<String> {
        val context = fontScale?.let { scale -> this.context.createConfigurationContext(Configuration(this.context.resources.configuration).apply { this.fontScale = scale }) } ?: this.context
        val composition = GlanceRemoteViews().compose(context, size = size) { TrackerWidgetContent(rows, singleAccount = rows?.size == 1, showAccountName = showAccountName) }
        var text = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = composition.remoteViews.apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val width = (size.width.value * density).roundToInt()
            val height = (size.height.value * density).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val diagnostics = mutableListOf("size=$size;density=$density;fontScale=${context.resources.configuration.fontScale}")
            fun describe(node: View) {
                if (node is TextView && node.text.isNotBlank()) diagnostics += "${node.text}:width=${node.width};height=${node.height};textSize=${node.textSize};measuredText=${node.paint.measureText(node.text.toString())};padding=${node.compoundPaddingLeft},${node.compoundPaddingRight};ellipsis=${node.layout?.getEllipsisCount(0)}"
                if (node is ViewGroup) for (i in 0 until node.childCount) describe(node.getChildAt(i))
            }
            describe(view)
            File(directory, "$name.layout.txt").writeText(diagnostics.joinToString("\n"))
            val labels = mutableListOf<String>()
            fun inspect(node: View, parentTop: Int) {
                val top = parentTop + node.top
                if (node is TextView && node.text.isNotBlank()) {
                    labels += node.text.toString()
                    assertTrue("Widget text has no visible width: ${node.text}", node.width > 0)
                    assertTrue("Widget text has no visible height: ${node.text}", node.height > 0)
                    assertTrue("Widget text extends above its host: ${node.text}", top >= 0)
                    assertTrue("Widget text extends below its host: ${node.text}", top + node.height <= height)
                    val visible = Rect()
                    assertTrue("Widget text is hidden by its parent: ${node.text}", node.getLocalVisibleRect(visible))
                    assertEquals("Widget text is clipped vertically: ${node.text}", node.height, visible.height())
                    if (node.text.toString() in setOf("123,456", "1,234,567,890", "12.34억") || node.text.contains(" 대비")) {
                        val layout = checkNotNull(node.layout)
                        for (line in 0 until layout.lineCount) assertEquals("Count and comparison date must be fully visible: ${node.text}", 0, layout.getEllipsisCount(line))
                    }
                }
                if (node is ViewGroup) for (index in 0 until node.childCount) inspect(node.getChildAt(index), top)
            }
            inspect(view, 0)
            text = labels
            verifyView?.invoke(view, width, height)
        }
        return text
    }
}
