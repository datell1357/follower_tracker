import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct CookieRecord: Codable, Sendable {
    public let name: String, value: String, domain: String, path: String
    public let expires: Date?
    public let secure: Bool, httpOnly: Bool
    public init(_ cookie: HTTPCookie) {
        name = cookie.name; value = cookie.value; domain = cookie.domain; path = cookie.path
        expires = cookie.expiresDate; secure = cookie.isSecure; httpOnly = cookie.isHTTPOnly
    }
    public func matches(_ url: URL, at: Date = Date()) -> Bool {
        guard let host = url.host?.lowercased(), expires == nil || expires! > at,
              !secure || url.scheme == "https" else { return false }
        let cleanDomain = domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let hostMatch = host == cleanDomain || (domain.hasPrefix(".") && host.hasSuffix("." + cleanDomain))
        let requestPath = url.path.isEmpty ? "/" : url.path
        let pathMatch = requestPath == path || (requestPath.hasPrefix(path) && (path.hasSuffix("/") || requestPath.dropFirst(path.count).hasPrefix("/")))
        return hostMatch && pathMatch
    }
    public var cookie: HTTPCookie? {
        var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value, .domain: domain, .path: path,
            .secure: secure ? "TRUE" : "FALSE", HTTPCookiePropertyKey("HttpOnly"): httpOnly ? "TRUE" : "FALSE"]
        if let expires { properties[.expires] = expires }
        return HTTPCookie(properties: properties)
    }
}

public struct SavedSession: Codable, Sendable {
    public var cookies: [CookieRecord]
    public var userAgent: String
    public var expectedID: String?
    public var savedAt: Int64
    public init(cookies: [CookieRecord], userAgent: String, expectedID: String?, savedAt: Int64) {
        self.cookies = cookies; self.userAgent = userAgent; self.expectedID = expectedID; self.savedAt = savedAt
    }
    public func identity(_ provider: Provider) -> String? {
        let values = Dictionary(cookies.filter { $0.matches(provider.loginURL) }.map { ($0.name, $0.value) }, uniquingKeysWith: { first, _ in first })
        switch provider {
        case .instagram: return values["ds_user_id"]?.range(of: "^[0-9]+$", options: .regularExpression) != nil ? values["ds_user_id"] : nil
        case .facebook: return values["c_user"]?.range(of: "^[0-9]+$", options: .regularExpression) != nil ? values["c_user"] : nil
        case .x:
            let decoded = values["twid"]?.removingPercentEncoding?.trimmingCharacters(in: CharacterSet(charactersIn: "\"")) ?? ""
            guard decoded.hasPrefix("u="), decoded.dropFirst(2).allSatisfy(\.isNumber), decoded.count > 2 else { return nil }
            return String(decoded.dropFirst(2))
        case .tiktok, .reddit: return expectedID
        }
    }
    public func authenticated(_ provider: Provider) -> Bool {
        let names = Set(cookies.filter { $0.matches(provider.loginURL) }.map(\.name))
        switch provider {
        case .instagram: return names.contains("sessionid") && identity(provider) != nil
        case .tiktok: return !names.isDisjoint(with: ["sessionid", "sessionid_ss", "sid_tt", "sid_guard"])
        case .x: return names.contains("auth_token") && identity(provider) != nil
        case .facebook: return names.contains("xs") && identity(provider) != nil
        case .reddit: return !names.isDisjoint(with: ["reddit_session", "token_v2"])
        }
    }
}
