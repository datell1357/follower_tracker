import SwiftUI

struct TrackerSupportView: View {
    @ScaledMetric(relativeTo: .largeTitle) private var priceSize = 44.0
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: "heart").font(.title).accessibilityHidden(true)
                    Text("변화를 확인하는 일,\n부담 없이.").font(.title.bold())
                    Text("기본 기능").font(.subheadline.weight(.semibold))
                    Text("0원").font(.system(size: priceSize, weight: .bold, design: .rounded))
                    Text("팔로워 추적과 위젯을 무료로 이용하세요.").font(.subheadline)
                }.foregroundStyle(TrackerStyle.onBlueSurface).padding(24).frame(maxWidth: .infinity, alignment: .leading)
                    .background(TrackerStyle.blueSurface, in: RoundedRectangle(cornerRadius: 24))
                TrackerSectionTitle(title: "무료로 이용할 수 있어요")
                VStack(alignment: .leading, spacing: 20) {
                    benefit("chart.xyaxis.line", "팔로워 수와 변화 기록", "SNS에서 읽은 수치와 수집 시각을 저장해요.")
                    benefit("square.grid.2x2", "홈 화면 위젯", "앱을 열지 않고 마지막 수집 기록을 확인해요.")
                    benefit("person.2", "관계 비교", "완료된 명단으로 맞팔과 미관측 기록을 비교해요.")
                    benefit("lock.shield", "기기 안에서 계정 관리", "공식 페이지에서 로그인하고 세션과 기록을 기기에 보관해요.")
                }.trackerPanel()
                VStack(alignment: .leading, spacing: 12) {
                    ViewThatFits(in: .horizontal) {
                        HStack { Text("선택해서 지원하기").font(.headline); Spacer(); TrackerBadge(text: "준비 중") }
                        VStack(alignment: .leading, spacing: 8) { Text("선택해서 지원하기").font(.headline); TrackerBadge(text: "준비 중") }
                    }
                    Text("광고와 선택 후원 등, 기본 기능을 무료로 유지할 운영 방식을 검토하고 있어요. 제공 방식이 정해지면 이 화면에서 안내할게요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
                    Text("현재 결제나 구독은 제공하지 않아요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                }.trackerPanel()
                TrackerInfoPanel(title: "기능별 지원 상태도 확인해주세요", detail: "SNS마다 읽을 수 있는 데이터가 달라요. 읽지 못한 명단으로 관계 결과를 만들지 않으며, iOS의 자동 갱신 시각은 운영체제가 정해요.")
                VStack(spacing: 0) {
                    NavigationLink(value: TrackerMorePage.help) { TrackerMenuLabel(title: "이용 안내", subtitle: "갱신과 위젯은 어떻게 동작하나요?", icon: "questionmark.circle") }.buttonStyle(.plain)
                    Divider().padding(.horizontal, 18)
                    NavigationLink(value: TrackerMorePage.privacy) { TrackerMenuLabel(title: "데이터 처리 안내", subtitle: "로그인 세션은 어디에 보관되나요?", icon: "shield") }.buttonStyle(.plain)
                }.background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
            }.foregroundStyle(TrackerStyle.ink).padding(20)
        }.background(TrackerStyle.background).navigationTitle("앱 지원").navigationBarTitleDisplayMode(.inline)
    }
    private func benefit(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(TrackerStyle.blue).frame(width: 26).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(TrackerStyle.muted).fixedSize(horizontal: false, vertical: true)
            }
        }.accessibilityElement(children: .combine)
    }
}

