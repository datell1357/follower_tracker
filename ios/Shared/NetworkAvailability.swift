import Foundation
import Network

/** Only a known unavailable path suppresses automatic collection; unknown is allowed. */
final class NetworkAvailability: @unchecked Sendable {
    static let shared = NetworkAvailability()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var status: NWPath.Status?
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock(); self.status = path.status; self.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "dev.datell.followertracker.network"))
    }
    var isDefinitelyOffline: Bool {
        lock.lock(); defer { lock.unlock() }
        return status == .unsatisfied
    }
    deinit { monitor.cancel() }
}
