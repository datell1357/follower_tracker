import SwiftUI
import FollowerCore

private enum TrackerSheet: Identifiable {
    case picker, login(Provider), detail(String)
    var id: String { switch self { case .picker: "picker"; case .login(let provider): "login-" + provider.id; case .detail(let key): "detail-" + key } }
}
struct TrackerRootView: View {
    @Bindable var model: TrackerModel
    @State private var selectedTab = 0
    @State private var sheet: TrackerSheet?
    @State private var disconnectKey: String?
    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                dashboard.navigationTitle("팔로워 트래커")
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("SNS 연결", systemImage: "plus") { sheet = .picker }.disabled(model.busy) } }
            }.tabItem { Label("추적", systemImage: "chart.xyaxis.line") }.tag(0)
            NavigationStack { RelationshipsView(model: model).navigationTitle("나의 연결") }
                .tabItem { Label("관계", systemImage: "person.2") }.tag(1)
            NavigationStack {
                TrackerSettingsView(model: model, onReconnect: { sheet = .login($0) }, onDisconnect: { disconnectKey = $0 }).navigationTitle("내 기기에서 관리")
            }.tabItem { Label("설정", systemImage: "slider.horizontal.3") }.tag(2)
        }
        .onOpenURL { url in
            guard url.scheme == "followertracker", url.host == "dashboard" else { return }
            sheet = nil
            selectedTab = 0
        }
        .sheet(item: $sheet) { destination in
            switch destination {
            case .picker:
                NavigationStack {
                    List {
                        Section { Text("공식 페이지에서 직접 로그인해주세요. 로그인 세션은 이 기기에 보관돼요.").font(.subheadline).foregroundStyle(.secondary) }
                        ForEach(Provider.allCases) { provider in
                            Button { sheet = .login(provider) } label: {
                                HStack(spacing: 12) { ProviderMark(provider: provider); VStack(alignment: .leading) { Text(provider.title).font(.headline); Text(model.accounts.contains { $0.account.provider == provider } ? "연결됨 · 로그인 확인" : "내 계정 연결").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                            }.foregroundStyle(.primary)
                        }
                    }.navigationTitle("SNS 연결").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { sheet = nil } } }
                }.presentationDetents([.medium, .large])
            case .login(let provider): SessionLoginView(provider: provider, model: model) { sheet = nil }
            case .detail(let key):
                if let row = model.accounts.first(where: { $0.id == key }) {
                    NavigationStack { AccountDetailView(row: row, busy: model.busy, onRefresh: { Task { await model.refresh(key) } }, onReconnect: { sheet = .login(row.account.provider) })
                        .navigationTitle(row.account.provider.title).navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { sheet = nil } } } }.presentationDetents([.large])
                }
            }
        }
        .alert("연결을 해제할까요?", isPresented: Binding(get: { disconnectKey != nil }, set: { if !$0 { disconnectKey = nil } })) {
            Button("유지하기", role: .cancel) { disconnectKey = nil }
            Button("연결 해제", role: .destructive) { if let key = disconnectKey { disconnectKey = nil; Task { await model.disconnect(key) } } }
        } message: { Text("이 SNS의 로그인 세션과 기기에 저장한 추적·관계 기록을 삭제해요. 다시 연결하면 새 기록부터 시작해요.") }
        .alert("확인해주세요", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) { Button("확인") { model.message = nil } } message: { Text(model.message ?? "") }
    }
    private var dashboard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("작은 변화도 한눈에").foregroundStyle(.secondary)
                if model.loading { ProgressView().frame(maxWidth: .infinity).padding(40) }
                else if model.storageError { EmptyCard("기록을 읽지 못했어요", "저장된 데이터를 보존했어요. 앱을 다시 열어 확인해주세요.") }
                else if model.accounts.isEmpty {
                    EmptyCard("내 계정부터 연결해보세요", "팔로워 수와 변화를 기록하고 홈 화면 위젯에서 확인할 수 있어요.") {
                        Button("SNS 연결하기", systemImage: "plus") { sheet = .picker }.buttonStyle(.borderedProminent)
                    }
                } else {
                    HStack { Text("연결된 계정 \(model.accounts.count)").font(.subheadline).foregroundStyle(.secondary); Spacer(); Button("갱신", systemImage: "arrow.clockwise") { Task { await model.refresh() } }.disabled(model.busy) }
                    ForEach(model.accounts) { row in Button { sheet = .detail(row.id) } label: { AccountCard(row: row) }.buttonStyle(.plain) }
                }
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "square.grid.2x2").foregroundStyle(TrackerStyle.blue)
                    VStack(alignment: .leading, spacing: 6) { Text("홈 화면에서 바로 확인").font(.headline); Text("홈 화면을 길게 누른 뒤 위젯 추가에서 팔로워 트래커를 선택해주세요. 마지막으로 읽은 수와 시각을 표시해요.").font(.caption).foregroundStyle(.secondary) }
                }.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(TrackerStyle.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 20))
            }.padding(24)
        }.background(TrackerStyle.background).overlay(alignment: .top) { if model.busy { ProgressView().padding(8).background(.regularMaterial, in: Capsule()) } }
    }
}

