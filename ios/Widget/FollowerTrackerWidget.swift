import AppIntents
import SwiftUI
import WidgetKit
import FollowerCore

enum AccountChoice: String, AppEnum {
    case all, instagram, tiktok, x, facebook, reddit
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "표시할 계정"
    static var caseDisplayRepresentations: [AccountChoice: DisplayRepresentation] = [.all: "모든 계정", .instagram: "Instagram", .tiktok: "TikTok", .x: "X", .facebook: "Facebook", .reddit: "Reddit"]
    var provider: Provider? { switch self { case .all: nil; case .instagram: .instagram; case .tiktok: .tiktok; case .x: .x; case .facebook: .facebook; case .reddit: .reddit } }
}
struct TrackerConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "팔로워 트래커"
    static var description = IntentDescription("위젯에서 확인할 계정을 선택해요.")
    @Parameter(title: "표시할 계정", default: .all) var account: AccountChoice
}
struct TrackerEntry: TimelineEntry {
    let date: Date
    let rows: [AccountOverview]
    var storageError = false
}
struct TrackerTimeline: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TrackerEntry { TrackerEntry(date: Date(), rows: []) }
    func snapshot(for configuration: TrackerConfiguration, in context: Context) async -> TrackerEntry { await entry(configuration) }
    func timeline(for configuration: TrackerConfiguration, in context: Context) async -> Timeline<TrackerEntry> {
        do {
            let repository = try TrackerRepository(), sync = SyncService(repository: repository)
            let rows = try await repository.widgetOverviews(provider: configuration.account.provider)
            // A bounded native attempt. WidgetKit decides when this code is scheduled.
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    for row in rows where nowMillis() - (row.latest?.observedAt ?? 0) >= Int64(TrackerSettings.interval) * 60_000 {
                        if Task.isCancelled { return }
                        try? await sync.refresh(row.id, background: true, timeout: 9)
                    }
                }
                group.addTask { try? await Task.sleep(for: .seconds(18)) }
                await group.next(); group.cancelAll()
            }
        } catch { }
        return Timeline(entries: [await entry(configuration)], policy: .after(Date().addingTimeInterval(Double(TrackerSettings.interval) * 60)))
    }
    private func entry(_ configuration: TrackerConfiguration) async -> TrackerEntry {
        do {
            let repository = try TrackerRepository()
            let rows = try await repository.widgetOverviews(provider: configuration.account.provider)
            return TrackerEntry(date: Date(), rows: rows)
        } catch { return TrackerEntry(date: Date(), rows: [], storageError: true) }
    }
}
struct TrackerWidgetView: View {
    let entry: TrackerEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        TrackerWidgetContent(rows: entry.rows, storageError: entry.storageError,
            size: family == .systemSmall ? .small : family == .systemLarge ? .large : .medium)
            .containerBackground(TrackerStyle.surface, for: .widget)
            .widgetURL(URL(string: "followertracker://dashboard"))
    }
}
@main
struct FollowerTrackerWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "FollowerTrackerWidget", intent: TrackerConfiguration.self, provider: TrackerTimeline()) { TrackerWidgetView(entry: $0) }
            .configurationDisplayName("팔로워 트래커").description("팔로워 수와 변화를 홈 화면에서 확인해요.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
