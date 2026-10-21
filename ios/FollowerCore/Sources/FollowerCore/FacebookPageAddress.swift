import Foundation

public enum FacebookPageAddress {
    public static func input(_ text: String) -> URL? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized: String
        if value.lowercased().hasPrefix("http://") { normalized = "https://" + value.dropFirst(7) }
        else if value.lowercased().hasPrefix("https://") { normalized = "https://" + value.dropFirst(8) }
        else if ["facebook.com/", "www.facebook.com/", "m.facebook.com/"].contains(where: { value.lowercased().hasPrefix($0) }) { normalized = "https://" + value }
        else { return nil }
        guard let url = URL(string: normalized), Provider.facebook.allows(url) else { return nil }
        return url
    }
    public static func isTarget(_ url: URL) -> Bool {
        guard Provider.facebook.allows(url), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        if parts.path == "/profile.php" {
            let ids = (parts.queryItems ?? []).filter { $0.name == "id" }
            return ids.count == 1 && ids[0].value?.range(of: "^[0-9]+$", options: .regularExpression) != nil
        }
        let path = parts.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path.range(of: "^(pages|people)/[^/]+/[0-9]+$|^p/[^/]+-[0-9]+$", options: .regularExpression) != nil { return true }
        let reserved: Set<String> = ["login", "checkpoint", "challenge", "two_factor", "two-factor", "twofactor", "two_step_verification", "auth_platform",
            "reel", "reels", "watch", "groups", "events", "marketplace", "search", "pages", "settings", "notifications", "friends", "bookmarks",
            "gaming", "help", "privacy", "policies", "photos", "stories"]
        return path.range(of: "^[A-Za-z0-9.]{1,255}$", options: .regularExpression) != nil && !path.lowercased().hasSuffix(".php") && !reserved.contains(path.lowercased())
    }
}