struct AccountCard: View {
    let row: AccountOverview
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                ProviderMark(provider: row.account.provider)
                VStack(alignment: .leading, spacing: 3) { Text(row.account.provider.title).font(.headline); Text("@" + row.account.username).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) { Text("팔로워").font(.caption).foregroundStyle(.secondary); Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—").font(.system(size: 36, weight: .semibold, design: .rounded)).monospacedDigit() }
                Spacer(); Text(TrackerStyle.change(row)).font(.subheadline.weight(.semibold)).foregroundStyle(TrackerStyle.blue).padding(.horizontal, 10).padding(.vertical, 7).background(TrackerStyle.blue.opacity(0.09), in: RoundedRectangle(cornerRadius: 10))
            }
            if row.history.count > 1 { GrowthChart(history: row.history).frame(height: 64) }
            HStack { Text(row.account.status == .ready ? TrackerStyle.time(row.latest?.observedAt) : row.account.status.label); Spacer(); Text(row.comparisonAt.map { TrackerStyle.observationTime($0) + " 대비" } ?? (row.previous == nil ? "변화 기록을 시작해요" : "정확한 두 기록이 필요해요")) }.font(.caption2).foregroundStyle(.secondary)
        }.padding(22).background(.background, in: RoundedRectangle(cornerRadius: 24)).accessibilityElement(children: .combine)
    }
}

struct AccountDetailView: View {
    let row: AccountOverview
    let busy: Bool
    let onRefresh: () -> Void, onReconnect: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) { ProviderMark(provider: row.account.provider); VStack(alignment: .leading) { Text(row.account.displayName).font(.title2.bold()); Text("@" + row.account.username).foregroundStyle(.secondary) } }
                VStack(alignment: .leading, spacing: 6) {
                    Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—").font(.system(size: 48, weight: .semibold, design: .rounded)).monospacedDigit()
                    Text("팔로워 · " + TrackerStyle.time(row.latest?.observedAt)).foregroundStyle(.secondary)
                    if let following = row.latest?.following { Text("팔로잉 " + TrackerStyle.count(following)).font(.subheadline) }
                }
                GrowthChart(history: row.history).frame(height: 150)
                HStack { Button("지금 갱신", action: onRefresh).buttonStyle(.borderedProminent).disabled(busy || row.account.status == .foregroundOnly); Button("로그인 페이지", action: onReconnect).buttonStyle(.bordered).disabled(busy) }
                Text(row.account.status.label).font(.subheadline).foregroundStyle(.secondary)
                Text("자동 갱신").font(.headline)
                Text(backgroundDetail).font(.subheadline).foregroundStyle(.secondary)
                Text("최근 기록").font(.headline)
                ForEach(Array(row.history.suffix(14).reversed()), id: \.observedAt) { metric in HStack { Text(TrackerStyle.time(metric.observedAt)).foregroundStyle(.secondary); Spacer(); Text(TrackerStyle.count(metric.followers)).monospacedDigit() }.font(.subheadline) }
            }.padding(24)
        }
    }
    private var backgroundDetail: String {
        switch row.account.capabilities.background {
        case .observed: "기기에서 백그라운드 수집이 실행됐어요. 실제 갱신 시각은 운영체제와 연결 상태에 따라 달라져요."
        case .foregroundOnly: "현재 이 SNS는 로그인 페이지에서 프로필을 열어 갱신해요."
        case .unavailable: "이 계정의 자동 수집 경로를 사용할 수 없어요."
        case .unverified: "위젯과 예약 수집에서 갱신을 시도해요. 백그라운드 수집 성공은 아직 확인되지 않았어요."
        }
    }
}
