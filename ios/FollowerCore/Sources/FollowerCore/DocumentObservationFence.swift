import Foundation

/// A document's callbacks can cross actors; its identity never contains URLs or user data.
public final class DocumentObservationFence: @unchecked Sendable {
    private let lock = NSLock()
    private var token: UUID? = UUID()
    public init() {}
    public func activate() { lock.lock(); defer { lock.unlock() }; token = UUID() }
    public func advance() { lock.lock(); defer { lock.unlock() }; if token != nil { token = UUID() } }
    public func invalidate() { lock.lock(); defer { lock.unlock() }; token = nil }
    public func capture() -> @Sendable () -> Bool {
        lock.lock(); let expected = token; lock.unlock()
        return { [weak self] in
            guard let self, let expected else { return false }
            self.lock.lock(); defer { self.lock.unlock() }
            return self.token == expected
        }
    }
}
