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
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                mark(row, side: 20)
                Text(row.account.identityLabel).font(.system(size: 10)).opacity(0.9).lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                .font(.system(size: 34, weight: .bold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.35).lineLimit(1)
            HStack(spacing: 8) {
                Text("팔로워").opacity(0.9)
                Text(TrackerStyle.change(row)).fontWeight(.semibold)
            }.font(.system(size: 11))
            if let following = row.latest?.following {
                Text("팔로잉 " + TrackerStyle.count(following)).font(.system(size: 10)).opacity(0.9).lineLimit(1).minimumScaleFactor(0.65)
            } else if let previous = row.previous {
                Text("이전 " + TrackerStyle.count(previous.followers)).font(.system(size: 10)).opacity(0.9).lineLimit(1).minimumScaleFactor(0.65)
            }
            if let compared = row.comparisonAt {
                Text(TrackerStyle.observationTime(compared, compact: true) + " 대비").font(.system(size: 8)).opacity(0.9).lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(TrackerStyle.widgetStatus(row)).font(.system(size: 9)).opacity(0.9).lineLimit(2)
        }.foregroundStyle(.white).padding(12)
        .accessibilityElement(children: .combine)
    }
    private var overview: some View {
        GeometryReader { geometry in
            overview(page: WidgetPageLayout(itemCount: rows.count, requestedPage: pageIndex, height: geometry.size.height))
        }
    }
    private func overview(page: WidgetPageLayout) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(single ? "내 계정" : "소셜 모아보기").font(.system(size: 17, weight: .bold))
                    if !rows.isEmpty { Text("팔로워 · 이전 수집 대비").font(.system(size: 9)).foregroundStyle(TrackerStyle.muted) }
                }
                Spacer(minLength: 4)
                if !rows.isEmpty { Text("\(rows.count)개 계정").font(.system(size: 10, weight: .medium)).padding(.horizontal, 9).padding(.vertical, 5).background(TrackerStyle.surface, in: Capsule()) }
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
            HStack(spacing: 8) {
                mark(row, side: 25)
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.account.connectionTitle).font(.system(size: 11, weight: .bold)).foregroundStyle(brand.accent)
                    Text(row.account.identityLabel).font(.system(size: 9)).foregroundStyle(TrackerStyle.muted)
                }.lineLimit(1)
                Spacer(minLength: 5)
                VStack(alignment: .trailing, spacing: 0) {
                    Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—").font(.system(size: 20, weight: .bold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.4).lineLimit(1)
                    Text(TrackerStyle.change(row)).font(.system(size: 9, weight: .medium)).foregroundStyle(TrackerStyle.changeColor(row.change)).lineLimit(1)
                }
            }
            HStack(spacing: 4) {
                Text(TrackerStyle.widgetStatus(row))
                Spacer(minLength: 0)
                if let compared = row.comparisonAt { Text(TrackerStyle.observationTime(compared, compact: true) + " 대비") }
            }.font(.system(size: 8)).foregroundStyle(TrackerStyle.muted).lineLimit(1).minimumScaleFactor(0.7)
        }.padding(.horizontal, 9).padding(.vertical, 4).background(brand.background, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityElement(children: .combine)
    }
    private func mark(_ row: AccountOverview, side: CGFloat) -> some View {
        let brand = ProviderBrand(provider: row.account.provider)
        return Image(brand.assetName).resizable().renderingMode(.original).scaledToFit()
            .frame(width: row.account.provider == .reddit ? side : side * 0.66, height: row.account.provider == .reddit ? side : side * 0.66)
            .frame(width: side, height: side).background(brand.iconBackground).clipShape(RoundedRectangle(cornerRadius: side * 0.29)).accessibilityHidden(true)
    }
}

#Preview("내 계정 · 미연결") { TrackerWidgetContent(rows: []).frame(width: 170, height: 170) }
#Preview("모아보기 · 저장 오류") { TrackerWidgetContent(rows: [], storageError: true, size: .large).frame(width: 340, height: 360) }
