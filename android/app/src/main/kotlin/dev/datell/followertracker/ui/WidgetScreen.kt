package dev.datell.followertracker.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.os.Build
import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.widget.TrackerWidgetContent
import dev.datell.followertracker.widget.WidgetPinning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WidgetScreen(state: TrackerState, onConnect: () -> Unit) {
    val context = LocalContext.current
    val canPin = remember(context) { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var wide by rememberSaveable { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.accounts) {
        if (state.accounts.none { it.account.key == selected }) selected = state.accounts.firstOrNull()?.account?.key
    }
    val rows = if (wide) state.accounts else state.accounts.filter { it.account.key == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("나의 숫자를,\n홈 화면에.", style = MaterialTheme.typography.headlineMedium)
            Text("한 계정은 작게, 모든 SNS는 한눈에.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !wide, onClick = { wide = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Outlined.CropSquare, null, Modifier.size(18.dp)) }) { Text("내 계정 · 1×1") }
                SegmentedButton(selected = wide, onClick = { wide = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Outlined.Dashboard, null, Modifier.size(18.dp)) }) { Text("모아보기 · 대형") }
            }
        }
        item {
            val shape = RoundedCornerShape(28.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerLow)), shape).padding(20.dp).testTag("widget-preview"), contentAlignment = Alignment.Center) {
                val width = if (wide) minOf(maxWidth, 330.dp) else 88.dp
                val size = DpSize(width, if (wide) 360.dp else 88.dp)
                Box(Modifier.fillMaxWidth().heightIn(min = if (wide) 360.dp else 192.dp), contentAlignment = Alignment.Center) {
                    NativeWidgetPreview(rows, !wide, size, Modifier.size(size.width, size.height).shadow(16.dp, RoundedCornerShape(if (wide) 26.dp else 18.dp)))
                }
            }
            Text(if (wide) "연결된 ${state.accounts.size}개 계정 · 많아지면 위젯 안에서 스크롤" else "가장 작은 1×1 · 숫자가 길면 축약 표시", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!wide && state.accounts.isNotEmpty()) item {
            SectionHeading("표시할 계정")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.selectableGroup()) {
                    state.accounts.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().selectable(selected = selected == row.account.key, role = Role.RadioButton, onClick = { selected = row.account.key }).testTag("widget-option-${row.account.connectionTitle}").padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProviderMark(row.account.provider)
                            Column(Modifier.weight(1f)) {
                                Text(row.account.connectionTitle, style = MaterialTheme.typography.titleSmall)
                                Text(row.account.identityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = selected == row.account.key, onClick = null)
                        }
                    }
                }
            }
        }
        if (wide) item {
            InfoPanel("연결하면 자동으로 포함", "모아보기는 연결된 계정을 모두 표시해요. 각 계정의 팔로워 수, 이전 수집 대비 변화, 마지막 수집 시각을 확인할 수 있어요.", icon = Icons.Outlined.Layers)
        }
        item {
            if (state.accounts.isEmpty()) Button(onClick = onConnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("SNS 연결하기") }
            else Button(onClick = {
                val requestedAccount = if (wide) null else selected
                requesting = true; error = false
                scope.launch {
                    try {
                        if (requestedAccount != null) context.getSharedPreferences("widget_preferences", Context.MODE_PRIVATE).edit { putString("accountKey", requestedAccount) }
                        error = !WidgetPinning.request(context, requestedAccount)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { requesting = false }
                }
            }, enabled = canPin && !requesting && (wide || selected != null), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (requesting) "확인창 여는 중" else if (wide) "모아보기 위젯 추가" else "내 계정 위젯 추가")
            }
            if (error) Text("추가 요청을 열지 못했어요. 홈 화면에서 직접 추가해주세요.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        item { InfoPanel("홈 화면에서 직접 추가하기", "빈 곳 길게 누르기 → 위젯 → 팔로워 트래커에서 내 계정 또는 소셜 모아보기를 선택하세요. 실제 크기는 런처의 격자와 여백에 따라 달라요.", icon = Icons.Outlined.TouchApp) }
        item { InfoPanel("수집 시각까지 확인하세요", "위젯에는 마지막으로 성공한 기록이 표시돼요. 수집이 멈추면 이전 숫자와 시각이 유지돼요. 위젯을 누르면 계정 탭이 열려요. 앱을 강제 중지했다면 앱 아이콘으로 다시 열어주세요.", icon = Icons.Outlined.Schedule) }
    }
}

/** Render the real RemoteViews, rather than maintaining a separate look-alike Compose preview. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
internal fun NativeWidgetPreview(rows: List<AccountOverview>, single: Boolean, size: DpSize, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // API 26–31 collections need a launcher-bound widget ID. Keep the exact row renderer
    // and let the in-app host scroll a full-height preview on those OS versions instead.
    val inline = Build.VERSION.SDK_INT <= 31 && !single
    val renderSize = if (inline) DpSize(size.width, maxOf(size.height.value, (84f + rows.size * 72f) * configuration.fontScale).dp) else size
    var failed by remember { mutableStateOf(false) }
    val remote by produceState<RemoteViews?>(null, rows, single, renderSize, configuration) {
        failed = false
        try {
            value = GlanceRemoteViews().compose(context, size = renderSize) { TrackerWidgetContent(rows, singleAccount = single, inlineOverview = inline) }.remoteViews
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null; failed = true }
    }
    val description = "위젯 미리보기: " + rows.joinToString { "${it.account.connectionTitle} ${it.account.identityLabel}" }
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val content = remote
        if (content != null) Box(Modifier.fillMaxSize().then(if (inline) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
          AndroidView(factory = { AppWidgetHostView(it).apply { setPadding(0, 0, 0, 0) } },
            modifier = if (inline) Modifier.width(size.width).height(renderSize.height) else Modifier.fillMaxSize(), update = { host ->
            if (host.tag !== content) {
                val view = content.apply(context, host)
                host.removeAllViews()
                host.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                host.tag = content
            }
          })
        }
        else if (failed) Text("미리보기를 불러오지 못했어요", style = MaterialTheme.typography.bodySmall)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
