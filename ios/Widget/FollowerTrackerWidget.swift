import AppIntents
import SwiftUI
import WidgetKit
import FollowerCore

enum AccountChoice: String, AppEnum {
    case all, instagram, tiktok, x, facebook, reddit
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "표시할 계정"
    static var caseDisplayRepresentations: [AccountChoice: DisplayRepresentation] = [.all: "첫 연결 계정", .instagram: "Instagram", .tiktok: "TikTok", .x: "X", .facebook: "Facebook", .reddit: "Reddit"]
    var provider: Provider? { switch self { case .all: nil; case .instagram: .instagram; case .tiktok: .tiktok; case .x: .x; case .facebook: .facebook; case .reddit: .reddit } }
}
struct TrackerAccountEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "연결된 계정"
    static var defaultQuery = TrackerAccountQuery()
    let id: String
    let title: String
    let identity: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: "\(identity)") }
    init(_ account: Account) { id = account.id; title = account.connectionTitle; identity = account.identityLabel }
}
struct TrackerAccountQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [TrackerAccountEntity] {
        let repository = try TrackerRepository()
        return try await repository.accounts().filter { identifiers.contains($0.id) }.map(TrackerAccountEntity.init)
    }
    func suggestedEntities() async throws -> [TrackerAccountEntity] {
        let repository = try TrackerRepository()
        return try await repository.accounts().map(TrackerAccountEntity.init)
    }
}
struct TrackerConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "팔로워 트래커"
    static var description = IntentDescription("위젯에서 확인할 계정을 선택해요.")
    @Parameter(title: "표시할 계정", default: .all) var account: AccountChoice
    @Parameter(title: "연결된 계정") var connection: TrackerAccountEntity?
    // Retain the stored provider choice for older widgets while new configurations select an exact connection.
    static var parameterSummary: some ParameterSummary { Summary("\(\.$connection)의 팔로워") }
}
struct TrackerEntry: TimelineEntry {
    let date: Date
    let rows: [AccountOverview]
    var storageError = false
    var overviewPage = 0
}
struct TrackerTimeline: AppIntentTimelineProvider {
    var allAccounts = false
    private func records(_ repository: TrackerRepository, _ configuration: TrackerConfiguration) async throws -> [AccountOverview] {
        if allAccounts { return try await repository.widgetOverviews() }
        if let selected = configuration.connection { return try await repository.widgetOverviews(key: selected.id) }
        let rows = try await repository.widgetOverviews(provider: configuration.account.provider)
        return Array(rows.prefix(1))
    }
    func placeholder(in context: Context) -> TrackerEntry { TrackerEntry(date: Date(), rows: []) }
    func snapshot(for configuration: TrackerConfiguration, in context: Context) async -> TrackerEntry { await entry(configuration) }
    func timeline(for configuration: TrackerConfiguration, in context: Context) async -> Timeline<TrackerEntry> {
        do {
            let repository = try TrackerRepository(), sync = SyncService(repository: repository)
            let rows = try await records(repository, configuration)
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
            let rows = try await records(repository, configuration)
            return TrackerEntry(date: Date(), rows: rows, overviewPage: allAccounts ? TrackerSettings.overviewPage : 0)
        } catch { return TrackerEntry(date: Date(), rows: [], storageError: true) }
    }
}
struct TrackerWidgetView: View {
    let entry: TrackerEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        TrackerWidgetContent(rows: entry.rows, storageError: entry.storageError,
            size: family == .systemSmall ? .small : .large, pageIndex: entry.overviewPage,
            widgetControls: family == .systemLarge)
            .containerBackground(.clear, for: .widget)
            .widgetURL(URL(string: "followertracker://dashboard"))
    }
}
struct FollowerTrackerWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "FollowerTrackerWidget", intent: TrackerConfiguration.self, provider: TrackerTimeline()) { TrackerWidgetView(entry: $0) }
            .configurationDisplayName("내 계정").description("SNS 하나를 가장 작은 위젯으로 확인해요.")
            .supportedFamilies([.systemSmall]).contentMarginsDisabled()
    }
}

struct OverviewConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "소셜 모아보기"
    static var description = IntentDescription("연결된 모든 SNS를 함께 표시해요.")
}
struct OverviewTimeline: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TrackerEntry { TrackerEntry(date: Date(), rows: []) }
    func snapshot(for configuration: OverviewConfiguration, in context: Context) async -> TrackerEntry {
        let choice = TrackerConfiguration()
        choice.account = .all
        return await TrackerTimeline(allAccounts: true).snapshot(for: choice, in: context)
    }
    func timeline(for configuration: OverviewConfiguration, in context: Context) async -> Timeline<TrackerEntry> {
        let choice = TrackerConfiguration()
        choice.account = .all
        return await TrackerTimeline(allAccounts: true).timeline(for: choice, in: context)
    }
}
struct OverviewTrackerWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "OverviewTrackerWidget", intent: OverviewConfiguration.self, provider: OverviewTimeline()) { TrackerWidgetView(entry: $0) }
            .configurationDisplayName("소셜 모아보기").description("모든 계정의 팔로워·증감·수집 시각을 한눈에 확인해요.")
            .supportedFamilies([.systemLarge]).contentMarginsDisabled()
    }
}
@main
struct FollowerTrackerWidgets: WidgetBundle {
    var body: some Widget {
        FollowerTrackerWidget()
        OverviewTrackerWidget()
    }
}
