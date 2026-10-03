import Foundation
import WidgetKit

/** Coalesces bursts within one process; background callers flush before their task ends. */
actor WidgetUpdates {
    static let shared = WidgetUpdates()
    private var pending: Task<Void, Never>?
    func request() {
        guard pending == nil else { return }
        pending = Task {
            try? await Task.sleep(for: .seconds(2))
            WidgetCenter.shared.reloadTimelines(ofKind: "FollowerTrackerWidget")
            pending = nil
        }
    }
    func flush() async { await pending?.value }
}
