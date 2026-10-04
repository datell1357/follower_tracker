package dev.datell.followertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.datell.followertracker.BuildConfig

enum class MorePage(val title: String, val subtitle: String) {
    SETTINGS("수집 설정", "빠른 추적과 수집 상태"),
    ACCOUNTS("연결된 계정", "SNS 연결과 로그인 관리"),
    SUPPORT("앱 지원", "기본 기능은 무료로"),
    PRIVACY("개인정보와 데이터", "내 기록이 저장되는 곳"),
    HELP("도움말", "궁금한 점을 빠르게 해결해요")
}

@Composable
fun MoreScreen(state: TrackerState, onPage: (MorePage) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToneBadge("기본 기능 무료")
                    Text("내 SNS를,\n내 방식으로.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("연결된 계정 ${state.accounts.size}개 · 이 기기에 기록하고 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            SectionHeading("내 계정과 수집")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    MenuRow("연결된 계정", "SNS 추가, 로그인 확인, 연결 해제", Icons.Outlined.ManageAccounts, { onPage(MorePage.ACCOUNTS) })
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("수집 설정", "빠른 추적 시작과 중지", Icons.Outlined.Tune, { onPage(MorePage.SETTINGS) })
                }
            }
        }
        item {
            SectionHeading("함께 만드는 트래커")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    MenuRow("앱 지원", "무료 기능과 운영 방향 알아보기", Icons.Outlined.FavoriteBorder, { onPage(MorePage.SUPPORT) })
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("도움말", "갱신, 위젯, 관계 분석 안내", Icons.AutoMirrored.Outlined.HelpOutline, { onPage(MorePage.HELP) })
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("개인정보와 데이터", "로그인 세션과 기록 보관 안내", Icons.Outlined.Shield, { onPage(MorePage.PRIVACY) })
                }
            }
        }
        item {
            Text("팔로워 트래커 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("작은 변화도 놓치지 않도록.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SupportScreen(onHelp: () -> Unit, onPrivacy: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("변화를 확인하는 일,\n부담 없이.", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("기본 기능", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("0원", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("팔로워 추적과 위젯을 무료로 이용하세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            SectionHeading("무료로 이용할 수 있어요")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    FeatureBenefit(Icons.Outlined.Insights, "팔로워 수와 변화 기록", "SNS에서 읽은 수치와 수집 시각을 저장해요.")
                    FeatureBenefit(Icons.Outlined.Widgets, "홈 화면 위젯", "앱을 열지 않고 마지막 수집 기록을 확인해요.")
                    FeatureBenefit(Icons.Outlined.PeopleOutline, "관계 비교", "완료된 명단으로 맞팔과 미관측 기록을 비교해요.")
                    FeatureBenefit(Icons.Outlined.Bolt, "1분 빠른 추적", "지원되는 Instagram 계정에서 시작할 수 있어요. 실행 조건과 시간 제한이 있어요.")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("선택해서 지원하기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ToneBadge("준비 중")
                    }
                    Text("광고와 선택 후원 등, 기본 기능을 무료로 유지할 운영 방식을 검토하고 있어요. 제공 방식이 정해지면 이 화면에서 안내할게요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("현재 결제나 구독은 제공하지 않아요.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { InfoPanel("기능별 지원 상태도 확인해주세요", "무료 이용과 SNS 수집 가능 여부는 달라요. 로그인·요청 제한·응답 변경으로 수집이 중지될 수 있으며, 읽지 못한 명단으로 결과를 만들지 않아요.", icon = Icons.Outlined.Info) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    MenuRow("이용 안내", "빠른 추적과 위젯은 어떻게 동작하나요?", Icons.AutoMirrored.Outlined.HelpOutline, onHelp)
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("데이터 처리 안내", "로그인 세션은 어디에 보관되나요?", Icons.Outlined.Shield, onPrivacy)
                }
            }
        }
    }
}

@Composable
private fun FeatureBenefit(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrivacyScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { InfoPanel("내 기기에 보관하는 기록", "팔로워 트래커의 서버로 로그인 세션이나 관계 명단을 업로드하지 않아요. SNS의 공식 페이지와 수집 경로에는 로그인 세션으로 요청해요.", icon = Icons.Outlined.Shield) }
        item { EmptyCard("공식 페이지에서 로그인", "로그인은 각 SNS의 공식 웹페이지에서 직접 진행해요. 앱이 비밀번호 입력값을 읽거나 별도로 저장하지 않아요.") }
        item { EmptyCard("기기 안에 남는 데이터", "로그인 쿠키는 WebView 저장소에 보관해요. 계정과 팔로워 수 기록은 기기 데이터베이스에, 관계 명단과 변화 기록은 암호화해 보관해요. 홈 위젯에는 사용자 이름·수치·마지막 수집 시각이 표시돼요.") }
        item { EmptyCard("연결을 해제하면", "확인창에서 연결 해제를 선택하면 해당 SNS의 로그인 세션과 이 기기의 추적·관계 기록을 삭제해요. SNS 계정 자체나 SNS의 팔로우 관계는 바꾸지 않아요.") }
        item { EmptyCard("자동으로 판단하지 않는 것", "SNS에서 데이터를 읽지 못하면 마지막 기록을 유지해요. 일부 명단만으로 언팔로우를 판단하지 않으며, 명단에서 사라진 이유가 언팔로우·삭제·비활성화 중 무엇인지는 구별할 수 없어요.") }
    }
}

@Composable
fun HelpScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InfoPanel("마지막 수집 시각부터 확인해요", "갱신이 늦어질 때는 수집 시각과 계정 상태를 함께 확인하세요. 네트워크, SNS 요청 제한, 기기의 절전 상태가 영향을 줄 수 있어요.", icon = Icons.Outlined.Schedule) }
        item { FaqItem("1분마다 항상 갱신되나요?", "설정 → 수집 설정에서 빠른 추적을 시작하면 연결된 SNS를 약 1분마다 확인해요. 실행 알림이 표시되며 한 번에 최대 약 6시간 실행해요. 일반 수집은 별도 설정 없이 15분 간격으로 요청해요. 운영체제·네트워크·SNS 요청 제한에 따라 실제 갱신은 늦어질 수 있어요.") }
        item { FaqItem("위젯의 숫자가 그대로예요", "실제 팔로워 수가 같거나 수집이 지연될 수 있어요. 위젯 아래의 수집 시각을 확인하세요. 홈 화면을 길게 눌러 위젯을 추가할 수 있고, 위젯을 누르면 앱의 계정 탭으로 이동해요.") }
        item { FaqItem("위젯이 회색으로 바뀌었어요", "앱을 강제 중지하면 예약 수집과 빠른 추적도 멈춰요. Android 15 이상에서는 위젯이 회색으로 비활성화돼요. 앱 아이콘으로 다시 열면 위젯을 사용할 수 있어요. 빠른 추적은 설정 → 수집 설정에서 다시 시작해주세요.") }
        item { FaqItem("갱신 대기와 로그인 필요는 다른가요?", "갱신 대기는 SNS의 요청 제한일 수 있어요. 대기 시간을 지켜 다시 요청하며, 반복해서 눌러도 우회하지 않아요. 다시 로그인 필요가 표시되면 설정 → 연결된 계정에서 공식 로그인 페이지를 열어주세요.") }
        item { FaqItem("언팔로우를 바로 알 수 있나요?", "전체 팔로워·팔로잉 명단을 끝까지 읽은 뒤 비교해요. 첫 완료 기록은 기준이며, 다음 명단부터 사라진 계정과 이후 확인 기록을 보여줘요. 수치의 감소만으로 특정 계정을 언팔로우로 표시하지 않아요.") }
        item { FaqItem("모든 SNS에서 같은 기능이 되나요?", "SNS마다 읽을 수 있는 데이터와 요청 제한이 달라요. 현재 빠른 추적은 Instagram을 대상으로 해요. 다른 SNS의 수집과 관계 명단은 별도 검증이 필요하며, 지원되지 않는 기능에는 상태를 안내해요.") }
        item { FaqItem("이용료나 자동 결제가 있나요?", "현재 기본 기능은 무료이고 결제·구독은 제공하지 않아요. 운영 방식과 선택 지원 기능은 앱 지원 화면에서 안내할 예정이에요.") }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "답변 접기" else "답변 펼치기", Modifier.padding(start = 12.dp).size(20.dp))
                }
            }
            if (expanded) Text(answer, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
