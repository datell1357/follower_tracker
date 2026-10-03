import SwiftUI
import FollowerCore

struct TrackerHomeView: View {
    let accounts: [AccountOverview]
    let loading: Bool, storageError: Bool, busy: Bool
    let onConnect: () -> Void, onAccount: (String) -> Void, onRefresh: () -> Void
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                if loading {
                    ProgressView("기록을 읽고 있어요").frame(maxWidth: .infinity).padding(40)
                } else if storageError {
                    EmptyCard("기록을 읽지 못했어요", "저장된 데이터를 보존했어요. 앱을 다시 열어 확인해주세요.")
                } else if accounts.isEmpty {
                    EmptyCard("첫 계정을 연결해보세요", "연결한 SNS의 팔로워 수와 변화를 한곳에서 확인하세요.") {
                        Button("SNS 연결하기", systemImage: "plus", action: onConnect).buttonStyle(TrackerPrimaryButtonStyle()).disabled(busy)
                    }
                } else {
                    ViewThatFits(in: .horizontal) {
                        HStack {
                            TrackerSectionTitle(title: "연결된 계정 \(accounts.count)개"); Spacer()
                            refreshButton
                        }
                        VStack(alignment: .leading, spacing: 10) {
                            TrackerSectionTitle(title: "연결된 계정 \(accounts.count)개"); refreshButton
                        }
                    }
                    ForEach(accounts) { row in
                        Button { onAccount(row.id) } label: { AccountCard(row: row) }.buttonStyle(.plain)
                            .accessibilityHint("계정의 변화 기록과 수집 상태를 열어요.")
                    }
                }
            }.padding(20)
        }.background(TrackerStyle.background)
    }
    private var refreshButton: some View {
        Button(busy ? "갱신 중" : "지금 갱신", systemImage: "arrow.clockwise", action: onRefresh)
            .font(.subheadline.weight(.semibold)).frame(minHeight: 48).disabled(busy)
    }
}

struct AccountCard: View {
    let row: AccountOverview
    @ScaledMetric(relativeTo: .largeTitle) private var countSize = 42.0
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                ProviderMark(provider: row.account.provider)
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.account.provider.title).font(.headline)
                    Text("@" + row.account.username).font(.caption).foregroundStyle(TrackerStyle.muted).lineLimit(1)
                }
                Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(TrackerStyle.muted).accessibilityHidden(true)
            }
            Text("팔로워").font(.caption).foregroundStyle(TrackerStyle.muted)
            Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                .font(.system(size: countSize, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            VStack(alignment: .leading, spacing: 6) {
                Text(TrackerStyle.change(row)).font(.subheadline.weight(.semibold)).foregroundStyle(TrackerStyle.changeColor(row.change))
                Text(row.comparisonAt.map { TrackerStyle.observationTime($0, compact: true) + " 대비" } ?? (row.previous == nil ? "변화 기록을 시작해요" : "정확한 두 기록이 필요해요"))
                    .font(.caption).foregroundStyle(TrackerStyle.muted)
            }
            if row.history.count > 1 { GrowthChart(history: row.history).frame(height: 56).accessibilityHidden(true) }
            Divider().overlay(TrackerStyle.outline)
            VStack(alignment: .leading, spacing: 4) {
                Text(row.account.status.label).font(.caption.weight(.semibold)).foregroundStyle(TrackerStyle.muted)
                Text(TrackerStyle.collectedAt(row.latest?.observedAt)).font(.caption).foregroundStyle(TrackerStyle.muted)
                if row.account.status == .rateLimited, let until = row.account.nextAllowedAt {
                    Text(TrackerStyle.observationTime(until) + " 이후 다시 시도").font(.caption).foregroundStyle(TrackerStyle.muted)
                }
            }
        }.foregroundStyle(TrackerStyle.ink).trackerPanel(padding: 22).accessibilityElement(children: .combine)
    }
}

#Preview("계정 · 연결 전") {
    TrackerHomeView(accounts: [], loading: false, storageError: false, busy: false, onConnect: {}, onAccount: { _ in }, onRefresh: {})
}
#Preview("계정 · 저장 오류") {
    TrackerHomeView(accounts: [], loading: false, storageError: true, busy: false, onConnect: {}, onAccount: { _ in }, onRefresh: {}).preferredColorScheme(.dark)
}
