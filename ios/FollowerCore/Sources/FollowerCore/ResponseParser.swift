import CoreFoundation
import Foundation

public enum ResponseParser {
    public static func objectBody(_ body: String) throws -> [String: Any] {
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let login = body.range(of: "login_required|accounts/login|i/flow/login", options: [.regularExpression, .caseInsensitive]) != nil
            throw CollectionFailure(login ? .reauthRequired : .formatChanged)
        }
        return root
    }
    public static func webCaptureFailure(_ root: [String: Any]) -> CollectionFailure? {
        guard let value = root["error"], !(value is NSNull) else { return nil }
        guard let error = value as? String else { return CollectionFailure(.formatChanged) }
        let status: SyncStatus
        switch error {
        case "http":
            guard let code = root["status"] as? NSNumber, CFGetTypeID(code) != CFBooleanGetTypeID(),
                  code.doubleValue == Double(code.intValue) else { return CollectionFailure(.formatChanged) }
            status = statusForHTTP(code.intValue) ?? .formatChanged
        case "rate_limited": status = .rateLimited
        case "offline": status = .offline
        case "reauth_required", "identity_missing": status = .reauthRequired
        case "own_profile_required", "check_required": status = .checkRequired
        default: status = .formatChanged
        }
        let retry = (root["retryAfterSeconds"] as? NSNumber).flatMap { value -> Int64? in
            guard CFGetTypeID(value) != CFBooleanGetTypeID(), let seconds = Int64(value.stringValue) else { return nil }
            return min(86_400, max(60, seconds))
        }
        return CollectionFailure(status, retryAfterSeconds: retry)
    }

    public static func capturedProfile(_ provider: Provider, payload: String, session: SavedSession,
                                       expected: Account?, now: Int64) throws -> (Account, MetricSnapshot) {
        let root = try objectBody(payload)
        if let failure = webCaptureFailure(root) { throw failure }
        guard session.authenticated(provider) else { throw CollectionFailure(.reauthRequired) }
        guard root["provider"] as? String == provider.rawValue else { throw CollectionFailure(.checkRequired) }
        guard root["precision"] as? String == "EXACT", let id = root["stableId"] as? String,
              let name = root["username"] as? String, let profile = root["profileURL"] as? String,
              let url = URL(string: profile), let source = root["source"] as? String else { throw CollectionFailure(.formatChanged) }
        guard expected == nil || expected?.provider == provider && expected?.stableID == id,
              session.expectedID == nil || session.expectedID == id else { throw CollectionFailure(.checkRequired) }
        if [.instagram, .x, .facebook].contains(provider), session.identity(provider) != id { throw CollectionFailure(.checkRequired) }
        let sources: Set<String>
        switch provider {
        case .instagram: sources = ["instagram-webview-profile", "instagram-webview-dom", "instagram-webview-session"]
        case .reddit: sources = ["reddit-webview-session"]
        case .tiktok: sources = ["tiktok-webview-profile"]
        case .x: sources = ["x-webview-profile", "x-webview-dom"]
        case .facebook: sources = ["facebook-webview-profile"]
        }
        guard sources.contains(source) else { throw CollectionFailure(.formatChanged) }
        func exact(_ value: Any?) throws -> Int64 {
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  let count = exactDisplayedCount(number.stringValue), count <= 9_007_199_254_740_991 else { throw CollectionFailure(.formatChanged) }
            return count
        }
        let followers = try exact(root["followers"])
        let following = try root["following"].flatMap { $0 is NSNull ? nil : try exact($0) }
        var account = try Account(provider: provider, stableID: id, username: name,
            displayName: root["displayName"] as? String ?? name, profileURL: url, connectedAt: expected?.connectedAt ?? now)
        let sessionResponse = provider == .instagram && source == "instagram-webview-session" ||
            provider == .reddit && source == "reddit-webview-session"
        account.capabilities = expected?.capabilities ?? Capabilities()
        account.capabilities.count = sessionResponse ? .observed : .foregroundOnly
        account.capabilities.background = sessionResponse ? (account.capabilities.background == .foregroundOnly ? .unverified : account.capabilities.background) : .foregroundOnly
        account.status = sessionResponse ? .ready : .foregroundOnly
        account.lastAttemptAt = now
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: now, followers: followers,
            following: following, source: source))
    }
    public static func instagramProfile(_ body: String, expectedID: String?, now: Int64) throws -> (Account, MetricSnapshot) {
        let root = try objectBody(body)
        try checkServiceStatus(root)
        guard let user = root["user"] as? [String: Any], let id = text(user, "pk") ?? text(user, "id"),
              let name = text(user, "username"), let url = URL(string: "https://www.instagram.com/\(name)/") else {
            throw CollectionFailure(.formatChanged)
        }
        if let expectedID, id != expectedID { throw CollectionFailure(.checkRequired) }
        let account = try Account(provider: .instagram, stableID: id, username: name,
            displayName: text(user, "full_name") ?? name, profileURL: url, connectedAt: now)
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: now, followers: count(user, "follower_count"),
            following: count(user, "following_count"), source: "instagram-web-session"))
    }
    public static func instagramPage(_ body: String, ownerKey: String) throws -> RelationshipPage {
        let root = try objectBody(body)
        try checkServiceStatus(root)
        guard let users = root["users"] as? [[String: Any]] else { throw CollectionFailure(.formatChanged) }
        let members = try users.map { user in
            guard let id = text(user, "pk") ?? text(user, "id"), let name = text(user, "username") else {
                throw CollectionFailure(.formatChanged)
            }
            return try Member(id: id, username: name, displayName: text(user, "full_name") ?? name)
        }
        let next = text(root, "next_max_id")
        let more = root["more_available"] as? Bool ?? (next != nil)
        return RelationshipPage(ownerKey: ownerKey, members: members, nextCursor: next, hasMore: more)
    }
    public static func redditProfile(_ body: String, expectedID: String?, now: Int64) throws -> (Account, MetricSnapshot) {
        let root = try objectBody(body), user = root["data"] as? [String: Any] ?? root
        guard let id = text(user, "id") else { throw CollectionFailure(.reauthRequired) }
        if let expectedID, id != expectedID { throw CollectionFailure(.checkRequired) }
        guard let name = text(user, "name"), let profile = user["subreddit"] as? [String: Any],
              let url = URL(string: "https://www.reddit.com/user/\(name)/") else { throw CollectionFailure(.formatChanged) }
        let account = try Account(provider: .reddit, stableID: id, username: name,
            displayName: text(profile, "title") ?? name, profileURL: url, connectedAt: now)
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: now,
            followers: count(profile, "subscribers"), following: nil, source: "reddit-profile-session"))
    }
    public static func tiktokProfile(_ html: String, expectedID: String, now: Int64) throws -> (Account, MetricSnapshot) {
        let regex = try NSRegularExpression(pattern: "<script[^>]*>([\\s\\S]*?)</script>", options: .caseInsensitive)
        for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let range = Range(match.range(at: 1), in: html),
                  let root = try? objectBody(String(html[range])), let scope = root["__DEFAULT_SCOPE__"] as? [String: Any],
                  let context = scope["webapp.app-context"] as? [String: Any],
                  let signedIn = context["user"] as? [String: Any] ?? (context["userInfo"] as? [String: Any])?["user"] as? [String: Any],
                  let owner = text(signedIn, "id") ?? text(signedIn, "uid"),
                  let detail = scope["webapp.user-detail"] as? [String: Any], let info = detail["userInfo"] as? [String: Any],
                  let user = info["user"] as? [String: Any], let stats = info["stats"] as? [String: Any],
                  let id = text(user, "id"), let name = text(user, "uniqueId"), let url = URL(string: "https://www.tiktok.com/@\(name)") else { continue }
            guard id == expectedID, owner == expectedID else { throw CollectionFailure(.checkRequired) }
            let account = try Account(provider: .tiktok, stableID: id, username: name,
                displayName: text(user, "nickname") ?? name, profileURL: url, connectedAt: now)
            return (account, try MetricSnapshot(accountKey: account.id, observedAt: now,
                followers: count(stats, "followerCount"), following: count(stats, "followingCount"), source: "tiktok-profile-session"))
        }
        throw CollectionFailure(.formatChanged)
    }
    private static func checkServiceStatus(_ root: [String: Any]) throws {
        if text(root, "status") == "fail" {
            let message = text(root, "message")?.lowercased() ?? ""
            let status: SyncStatus = message.contains("login") ? .reauthRequired :
                (message.contains("challenge") || root["challenge"] != nil) ? .checkRequired :
                (message.contains("wait") || message.contains("rate")) ? .rateLimited : .formatChanged
            throw CollectionFailure(status)
        }
    }
    private static func text(_ object: [String: Any], _ name: String) -> String? {
        if let text = object[name] as? String { return text.isEmpty ? nil : text }
        if let value = object[name] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() { return value.stringValue }
        return nil
    }
    private static func count(_ object: [String: Any], _ name: String) throws -> Int64 {
        guard let text = text(object, name), let value = Int64(text), value >= 0 else { throw CollectionFailure(.formatChanged) }
        return value
    }
}
