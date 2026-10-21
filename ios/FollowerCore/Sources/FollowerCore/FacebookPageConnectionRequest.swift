import Foundation

public enum FacebookPageAction: Equatable, Sendable { case wait, openTarget, capture }

/// One explicit link submission, including its return from an interactive login.
public struct FacebookPageConnectionRequest: Sendable {
    public let target: URL
    public private(set) var returnAfterLogin: Bool
    public var returnedAfterLogin = false
    public var captureAttempted = false
    public var requiresConfirmation: Bool { Self.isShareLink(target) }

    public init?(input: String, authenticated: Bool) {
        guard input.utf8.count <= 16_384, let target = FacebookPageAddress.input(input),
              FacebookPageAddress.isTarget(target) || Self.isShareLink(target),
              canAutoConnect(.facebook, url: target, authenticated: true, loading: false, busy: false) else { return nil }
        self.target = target; returnAfterLogin = !authenticated
    }

    public mutating func observe(_ current: URL) {
        if !captureAttempted, Provider.facebook.allows(current),
           !canAutoConnect(.facebook, url: current, authenticated: true, loading: false, busy: false) {
            returnAfterLogin = true
        }
    }

    public func next(current: URL, authenticated: Bool, loading: Bool, busy: Bool) -> FacebookPageAction {
        guard !captureAttempted, canAutoConnect(.facebook, url: current, authenticated: authenticated, loading: loading, busy: busy) else { return .wait }
        if returnAfterLogin && !returnedAfterLogin { return .openTarget }
        if Self.sameTarget(target, current) { return .capture }
        // Opaque share tokens do not identify a Page. Confirm the resolved destination explicitly.
        if requiresConfirmation, FacebookPageAddress.isTarget(current) { return .wait }
        return .wait
    }

    private static func isShareLink(_ url: URL) -> Bool {
        Provider.facebook.allows(url) && url.path.range(of: "^/share/[A-Za-z0-9]{1,512}/?$", options: .regularExpression) != nil
    }
    private static func sameTarget(_ requested: URL, _ current: URL) -> Bool {
        guard FacebookPageAddress.isTarget(requested), FacebookPageAddress.isTarget(current),
              requested.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() == current.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() else { return false }
        guard requested.path == "/profile.php" else { return true }
        func id(_ url: URL) -> String? { URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value }
        return id(requested) == id(current)
    }
}
