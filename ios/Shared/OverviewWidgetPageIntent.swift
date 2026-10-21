import AppIntents

/// A widget-only presentation action. WidgetKit reloads the timeline after completion.
struct OverviewWidgetPageIntent: AppIntent {
    static let title: LocalizedStringResource = "다른 계정 보기"
    static let openAppWhenRun = false
    static let isDiscoverable = false

    @Parameter(title: "페이지", default: 0) var page: Int
    init() {}
    init(page: Int) { self.page = page }

    func perform() async throws -> some IntentResult {
        try TrackerSettings.showOverviewPage(page)
        return .result()
    }
}
