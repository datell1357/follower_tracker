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
