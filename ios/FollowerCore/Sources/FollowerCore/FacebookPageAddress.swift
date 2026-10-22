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

    public static func sameTarget(_ requested: URL, _ current: URL) -> Bool {
        guard isTarget(requested), isTarget(current) else { return false }
        let a = identifier(requested), b = identifier(current)
        if a != nil || b != nil { return a != nil && a == b }
        return requested.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() ==
            current.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
    }

    /// Navigation permission only. Capture must prove the submitted name/ID in the current document.
    public static func canInspectRedirect(_ requested: URL, _ current: URL) -> Bool {
        guard isTarget(requested), isTarget(current) else { return false }
        return sameTarget(requested, current) || (identifier(requested) == nil) != (identifier(current) == nil)
    }

    private static func identifier(_ url: URL) -> String? {
        let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path == "profile.php" {
            return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value
        }
        if path.range(of: "^[0-9]+$", options: .regularExpression) != nil { return path }
        if path.hasPrefix("pages/") || path.hasPrefix("people/") { return path.split(separator: "/").last.map(String.init) }
        if path.hasPrefix("p/") { return path.split(separator: "-").last.map(String.init) }
        return nil
    }
}
