import SwiftUI
import FollowerCore

private enum TrackerSheet: Identifiable {
    case picker, login(Provider), page(String?)
    var id: String { switch self { case .picker: "picker"; case .login(let provider): "login-" + provider.id; case .page(let key): "page-" + (key ?? "new") } }
}
private enum HomeDestination: Hashable { case account(String) }
private enum AccountManagementAction {
    case logout(Provider), disconnect(String)
    var title: String {
        switch self { case .logout(let provider): provider.title + "에서 로그아웃할까요?"; case .disconnect: "연결을 해제할까요?" }
    }
    var detail: String {
        switch self {
        case .logout: "이 기기의 SNS 로그인 정보를 지우고 해당 SNS의 수집을 멈춰요. 같은 SNS의 페이지에도 적용돼요. 팔로워·관계 기록과 다른 SNS 로그인은 유지해요."
        case .disconnect: "선택한 연결의 추적·관계 기록을 삭제해요. 같은 SNS의 다른 연결과 기록은 유지해요. 마지막 연결을 해제하면 해당 SNS 로그인도 지워요."
        }
    }
}

struct TrackerRootView: View {
    @Bindable var model: TrackerModel
    @SceneStorage("tracker.selectedTab") private var selectedTab = "home"
    @State private var homePath: [HomeDestination] = []
    @State private var morePath: [TrackerMorePage] = []
    @State private var sheet: TrackerSheet?
    @State private var accountAction: AccountManagementAction?
    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack(path: $homePath) {
                TrackerHomeView(accounts: model.accounts, loading: model.loading, storageError: model.storageError, busy: model.busy,
                    onConnect: { sheet = .picker }, onAccount: { homePath.append(.account($0)) },
                    onRefresh: { Task { await model.refresh() } })
                    .navigationTitle("계정").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("SNS 연결", systemImage: "plus") { sheet = .picker }.disabled(model.busy) } }
                    .navigationDestination(for: HomeDestination.self) { destination in
                        switch destination {
                        case .account(let key): AccountDetailScreen(model: model, key: key, onReconnect: reconnect, onLogout: { accountAction = .logout($0) })
                        }
                    }
            }.tabItem { Label("계정", systemImage: "person.crop.circle") }.tag("home")
            NavigationStack { RelationshipsView(model: model, onConnect: { sheet = .picker }).navigationTitle("분석").navigationBarTitleDisplayMode(.inline) }
                .tabItem { Label("분석", systemImage: "chart.xyaxis.line") }.tag("relationships")
            NavigationStack { TrackerWidgetGallery(accounts: model.accounts, storageError: model.storageError, busy: model.busy, onConnect: { sheet = .picker }).navigationTitle("위젯").navigationBarTitleDisplayMode(.inline) }
                .tabItem { Label("위젯", systemImage: "square.grid.2x2") }.tag("widgets")
            NavigationStack(path: $morePath) {
                TrackerMoreView(accountCount: model.accounts.count).navigationTitle("설정").navigationBarTitleDisplayMode(.inline)
                    .navigationDestination(for: TrackerMorePage.self) { page in
                        switch page {
                        case .accounts: TrackerAccountsView(accounts: model.accounts, busy: model.busy, onConnect: { sheet = .picker }, onReconnect: reconnect, onDisconnect: { accountAction = .disconnect($0) }, onLogout: { accountAction = .logout($0) })
                        case .settings: TrackerSettingsView()
                        case .support: TrackerSupportView()
                        case .help: TrackerHelpView()
                        case .privacy: TrackerPrivacyView()
                        }
                    }
            }.tabItem { Label("설정", systemImage: "gearshape") }.tag("more")
        }.foregroundStyle(TrackerStyle.ink)
        .onOpenURL { url in
            guard url.scheme == "followertracker", url.host == "dashboard" else { return }
            sheet = nil; homePath = []; selectedTab = "home"
        }
        .sheet(item: $sheet) { destination in
            switch destination {
            case .picker: ProviderPickerView(accounts: model.accounts, onSelect: { sheet = .login($0) }, onPage: { sheet = .page(nil) })
            case .login(let provider): SessionLoginView(provider: provider, model: model)
            case .page(let key): SessionLoginView(provider: .facebook, model: model, facebookPage: true,
                expectedPage: model.accounts.first { $0.id == key }?.account)
            }
        }
        .alert(accountAction?.title ?? "계정 관리", isPresented: Binding(get: { accountAction != nil }, set: { if !$0 { accountAction = nil } }), presenting: accountAction) { action in
            Button("취소", role: .cancel) { accountAction = nil }
            switch action {
            case .logout(let provider): Button("로그아웃", role: .destructive) { accountAction = nil; Task { await model.logout(provider) } }
            case .disconnect(let key): Button("연결 해제", role: .destructive) { accountAction = nil; Task { await model.disconnect(key) } }
            }
        } message: { Text($0.detail) }
        .alert("확인해주세요", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) { Button("확인") { model.message = nil } } message: { Text(model.message ?? "") }
    }
    private func reconnect(_ account: Account) { sheet = account.accountType == .page ? .page(account.id) : .login(account.provider) }
}

private struct ProviderPickerView: View {
    let accounts: [AccountOverview]
    let onSelect: (Provider) -> Void
    let onPage: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    TrackerInfoPanel(title: "내 계정을 연결해요", detail: "공식 SNS 페이지에서 로그인하면 본인 계정과 정확한 팔로워 수를 확인해 자동으로 연결해요.", icon: "lock.shield")
                    ForEach(Provider.allCases) { provider in
                        Button { onSelect(provider) } label: {
                            HStack(spacing: 12) {
                                ProviderMark(provider: provider)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(provider.title).font(.headline).foregroundStyle(TrackerStyle.ink)
                                    Text(accounts.contains { $0.account.provider == provider && $0.account.accountType == .profile } ? "연결됨 · 로그인 페이지 열기" : "내 계정 연결").font(.caption).foregroundStyle(TrackerStyle.muted)
                                }
                                Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(TrackerStyle.muted)
                            }.trackerPanel(padding: 18)
                        }.buttonStyle(.plain)
                    }
                    Button(action: onPage) {
                        HStack(spacing: 12) {
                            ProviderMark(provider: .facebook)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Facebook 페이지").font(.headline).foregroundStyle(TrackerStyle.ink)
                                Text("페이지로 이동한 뒤 연결 확인").font(.caption).foregroundStyle(TrackerStyle.muted)
                            }
                            Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(TrackerStyle.muted)
                        }.trackerPanel(padding: 18)
                    }.buttonStyle(.plain)
                    Text("SNS마다 읽을 수 있는 데이터가 달라요. 연결 후 각 계정의 수집 상태를 확인해주세요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                }.padding(20)
            }.background(TrackerStyle.background).navigationTitle("SNS 연결").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
        }.presentationDetents([.large])
    }
}
