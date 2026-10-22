import SwiftUI
import FollowerCore

// Two products, also used by the in-app gallery.
enum TrackerWidgetSize: Equatable { case small, large }

struct TrackerWidgetContent: View {
    let rows: [AccountOverview]
    var storageError = false
    var size: TrackerWidgetSize = .small
    var pageIndex = 0
    var onPage: ((Int) -> Void)? = nil
    var widgetControls = false
    var showAccountName = false
    private var single: Bool { size == .small }
    var body: some View {
        Group {
            if !storageError, single, let row = rows.first { accountTile(row) }
            else { overview }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            if single, !storageError, let row = rows.first { ProviderBrand(provider: row.account.provider).widgetBackground }
            else { LinearGradient(colors: [TrackerStyle.surface, TrackerStyle.background], startPoint: .topLeading, endPoint: .bottomTrailing) }
        }
    }
    private func accountTile(_ row: AccountOverview) -> some View {
        GeometryReader { geometry in
            let padding: CGFloat = geometry.size.height < 110 ? 12 : 16
            let mainHeight = max(1, geometry.size.height - padding * 2 - 24)
            let side = min(64, min(mainHeight, geometry.size.width * 0.23))
            VStack(spacing: 6) {
                HStack(spacing: 12) {
                    mark(row, side: side)
                    if row.account.status != .ready && row.account.status != .refreshing {
                        Image(systemName: "exclamationmark.circle.fill").font(.system(size: 14)).accessibilityHidden(true)
                    }
                    Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                        .font(.system(size: min(60, mainHeight / 1.4), weight: .bold, design: .rounded))
                        .monospacedDigit().minimumScaleFactor(0.35).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }.frame(maxHeight: .infinity)
                HStack(spacing: 8) {
                    if showAccountName { Text(row.account.identityLabel).lineLimit(1).truncationMode(.middle) }
                    Spacer(minLength: 0)
                    Text(row.latest.map { TrackerStyle.observationTime($0.observedAt, compact: true) } ?? "수집 전")
                        .lineLimit(1).minimumScaleFactor(0.7).layoutPriority(1)
                }.font(.system(size: 14)).opacity(0.9)
            }.foregroundStyle(.white).padding(padding)
        }.accessibilityElement(children: .ignore).accessibilityLabel(description(row))
    }
    private func description(_ row: AccountOverview) -> String {
        let identity = showAccountName ? " " + row.account.identityLabel : ""
        return row.account.connectionTitle + identity + ", " + row.account.provider.metricLabel + " " + (row.latest.map { TrackerStyle.count($0.followers) } ?? "기록 없음") + ", " + TrackerStyle.widgetStatus(row)
    }
    private var overview: some View {
        GeometryReader { geometry in
            overview(page: WidgetPageLayout(itemCount: rows.count, requestedPage: pageIndex, height: geometry.size.height, rowHeight: showAccountName ? 98 : 78))
        }
    }
    private func overview(page: WidgetPageLayout) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(single ? "내 계정" : "소셜 모아보기").font(.system(size: 20, weight: .bold))
                    if !rows.isEmpty {
                        let metric = rows.allSatisfy { $0.account.provider == .youtube } ? "구독자" :
                            rows.contains { $0.account.provider == .youtube } ? "팔로워·구독자" : "팔로워"
                        Text(metric).font(.system(size: 13)).foregroundStyle(TrackerStyle.muted)
                    }
                }
                Spacer(minLength: 4)
                if !rows.isEmpty { Text("\(rows.count)개 계정").font(.system(size: 13, weight: .medium)).padding(.horizontal, 9).padding(.vertical, 5).background(TrackerStyle.surface, in: Capsule()) }
            }
            if storageError || rows.isEmpty {
                Spacer(minLength: 0)
                Text(storageError ? "기록을 읽지 못했어요" : "SNS를 연결해보세요").font(.headline)
                Text(storageError ? "앱에서 확인해주세요" : "눌러서 앱 열기").font(.caption).foregroundStyle(TrackerStyle.muted)
            } else {
                // Connections include explicitly selected Pages with distinct keys and labels.
                ForEach(Array(rows[page.range])) { row in overviewRow(row) }
                if page.pageCount > 1 {
                    HStack {
                        pageButton("이전 계정", symbol: "chevron.left", target: page.index - 1, enabled: page.hasPrevious)
                        Spacer(minLength: 4)
                        Text("\(page.index + 1) / \(page.pageCount)").font(.system(size: 11, weight: .medium))
                            .accessibilityLabel("\(page.pageCount)쪽 중 \(page.index + 1)쪽")
                        Spacer(minLength: 4)
                        pageButton("다음 계정", symbol: "chevron.right", target: page.index + 1, enabled: page.hasNext)
                    }
                }
            }
            Spacer(minLength: 0)
        }.foregroundStyle(TrackerStyle.ink).padding(14)
    }
    @ViewBuilder private func pageButton(_ title: String, symbol: String, target: Int, enabled: Bool) -> some View {
        if widgetControls {
            Button(intent: OverviewWidgetPageIntent(page: target)) { Image(systemName: symbol).frame(width: 44, height: 44) }
                .buttonStyle(.plain).disabled(!enabled).accessibilityLabel(title)
        } else {
            Button { onPage?(target) } label: { Image(systemName: symbol).frame(width: 44, height: 44) }
                .buttonStyle(.plain).disabled(!enabled || onPage == nil).accessibilityLabel(title)
        }
    }
    private func overviewRow(_ row: AccountOverview) -> some View {
        let brand = ProviderBrand(provider: row.account.provider)
        return VStack(spacing: 2) {
            HStack(spacing: 12) {
                mark(row, side: 40)
                Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                    .font(.system(size: 32, weight: .bold, design: .rounded)).monospacedDigit()
                    .minimumScaleFactor(0.4).lineLimit(1).frame(maxWidth: .infinity, alignment: .trailing)
            }
            if showAccountName {
                Text(row.account.identityLabel).font(.system(size: 14)).foregroundStyle(TrackerStyle.muted)
                    .lineLimit(1).frame(maxWidth: .infinity, alignment: .trailing)
            }
            Text(TrackerStyle.widgetStatus(row)).font(.system(size: 12)).foregroundStyle(TrackerStyle.muted)
                .lineLimit(1).minimumScaleFactor(0.65).frame(maxWidth: .infinity, alignment: .trailing)
        }.padding(.horizontal, 12).padding(.vertical, 6).frame(height: showAccountName ? 90 : 70)
            .background(brand.background, in: RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .ignore).accessibilityLabel(description(row))
    }
    private func mark(_ row: AccountOverview, side: CGFloat) -> some View {
        let brand = ProviderBrand(provider: row.account.provider)
        return Image(brand.assetName).resizable().renderingMode(.original).scaledToFit()
            .frame(width: row.account.provider == .reddit ? side : side * 0.8, height: row.account.provider == .reddit ? side : side * 0.8)
            .frame(width: side, height: side).background(brand.iconBackground).clipShape(RoundedRectangle(cornerRadius: side * 0.29)).accessibilityHidden(true)
    }
}

#Preview("내 계정 · 미연결") { TrackerWidgetContent(rows: []).frame(width: 340, height: 160) }
#Preview("모아보기 · 저장 오류") { TrackerWidgetContent(rows: [], storageError: true, size: .large).frame(width: 340, height: 360) }