struct TrackerHelpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                TrackerInfoPanel(title: "마지막 수집 시각부터 확인해요", detail: "갱신이 늦어질 때는 수집 시각과 계정 상태를 함께 확인하세요. 네트워크, SNS 요청 제한, 기기의 절전 상태가 영향을 줄 수 있어요.", icon: "clock")
                faq("1분마다 항상 갱신되나요?", "iOS는 백그라운드 실행과 위젯 갱신 시각을 운영체제가 정해요. 이 앱의 예약 요청 간격은 30분·60분·2시간이며 정확히 그 시각에 실행되거나 1분마다 계속 갱신되는 것을 보장하지 않아요.")
                faq("위젯의 숫자가 그대로예요", "실제 팔로워 수가 같거나 수집이 지연될 수 있어요. 아래의 수집 날짜와 시각을 확인하세요. 홈 화면을 길게 눌러 위젯을 추가할 수 있고, 위젯을 누르면 앱의 계정 탭으로 이동해요.")
                faq("갱신 대기와 로그인 필요는 다른가요?", "갱신 대기는 SNS의 요청 제한일 수 있어요. 대기 시간을 지켜 다시 요청하며 반복해서 눌러도 우회하지 않아요. 다시 로그인 필요가 표시되면 설정 → 연결된 계정에서 로그인 페이지를 열어주세요.")
                faq("언팔로우를 바로 알 수 있나요?", "전체 팔로워·팔로잉 명단을 끝까지 읽은 뒤 비교해요. 첫 완료 기록은 기준이며 다음 명단부터 사라진 계정과 이후 확인 기록을 보여줘요. 수치의 감소만으로 특정 계정을 언팔로우로 표시하지 않아요.")
                faq("모든 SNS에서 같은 기능이 되나요?", "SNS마다 읽을 수 있는 데이터와 요청 제한이 달라요. 전체 관계 명단은 현재 Instagram을 대상으로 하며 실제 계정 검증이 더 필요해요. 앱에서 갱신 상태라면 공식 로그인 페이지에서 내 프로필을 열어 수치를 확인해주세요.")
                faq("이용료나 자동 결제가 있나요?", "현재 기본 기능은 무료이고 결제·구독은 제공하지 않아요. 운영 방식과 선택 지원 기능은 앱 지원 화면에서 안내할 예정이에요.")
            }.padding(20)
        }.background(TrackerStyle.background).foregroundStyle(TrackerStyle.ink).navigationTitle("도움말").navigationBarTitleDisplayMode(.inline)
    }
    private func faq(_ question: String, _ answer: String) -> some View {
        DisclosureGroup {
            Text(answer).font(.subheadline).foregroundStyle(TrackerStyle.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 10)
        } label: {
            Text(question).font(.subheadline.weight(.semibold)).frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
        }.trackerPanel(padding: 18)
    }
}

struct TrackerPrivacyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                TrackerInfoPanel(title: "내 기기에 보관하는 기록", detail: "팔로워 트래커의 서버로 로그인 세션이나 관계 명단을 업로드하지 않아요. SNS의 공식 페이지와 수집 경로에는 로그인 세션으로 요청해요.", icon: "shield")
                EmptyCard("공식 페이지에서 로그인", "로그인은 각 SNS의 공식 웹페이지에서 직접 진행해요. 앱이 비밀번호 입력값을 읽거나 별도로 저장하지 않아요.")
                EmptyCard("기기 안에 남는 데이터", "로그인 쿠키는 Keychain에 보관해요. 계정과 수치 기록은 기기 데이터베이스에, 관계 명단과 변화 기록은 암호화해 보관해요. 홈 위젯에는 사용자 이름·수치·마지막 수집 시각이 표시돼요.")
                EmptyCard("연결을 해제하면", "확인창에서 연결 해제를 선택하면 해당 SNS의 로그인 세션과 이 기기의 추적·관계 기록을 삭제해요. SNS 계정 자체나 SNS의 팔로우 관계는 바꾸지 않아요.")
                EmptyCard("자동으로 판단하지 않는 것", "SNS에서 데이터를 읽지 못하면 마지막 기록을 유지해요. 일부 명단만으로 언팔로우를 판단하지 않으며, 명단에서 사라진 이유가 언팔로우·삭제·비활성화 중 무엇인지는 구별할 수 없어요.")
            }.padding(20)
        }.background(TrackerStyle.background).navigationTitle("개인정보와 데이터").navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("앱 지원") { NavigationStack { TrackerSupportView() } }
#Preview("도움말 · 큰 글자") { NavigationStack { TrackerHelpView() }.environment(\.dynamicTypeSize, .accessibility1) }
