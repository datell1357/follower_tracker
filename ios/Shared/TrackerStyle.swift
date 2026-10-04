import SwiftUI
import FollowerCore

enum TrackerStyle {
    static let blue = adaptive(0x3958D9, 0xADBCFF)
    static let green = adaptive(0x08775D, 0x74D5B7)
    static let ink = adaptive(0x172039, 0xEEF1FA)
    static let muted = adaptive(0x5F6A80, 0xBBC3D7)
    static let background = adaptive(0xF5F6FA, 0x101522)
    static let surface = adaptive(0xFFFFFF, 0x1B2232)
    static let outline = adaptive(0xE5E9F1, 0x333D52)
    static let blueSurface = adaptive(0xE9EDFF, 0x253365)
    static let onBlueSurface = adaptive(0x243D99, 0xE2E7FF)
    static let onBlue = adaptive(0xFFFFFF, 0x192A75)
    static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: Double((value >> 16) & 255) / 255,
                           green: Double((value >> 8) & 255) / 255,
                           blue: Double(value & 255) / 255, alpha: 1)
        })
    }
    static func count(_ value: Int64) -> String { value.formatted(.number.grouping(.automatic)) }
    static func change(_ value: Int64?) -> String {
        guard let value else { return "첫 기록" }; return (value > 0 ? "+" : "") + count(value)
    }
    static func change(_ row: AccountOverview) -> String {
        row.previous != nil && row.comparison == nil ? "비교 불가" : change(row.change)
    }
    static func changeColor(_ change: Int64?) -> Color { (change ?? 0) > 0 ? green : blue }
    static func collectedAt(_ value: Int64?, compact: Bool = false) -> String {
        value.map { "수집 " + observationTime($0, compact: compact) } ?? "아직 기록 없음"
    }
    static func widgetStatus(_ row: AccountOverview) -> String {
        (row.account.status == .ready ? "" : row.account.status.label + " · ") + collectedAt(row.latest?.observedAt, compact: true)
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
        ProviderBrand(provider: provider).accent
    }
}
struct ProviderMark: View {
    let provider: Provider
    var body: some View {
        let brand = ProviderBrand(provider: provider)
        Image(brand.assetName).resizable().renderingMode(.original).scaledToFit()
            .frame(width: provider == .reddit ? 44 : 28, height: provider == .reddit ? 44 : 28)
            .frame(width: 44, height: 44)
            .background(brand.iconBackground)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .accessibilityHidden(true)
    }
}
struct EmptyCard<Content: View>: View {
    let title: String, detail: String
    @ViewBuilder var action: Content
    init(_ title: String, _ detail: String, @ViewBuilder action: () -> Content) { self.title = title; self.detail = detail; self.action = action() }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { Text(title).font(.title3.bold()); Text(detail).font(.subheadline).foregroundStyle(TrackerStyle.muted); action }
            .foregroundStyle(TrackerStyle.ink).frame(maxWidth: .infinity, alignment: .leading).padding(22).background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
    }
}
extension EmptyCard where Content == EmptyView {
    init(_ title: String, _ detail: String) { self.init(title, detail) { EmptyView() } }
}
struct GrowthChart: View {
    let history: [MetricSnapshot]
    var color: Color = TrackerStyle.blue
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
                context.fill(fill, with: .linearGradient(Gradient(colors: [color.opacity(0.16), .clear]), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
            context.fill(Path(ellipseIn: CGRect(x: points.last!.x - 3, y: points.last!.y - 3, width: 6, height: 6)), with: .color(color))
        }.accessibilityLabel("팔로워 변화 그래프, \(history.count)개 기록")
    }
}
