import SwiftUI
import FollowerCore

enum TrackerStyle {
    static let blue = Color(red: 0.216, green: 0.369, blue: 0.847)
    static let background = Color(uiColor: .systemGroupedBackground)
    static func count(_ value: Int64) -> String { value.formatted(.number.grouping(.automatic)) }
    static func change(_ value: Int64?) -> String {
        guard let value else { return "첫 기록" }; return (value > 0 ? "+" : "") + count(value)
    }
    static func change(_ row: AccountOverview) -> String {
        row.previous != nil && row.comparison == nil ? "비교 불가" : change(row.change)
    }
    static func observationTime(_ value: Int64, compact: Bool = false) -> String {
        let date = Date(timeIntervalSince1970: Double(value) / 1_000)
        let calendar = Calendar(identifier: .gregorian)
        let formatter = DateFormatter()
        formatter.calendar = calendar; formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = compact ? (calendar.component(.year, from: date) == calendar.component(.year, from: Date()) ? "M/d HH:mm" : "yy/M/d HH:mm") : "yyyy.M.d HH:mm"
        return formatter.string(from: date)
    }
    static func time(_ value: Int64?) -> String {
        guard let value else { return "아직 기록 없음" }
        let date = Date(timeIntervalSince1970: Double(value) / 1_000)
        let minutes = max(0, Int(Date().timeIntervalSince(date) / 60))
        if minutes < 1 { return "방금 갱신" }
        if minutes < 60 { return "\(minutes)분 전 갱신" }
        if minutes < 1440 { return "\(minutes / 60)시간 전 갱신" }
        return date.formatted(.dateTime.month().day().hour().minute())
    }
    static func color(_ provider: Provider) -> Color {
        switch provider {
        case .instagram: Color(red: 0.67, green: 0.27, blue: 0.48)
        case .tiktok: Color(red: 0.13, green: 0.42, blue: 0.44)
        case .x: Color(red: 0.15, green: 0.21, blue: 0.30)
        case .facebook: blue
        case .reddit: Color(red: 0.85, green: 0.40, blue: 0.20)
        }
    }
}
struct ProviderMark: View {
    let provider: Provider
    var body: some View {
        Text([Provider.instagram: "IG", .tiktok: "Tk", .x: "X", .facebook: "f", .reddit: "r"][provider]!)
            .font(.headline).foregroundStyle(TrackerStyle.color(provider)).frame(width: 44, height: 44)
            .background(TrackerStyle.color(provider).opacity(0.11), in: RoundedRectangle(cornerRadius: 14))
    }
}
struct EmptyCard<Content: View>: View {
    let title: String, detail: String
    @ViewBuilder var action: Content
    init(_ title: String, _ detail: String, @ViewBuilder action: () -> Content) { self.title = title; self.detail = detail; self.action = action() }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { Text(title).font(.title3.bold()); Text(detail).font(.subheadline).foregroundStyle(.secondary); action }
            .frame(maxWidth: .infinity, alignment: .leading).padding(24).background(.background, in: RoundedRectangle(cornerRadius: 24))
    }
}
extension EmptyCard where Content == EmptyView {
    init(_ title: String, _ detail: String) { self.init(title, detail) { EmptyView() } }
}
struct GrowthChart: View {
    let history: [MetricSnapshot]
    var body: some View {
        Canvas { context, size in
            guard let first = history.first, let last = history.last else { return }
            let minimum = Double(history.map(\.followers).min()!), maximum = Double(history.map(\.followers).max()!)
            let span = max(1, maximum - minimum), duration = Double(max(1, last.observedAt - first.observedAt))
            let points = history.map { item in CGPoint(x: 5 + Double(item.observedAt - first.observedAt) / duration * (size.width - 10),
                y: size.height - 5 - (Double(item.followers) - minimum) / span * (size.height - 10)) }
            var path = Path(); path.move(to: points[0]); points.dropFirst().forEach { path.addLine(to: $0) }
            if points.count > 1 {
                var fill = path; fill.addLine(to: CGPoint(x: points.last!.x, y: size.height)); fill.addLine(to: CGPoint(x: points[0].x, y: size.height)); fill.closeSubpath()
                context.fill(fill, with: .linearGradient(Gradient(colors: [TrackerStyle.blue.opacity(0.16), .clear]), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                context.stroke(path, with: .color(TrackerStyle.blue), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
            context.fill(Path(ellipseIn: CGRect(x: points.last!.x - 3, y: points.last!.y - 3, width: 6, height: 6)), with: .color(TrackerStyle.blue))
        }.accessibilityLabel("팔로워 변화 그래프, \(history.count)개 기록")
    }
}
