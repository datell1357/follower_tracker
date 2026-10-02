import SwiftUI

enum TrackerWidgetSize: Equatable { case small, medium, large }

/// Shared by the real extension and the app's preview, using the same saved records.
struct TrackerWidgetContent: View {
    let rows: [AccountOverview]
    var storageError = false
    var size: TrackerWidgetSize = .small
    private var single: Bool { size == .small || rows.count == 1 }
    var body: some View {
        VStack(alignment: .leading, spacing: single ? 6 : (size == .medium ? 4 : 8)) {
            if !single || storageError || rows.isEmpty { Text("팔로워 트래커").font(.caption.weight(.bold)).foregroundStyle(TrackerStyle.blue) }
            if storageError {
                Text("기록을 읽지 못했어요").font(.headline)
                Text("앱에서 확인해주세요").font(.caption).foregroundStyle(TrackerStyle.muted)
            } else if rows.isEmpty {
                Text("SNS를 연결해보세요").font(.headline)
                Text("눌러서 앱 열기").font(.caption).foregroundStyle(TrackerStyle.muted)
                Spacer(minLength: 0)
            } else if single {
                let row = rows[0]
                Text(row.account.provider.title + " · @" + row.account.username).font(.caption2).foregroundStyle(TrackerStyle.muted).lineLimit(1)
                Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                    .font(.system(size: 32, weight: .bold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
                Text("팔로워 · " + TrackerStyle.change(row)).font(.caption2).foregroundStyle(TrackerStyle.changeColor(row.change))
                if let comparedAt = row.comparisonAt {
                    Text("비교 " + TrackerStyle.observationTime(comparedAt, compact: true)).font(.system(size: 10)).foregroundStyle(TrackerStyle.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
                Text(TrackerStyle.widgetStatus(row)).font(.system(size: 10)).foregroundStyle(TrackerStyle.muted).lineLimit(2)
            } else {
                ForEach(Array(rows.prefix(size == .large ? 5 : 3))) { row in
                    VStack(spacing: 0) {
                        HStack {
                            Text(row.account.provider.title).font(.system(size: 12, weight: .semibold))
                            Spacer(minLength: 6)
                            Text(row.latest.map { TrackerStyle.count($0.followers) } ?? "—")
                                .font(.system(size: size == .medium ? 18 : 21, weight: .bold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.5).lineLimit(1)
                        }
                        HStack {
                            Text(TrackerStyle.widgetStatus(row)).font(.system(size: 10)).foregroundStyle(TrackerStyle.muted).lineLimit(1)
                            Spacer(minLength: 6)
                            Text(TrackerStyle.change(row) + (row.comparisonAt.map { " · " + TrackerStyle.observationTime($0, compact: true) + " 대비" } ?? ""))
                                .font(.system(size: 10)).foregroundStyle(TrackerStyle.changeColor(row.change)).lineLimit(1)
                        }
                    }
                }
                if size == .large { Spacer(minLength: 0) }
            }
        }.foregroundStyle(TrackerStyle.ink).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

#Preview("미연결 위젯") { TrackerWidgetContent(rows: []).padding(16).frame(width: 170, height: 170).background(TrackerStyle.surface) }
#Preview("저장 오류 위젯") { TrackerWidgetContent(rows: [], storageError: true, size: .medium).padding(16).frame(width: 340, height: 170).background(TrackerStyle.surface) }
