import BackgroundTasks
import Foundation
import WidgetKit
import FollowerCore

enum BackgroundRefresh {
    private static let identifier = "dev.datell.followertracker.refresh"
    private static let listsIdentifier = "dev.datell.followertracker.relationships"
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in handle(task, lists: false) }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: listsIdentifier, using: nil) { task in handle(task, lists: true) }
    }
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date().addingTimeInterval(Double(TrackerSettings.interval) * 60)
        do { try BGTaskScheduler.shared.submit(request) } catch { /* OS can deny scheduling; stored observations remain visible. */ }
        let lists = BGProcessingTaskRequest(identifier: listsIdentifier)
        lists.requiresNetworkConnectivity = true
        lists.earliestBeginDate = Date(timeIntervalSince1970: Double(max(nowMillis(), TrackerSettings.lastLists + 86_400_000)) / 1_000)
        do { try BGTaskScheduler.shared.submit(lists) } catch { }
    }
    private static func handle(_ task: BGTask, lists: Bool) {
        let work = Task {
            var success = false
            do {
                let repository = try TrackerRepository(), sync = SyncService(repository: repository)
                let accounts = try await repository.accounts()
                var failures: Set<String> = []
                if lists {
                    for account in accounts where account.provider == .instagram {
                        try Task.checkCancellation()
                        try await sync.relationships(account.id, background: true)
                    }
                } else {
                    failures = try await sync.refreshAll(accounts.map(\.id), background: true, timeout: 12)
                }
                if lists { TrackerSettings.lastLists = nowMillis() }
                success = failures.isEmpty
                await WidgetUpdates.shared.request()
                await WidgetUpdates.shared.flush()
            } catch { }
            task.setTaskCompleted(success: success && !Task.isCancelled); schedule()
        }
        task.expirationHandler = { work.cancel() }
    }
}
