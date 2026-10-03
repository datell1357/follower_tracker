import SwiftUI
import FollowerCore

private enum TrackerSheet: Identifiable {
    case picker, login(Provider)
    var id: String { switch self { case .picker: "picker"; case .login(let provider): "login-" + provider.id } }
}
private enum HomeDestination: Hashable { case account(String) }

struct TrackerRootView: View {
    @Bindable var model: TrackerModel
    @SceneStorage("tracker.selectedTab") private var selectedTab = "home"
    @State private var homePath: [HomeDestination] = []
    @State private var morePath: [TrackerMorePage] = []
    @State private var sheet: TrackerSheet?
    @State private var disconnectKey: String?
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
                        case .account(let key): AccountDetailScreen(model: model, key: key, onReconnect: { sheet = .login($0) })
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
                        case .accounts: TrackerAccountsView(accounts: model.accounts, busy: model.busy, onConnect: { sheet = .picker }, onReconnect: { sheet = .login($0) }, onDisconnect: { disconnectKey = $0 })
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
            case .picker: ProviderPickerView(accounts: model.accounts, onSelect: { sheet = .login($0) })
            case .login(let provider): SessionLoginView(provider: provider, model: model)
            }
        }
        .alert("연결을 해제할까요?", isPresented: Binding(get: { disconnectKey != nil }, set: { if !$0 { disconnectKey = nil } })) {
            Button("유지하기", role: .cancel) { disconnectKey = nil }
            Button("연결 해제", role: .destructive) { if let key = disconnectKey { disconnectKey = nil; Task { await model.disconnect(key) } } }
        } message: { Text("이 SNS의 로그인 세션과 기기에 저장한 추적·관계 기록을 삭제해요. 다시 연결하면 새 기록부터 시작해요.") }
        .alert("확인해주세요", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) { Button("확인") { model.message = nil } } message: { Text(model.message ?? "") }
    }
}

private struct ProviderPickerView: View {
    let accounts: [AccountOverview]
    let onSelect: (Provider) -> Void
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
                                    Text(accounts.contains { $0.account.provider == provider } ? "연결됨 · 로그인 페이지 열기" : "내 계정 연결").font(.caption).foregroundStyle(TrackerStyle.muted)
                                }
                                Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(TrackerStyle.muted)
                            }.trackerPanel(padding: 18)
                        }.buttonStyle(.plain)
                    }
                    Text("SNS마다 읽을 수 있는 데이터가 달라요. 연결 후 각 계정의 수집 상태를 확인해주세요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                }.padding(20)
            }.background(TrackerStyle.background).navigationTitle("SNS 연결").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
        }.presentationDetents([.large])
    }
}
