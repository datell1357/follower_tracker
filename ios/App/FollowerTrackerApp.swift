import SwiftUI

@main
struct FollowerTrackerApp: App {
    @State private var model = TrackerModel()
    @Environment(\.scenePhase) private var scenePhase
    init() { BackgroundRefresh.register() }
    var body: some Scene {
        WindowGroup {
            TrackerRootView(model: model).tint(TrackerStyle.blue)
                .task { await model.reload(); BackgroundRefresh.schedule() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await model.reload() } }
                    if phase == .background { BackgroundRefresh.schedule() }
                }
        }
    }
}
