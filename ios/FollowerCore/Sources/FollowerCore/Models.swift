import Foundation

public enum Provider: String, Codable, CaseIterable, Sendable, Identifiable {
    case instagram = "INSTAGRAM", tiktok = "TIKTOK", x = "X", facebook = "FACEBOOK", reddit = "REDDIT"
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .instagram: "Instagram"
        case .tiktok: "TikTok"
        case .x: "X"
        case .facebook: "Facebook"
        case .reddit: "Reddit"
        }
    }
    public var domain: String {
        switch self {
        case .instagram: "instagram.com"
        case .tiktok: "tiktok.com"
        case .x: "x.com"
        case .facebook: "facebook.com"
        case .reddit: "reddit.com"
        }
    }
    public var loginURL: URL {
        let path: String
        switch self {
        case .instagram: path = "https://www.instagram.com/accounts/login/"
        case .tiktok: path = "https://www.tiktok.com/login"
        case .x: path = "https://x.com/i/flow/login"
        case .facebook: path = "https://www.facebook.com/login/"
        case .reddit: path = "https://www.reddit.com/login/"
        }
        return URL(string: path)!
    }
    public func allows(_ url: URL) -> Bool {
        guard url.scheme == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443, let host = url.host?.lowercased() else { return false }
        return host == domain || host.hasSuffix(".\(domain)")
    }
    public func allowsLogin(_ url: URL) -> Bool {
        if allows(url) { return true }
        if self == .instagram { return Provider.facebook.allows(url) }
        guard self == .x, url.scheme == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443, let host = url.host?.lowercased() else { return false }
        return host == "twitter.com" || host.hasSuffix(".twitter.com")
    }
}

public enum SyncStatus: String, Codable, Sendable {
    case ready = "READY", refreshing = "REFRESHING", reauthRequired = "REAUTH_REQUIRED"
    case checkRequired = "CHECK_REQUIRED", rateLimited = "RATE_LIMITED", offline = "OFFLINE"
    case formatChanged = "FORMAT_CHANGED", foregroundOnly = "FOREGROUND_ONLY", listIncomplete = "LIST_INCOMPLETE"
    public var label: String {
        switch self {
        case .ready: "갱신 완료"
        case .refreshing: "갱신 중"
        case .reauthRequired: "다시 로그인 필요"
        case .checkRequired: "연결 확인 필요"
        case .rateLimited: "갱신 대기"
        case .offline: "일시 오류"
        case .formatChanged: "수집 재시도 대기"
        case .foregroundOnly: "앱에서 갱신"
        case .listIncomplete: "명단 갱신 미완료"
        }
    }
    public var blocksAutomaticRetry: Bool { [.reauthRequired, .checkRequired].contains(self) }
}

public enum Capability: String, Codable, Sendable {
    case unverified = "UNVERIFIED", observed = "OBSERVED", foregroundOnly = "FOREGROUND_ONLY", unavailable = "UNAVAILABLE"
}
public struct Capabilities: Codable, Equatable, Sendable {
    public var count: Capability = .unverified
    public var followers: Capability = .unverified
    public var following: Capability = .unverified
    public var background: Capability = .unverified
    public init() {}
}

public struct TransientRetryState: Codable, Equatable, Sendable {
    public let failureCount: Int
    public let nextAttemptAt: Int64
    public init(failureCount: Int, nextAttemptAt: Int64) {
        self.failureCount = failureCount; self.nextAttemptAt = nextAttemptAt
    }
}

public struct Account: Codable, Identifiable, Equatable, Sendable {
    public let provider: Provider
    public let stableID: String
    public var username: String
    public var displayName: String
    public let profileURL: URL
    public var status: SyncStatus = .ready
    public var capabilities = Capabilities()
    public let connectedAt: Int64
    public var lastAttemptAt: Int64?
    public var nextAllowedAt: Int64?
    public var transientRetry: TransientRetryState?
    public var relationshipStatus: SyncStatus?
    public var id: String { "\(provider.rawValue):\(stableID)" }
    public init(provider: Provider, stableID: String, username: String, displayName: String,
                profileURL: URL, connectedAt: Int64) throws {
        guard !stableID.isEmpty, !username.isEmpty, provider.allows(profileURL) else {
            throw CollectionFailure(.checkRequired)
        }
        self.provider = provider; self.stableID = stableID; self.username = username
        self.displayName = displayName; self.profileURL = profileURL; self.connectedAt = connectedAt
    }
}

public enum Precision: String, Codable, Sendable { case exact = "EXACT", rounded = "ROUNDED", estimated = "ESTIMATED" }
public struct MetricSnapshot: Codable, Equatable, Sendable {
    public let accountKey: String
    public let observedAt: Int64
    public let followers: Int64
    public let following: Int64?
    public let precision: Precision
    public let source: String
    public let adapterVersion: Int
    public init(accountKey: String, observedAt: Int64, followers: Int64, following: Int64?,
                precision: Precision = .exact, source: String, adapterVersion: Int = 1) throws {
        guard followers >= 0, following == nil || following! >= 0 else { throw CollectionFailure(.formatChanged) }
        self.accountKey = accountKey; self.observedAt = observedAt; self.followers = followers
        self.following = following; self.precision = precision; self.source = source; self.adapterVersion = adapterVersion
    }
}

public enum Direction: String, Codable, Sendable { case followers = "FOLLOWERS", following = "FOLLOWING" }
public struct Member: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let username: String
    public let displayName: String
    public init(id: String, username: String, displayName: String? = nil) throws {
        guard !id.isEmpty, !username.isEmpty else { throw CollectionFailure(.formatChanged) }
        self.id = id; self.username = username; self.displayName = displayName ?? username
    }
}
public struct RelationshipSnapshot: Codable, Sendable {
    public let accountKey: String
    public let direction: Direction
    public let startedAt: Int64
    public let finishedAt: Int64
    public let members: [Member]
    public let complete: Bool
    public let consistent: Bool
    public let endReason: String
    public init(accountKey: String, direction: Direction, startedAt: Int64, finishedAt: Int64,
                members: [Member], complete: Bool, consistent: Bool, endReason: String) {
        self.accountKey = accountKey; self.direction = direction; self.startedAt = startedAt
        self.finishedAt = finishedAt; self.members = members; self.complete = complete
        self.consistent = consistent; self.endReason = endReason
    }
}
public struct RelationshipPage: Sendable {
    public let ownerKey: String
    public let members: [Member]
    public let nextCursor: String?
    public let hasMore: Bool
    public init(ownerKey: String, members: [Member], nextCursor: String?, hasMore: Bool) {
        self.ownerKey = ownerKey; self.members = members; self.nextCursor = nextCursor; self.hasMore = hasMore
    }
}
public struct CollectionFailure: Error, Sendable {
    public let status: SyncStatus
    public let retryAfterSeconds: Int64?
    public init(_ status: SyncStatus, retryAfterSeconds: Int64? = nil) {
        self.status = status; self.retryAfterSeconds = retryAfterSeconds
    }
}
public func statusForHTTP(_ code: Int, loginResponse: Bool = false) -> SyncStatus? {
    if loginResponse || code == 401 { return .reauthRequired }
    if code == 403 { return .checkRequired }
    if code == 429 { return .rateLimited }
    if code >= 500 { return .offline }
    return (200..<300).contains(code) ? nil : .checkRequired
}
public func exactDisplayedCount(_ text: String) -> Int64? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.range(of: "^[0-9][0-9,\\s\u{00A0}\u{202F}]*$", options: .regularExpression) != nil else { return nil }
    return Int64(trimmed.filter { $0.isASCII && $0.isNumber })
}
public func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }
