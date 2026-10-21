import SwiftUI
import FollowerCore

enum TrackerMorePage: Hashable { case accounts, settings, support, help, privacy }

struct TrackerMoreView: View {
    let accountCount: Int
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 10) {
                    TrackerBadge(text: "기본 기능 무료")
                    Text("내 SNS를,\n내 방식으로.").font(.title.bold()).foregroundStyle(TrackerStyle.onBlueSurface)
                    Text("연결된 계정 \(accountCount)개 · 이 기기에 기록하고 있어요.").font(.subheadline).foregroundStyle(TrackerStyle.onBlueSurface)
                }.padding(22).frame(maxWidth: .infinity, alignment: .leading).background(TrackerStyle.blueSurface, in: RoundedRectangle(cornerRadius: 24))
                TrackerSectionTitle(title: "내 계정과 수집")
                VStack(spacing: 0) {
                    link(.accounts, "연결된 계정", "SNS 추가, 로그인 확인, 연결 해제", "person.crop.circle")
                    Divider().padding(.horizontal, 18)
                    link(.settings, "수집 설정", "자동 수집과 명단 안내", "slider.horizontal.3")
                }.background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
                TrackerSectionTitle(title: "함께 만드는 트래커")
                VStack(spacing: 0) {
                    link(.support, "앱 지원", "무료 기능과 운영 방향 알아보기", "heart")
                    Divider().padding(.horizontal, 18)
                    link(.help, "도움말", "갱신, 위젯, 관계 분석 안내", "questionmark.circle")
                    Divider().padding(.horizontal, 18)
                    link(.privacy, "개인정보와 데이터", "로그인 세션과 기록 보관 안내", "shield")
                }.background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
                Text("팔로워 트래커 " + (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
                    .font(.caption).foregroundStyle(TrackerStyle.muted)
                Text("작은 변화도 놓치지 않도록.").font(.caption).foregroundStyle(TrackerStyle.muted)
            }.padding(20)
        }.background(TrackerStyle.background)
    }
    private func link(_ page: TrackerMorePage, _ title: String, _ subtitle: String, _ icon: String) -> some View {
        NavigationLink(value: page) { TrackerMenuLabel(title: title, subtitle: subtitle, icon: icon) }.buttonStyle(.plain)
    }
}

struct TrackerAccountsView: View {
    let accounts: [AccountOverview]
    let busy: Bool
    let onConnect: () -> Void, onReconnect: (Account) -> Void, onDisconnect: (String) -> Void, onLogout: (Provider) -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Button("SNS 연결하기", systemImage: "plus", action: onConnect).buttonStyle(TrackerPrimaryButtonStyle()).disabled(busy)
                if accounts.isEmpty { EmptyCard("연결된 계정이 없어요", "첫 SNS를 연결하면 계정의 상태와 기록을 확인할 수 있어요.") }
                ForEach(accounts) { row in
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 12) {
                            ProviderMark(provider: row.account.provider)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(row.account.connectionTitle).font(.headline)
                                Text(row.account.identityLabel).font(.caption).foregroundStyle(TrackerStyle.muted).lineLimit(1)
                            }
                        }
                        Text(row.account.status.label + " · " + TrackerStyle.collectedAt(row.latest?.observedAt, compact: true)).font(.caption).foregroundStyle(TrackerStyle.muted)
                        ViewThatFits(in: .horizontal) {
                            HStack { reconnect(row); Spacer(minLength: 12); logout(row); disconnect(row) }
                            VStack(alignment: .leading, spacing: 10) { reconnect(row); logout(row); disconnect(row) }
                        }
                    }.providerPanel(row.account.provider).disabled(busy)
                }
                Text("로그아웃하면 목록·위젯에서 빠지고 기록은 보관돼요. 같은 계정으로 다시 로그인하면 복원돼요. 연결 해제는 선택한 연결의 기록도 삭제해요.").font(.caption).foregroundStyle(TrackerStyle.muted)
            }.padding(20).foregroundStyle(TrackerStyle.ink)
        }.background(TrackerStyle.background).navigationTitle("연결된 계정").navigationBarTitleDisplayMode(.inline)
    }
    private func reconnect(_ row: AccountOverview) -> some View { Button(row.account.accountType == .page ? "페이지 갱신" : "로그인 페이지") { onReconnect(row.account) }.font(.subheadline.weight(.semibold)).tint(TrackerStyle.color(row.account.provider)).frame(minHeight: 48) }
    private func disconnect(_ row: AccountOverview) -> some View { Button("연결 해제", role: .destructive) { onDisconnect(row.id) }.font(.subheadline).frame(minHeight: 48) }
    private func logout(_ row: AccountOverview) -> some View { Button("로그아웃") { onLogout(row.account.provider) }.font(.subheadline).frame(minHeight: 48) }
}

#Preview("설정") { NavigationStack { TrackerMoreView(accountCount: 0) } }
#Preview("연결된 계정 · 빈 상태") { NavigationStack { TrackerAccountsView(accounts: [], busy: false, onConnect: {}, onReconnect: { _ in }, onDisconnect: { _ in }, onLogout: { _ in }) } }
