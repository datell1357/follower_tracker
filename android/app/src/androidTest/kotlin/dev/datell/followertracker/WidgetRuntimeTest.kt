package dev.datell.followertracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.widget.TrackerWidgetContent
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
    private val sizes = listOf(DpSize(160.dp, 160.dp), DpSize(300.dp, 180.dp), DpSize(300.dp, 300.dp))

    @Test fun emptyWidgetsShowConnectionGuidanceAtEverySize() = runBlocking {
        sizes.forEachIndexed { index, size ->
            val text = render(emptyList(), size, "widget-empty-$index.png")
            assertTrue(text.contains("SNS를 연결해보세요"))
            assertTrue(text.contains("눌러서 앱 열기"))
            assertFalse(text.contains("0"))
        }
    }
    @Test fun storageFailureDoesNotRenderAnEmptyAccountOrZeroCount() = runBlocking {
        val text = render(null, sizes.first(), "widget-storage-error.png")
        assertTrue(text.any { it.contains("기록을 읽지 못했어요") })
        assertFalse(text.contains("SNS를 연결해보세요"))
        assertFalse(text.contains("0"))
    }
    @Test fun reauthenticationKeepsTheLastCountAndChange() = runBlocking {
        val text = render(listOf(row(Provider.INSTAGRAM, SyncStatus.REAUTH_REQUIRED)), sizes.first(), "widget-reauth.png")
        assertTrue(text.contains("123,456"))
        assertTrue(text.any { it.contains("+3") })
        assertTrue(text.any { it.startsWith("다시 로그인 필요") })
    }
    @Test fun mediumAndLargeWidgetsFitThreeAndFiveAccounts() = runBlocking {
        val rows = Provider.entries.map { row(it) }
        val medium = render(rows, sizes[1], "widget-medium.png")
        rows.take(3).forEach { assertTrue(medium.contains(it.account.provider.title)) }
        rows.drop(3).forEach { assertFalse(medium.contains(it.account.provider.title)) }
        val large = render(rows, sizes[2], "widget-large.png")
        rows.forEach { assertTrue(large.contains(it.account.provider.title)) }
    }

    @Test fun longFollowerCountsAreNotEllipsizedAtAnySize() = runBlocking {
        val owner = row(Provider.INSTAGRAM, followers = 1_234_567_890)
        sizes.forEachIndexed { index, size ->
            assertTrue(render(listOf(owner), size, "widget-long-count-$index.png").contains("1,234,567,890"))
        }
    }

    private fun row(provider: Provider, status: SyncStatus = SyncStatus.READY, followers: Long = 123_456): AccountOverview {
        val account = Account(provider, "fixture_${provider.name}", "fixture_owner", profileUrl = provider.loginUrl,
            connectedAt = 1, status = status)
        val now = System.currentTimeMillis()
        return AccountOverview(account, listOf(
            MetricSnapshot(account.key, now - 3_600_000, followers - 3, following = null, source = "synthetic-widget-fixture"),
            MetricSnapshot(account.key, now - 60_000, followers, following = null, source = "synthetic-widget-fixture")))
    }

    private suspend fun render(rows: List<AccountOverview>?, size: DpSize, name: String): List<String> {
        val composition = GlanceRemoteViews().compose(context, size = size) { TrackerWidgetContent(rows) }
        var text = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = composition.remoteViews.apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val width = (size.width.value * density).roundToInt()
            val height = (size.height.value * density).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val labels = mutableListOf<String>()
            fun inspect(node: View, parentTop: Int) {
                val top = parentTop + node.top
                if (node is TextView && node.text.isNotBlank()) {
                    labels += node.text.toString()
                    assertTrue("Widget text has no visible width: ${node.text}", node.width > 0)
                    assertTrue("Widget text has no visible height: ${node.text}", node.height > 0)
                    assertTrue("Widget text extends above its host: ${node.text}", top >= 0)
                    assertTrue("Widget text extends below its host: ${node.text}", top + node.height <= height)
                    if (node.text.toString() in setOf("123,456", "1,234,567,890")) {
                        val layout = checkNotNull(node.layout)
                        for (line in 0 until layout.lineCount) assertEquals("Follower count must be fully visible", 0, layout.getEllipsisCount(line))
                    }
                }
                if (node is ViewGroup) for (index in 0 until node.childCount) inspect(node.getChildAt(index), top)
            }
            inspect(view, 0)
            text = labels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        return text
    }
}
