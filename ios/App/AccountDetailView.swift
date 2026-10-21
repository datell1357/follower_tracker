import SwiftUI
import FollowerCore

enum TrackerTrendRange: String, CaseIterable {
    case recent = "최근 기록", hour = "최근 1시간", today = "오늘"
    func records(_ history: [MetricSnapshot], now: Int64) -> [MetricSnapshot] {
        let earliest: Int64
        switch self {
        case .recent: earliest = 0
        case .hour: earliest = now - 3_600_000
        case .today: earliest = Int64(Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1_000)).timeIntervalSince1970 * 1_000)
        }
        let rows = history.filter { $0.observedAt >= earliest && $0.observedAt <= now }.sorted { $0.observedAt < $1.observedAt }
        return self == .recent ? Array(rows.suffix(14)) : rows
    }
}

struct AccountDetailScreen: View {
    let model: TrackerModel
    let key: String
    let onReconnect: (Account) -> Void
    let onLogout: (Provider) -> Void
    var body: some View {
        Group {
            if let row = model.accounts.first(where: { $0.id == key }) {
                AccountDetailView(row: row, busy: model.busy, onRefresh: { Task { await model.refresh(key) } }, onReconnect: { onReconnect(row.account) }, onLogout: { onLogout(row.account.provider) })
            } else {
                EmptyCard("계정을 찾을 수 없어요", "연결 상태를 홈에서 다시 확인해주세요.").padding(20)
            }
        }.background(TrackerStyle.background).navigationTitle("계정 기록").navigationBarTitleDisplayMode(.inline)
    }
}

struct AccountDetailView: View {
    let row: AccountOverview
    let busy: Bool
    let onRefresh: () -> Void, onReconnect: () -> Void
    var onLogout: (() -> Void)? = nil
    @State private var range: TrackerTrendRange = .recent
    @ScaledMetric(relativeTo: .largeTitle) private var countSize = 48.0
    private var records: [MetricSnapshot] { range.records(row.history, now: nowMillis()) }
    private var comparison: MetricComparison? { MetricComparison.between(previous: records.first, current: records.last) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    ProviderMark(provider: row.account.provider)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.account.displayName).font(.title3.bold())
                        Text(row.account.connectionTitle + " · " + row.account.identityLabel).font(.caption).foregroundStyle(TrackerStyle.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if let onLogout {
                        Button("로그아웃", action: onLogout).font(.subheadline).fixedSize(horizontal: true, vertical: false).frame(minHeight: 48).disabled(busy)
                    }
                }.padding(.vertical, 8)
                VStack(alignment: .leading, spacing: 10) {
                    Text("팔로워").font(.caption).foregroundStyle(TrackerStyle.muted)
                    Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                        .font(.system(size: countSize, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
                    if let following = row.latest?.following { Text("팔로잉 " + TrackerStyle.count(following)).font(.subheadline).foregroundStyle(TrackerStyle.muted) }
                    Text(TrackerStyle.collectedAt(row.latest?.observedAt)).font(.caption).foregroundStyle(TrackerStyle.muted)
                    TrackerBadge(text: row.account.status.label)
                    if row.account.status == .rateLimited, let until = row.account.nextAllowedAt {
                        Text(TrackerStyle.observationTime(until) + " 이후 다시 시도해요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                    }
                }.trackerPanel()
                Picker("기록 기간", selection: $range) { ForEach(TrackerTrendRange.allCases, id: \.self) { Text($0.rawValue).tag($0) } }.pickerStyle(.menu)
                    .font(.subheadline).frame(minHeight: 48).accessibilityIdentifier("trend.range")
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text(range.rawValue).font(.headline); Spacer()
                        Text(comparison.map { TrackerStyle.change($0.change) } ?? "비교 대기").font(.headline).foregroundStyle(TrackerStyle.changeColor(comparison?.change))
                    }
                    if let comparison {
                        Text(TrackerStyle.observationTime(comparison.previousAt) + " → " + TrackerStyle.observationTime(comparison.currentAt))
                            .font(.caption).foregroundStyle(TrackerStyle.muted)
                        GrowthChart(history: records).frame(height: 130)
                    } else {
                        Text("이 기간의 정확한 기록이 두 개 이상 필요해요. 기록이 쌓이면 변화를 표시해요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
                    }
                }.trackerPanel()
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { refreshButton; reconnectButton }
                    VStack(alignment: .leading, spacing: 10) { refreshButton; reconnectButton }
                }
                TrackerInfoPanel(title: "자동 갱신", detail: backgroundDetail, icon: "clock")
                TrackerSectionTitle(title: "선택한 기간의 기록")
                if records.isEmpty { EmptyCard("아직 기록이 없어요", "다른 기간을 선택하거나 갱신을 진행해주세요.") }
                else {
                    VStack(spacing: 14) {
                        ForEach(Array(records.reversed()), id: \.observedAt) { metric in
                            ViewThatFits(in: .horizontal) {
                                HStack { recordTime(metric); Spacer(); recordCount(metric) }
                                VStack(alignment: .leading, spacing: 4) { recordTime(metric); recordCount(metric) }
                            }
                        }
                    }.trackerPanel()
                }
            }.foregroundStyle(TrackerStyle.ink).padding(20)
        }.background(TrackerStyle.background)
    }
    private func recordTime(_ metric: MetricSnapshot) -> some View { Text(TrackerStyle.observationTime(metric.observedAt)).font(.caption).foregroundStyle(TrackerStyle.muted) }
    private func recordCount(_ metric: MetricSnapshot) -> some View { Text(TrackerStyle.count(metric.followers)).font(.subheadline.weight(.semibold)).monospacedDigit() }
    private var refreshButton: some View { Button(busy ? "갱신 중" : "지금 갱신", action: onRefresh).buttonStyle(TrackerPrimaryButtonStyle()).disabled(busy || row.account.status == .foregroundOnly) }
    private var reconnectButton: some View { Button(row.account.accountType == .page ? "페이지 열고 갱신" : "로그인 페이지", action: onReconnect).font(.subheadline.weight(.semibold)).frame(minHeight: 48).disabled(busy) }
    private var backgroundDetail: String {
        switch row.account.capabilities.background {
        case .observed: "기기에서 백그라운드 수집이 실행됐어요. 실제 갱신 시각은 운영체제와 연결 상태에 따라 달라져요."
        case .foregroundOnly: row.account.accountType == .page ? "페이지를 열고 연결 확인을 눌러 갱신해요. 위젯은 마지막 정상 수집값을 보여줘요." : "현재 이 계정은 로그인 페이지에서 내 프로필을 열어 갱신해요."
        case .unavailable: "이 계정의 자동 수집 경로를 사용할 수 없어요. 마지막으로 읽은 기록을 표시해요."
        case .unverified: "예약 수집과 위젯에서 갱신을 시도해요. 이 계정의 백그라운드 성공은 아직 확인되지 않았어요."
        }
    }
}
