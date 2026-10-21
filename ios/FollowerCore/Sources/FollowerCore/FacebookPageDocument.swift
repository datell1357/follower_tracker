import Foundation
import CoreFoundation

/// Reads structured data from a fresh Facebook response. It never executes page scripts.
public enum FacebookPageDocument {
    public static func observation(_ html: String, url: URL, expected: Account, session: SavedSession,
                                   now: Int64) throws -> (Account, MetricSnapshot) {
        guard session.authenticated(.facebook), let owner = session.identity(.facebook) else { throw CollectionFailure(.reauthRequired) }
        guard expected.provider == .facebook, expected.accountType == .page, !expected.loggedOut,
              expected.authenticationOwnerID == owner, let current = address(url) else { throw CollectionFailure(.checkRequired) }
        guard html.utf8.count <= 8 * 1024 * 1024 else { throw CollectionFailure(.formatChanged) }
        let document = try PageHTML(html)
        var ids = Set([current.id].compactMap { $0 }), names = Set([current.name].compactMap { $0 })
        for value in document.canonicals {
            guard let target = URL(string: value, relativeTo: url)?.absoluteURL, let canonical = address(target) else { throw CollectionFailure(.checkRequired) }
            if let id = canonical.id { ids.insert(id) }
            if let name = canonical.name {
                if let requested = current.name, requested != name { throw CollectionFailure(.checkRequired) }
                names.insert(name)
            }
        }
        for value in document.pageIDs { ids.insert(value) }
        guard ids.count <= 1, ids.isEmpty || ids == [expected.stableID], names.count <= 1 else { throw CollectionFailure(.checkRequired) }

        var queue = document.roots.map { ($0, 0) }, index = 0
        var pages: [[String: Any]] = [], sparsePages: [[String: Any]] = [], users: [[String: Any]] = []
        while index < queue.count {
            if index & 511 == 0 { try Task.checkCancellation() }
            guard index < 100_000 else { throw CollectionFailure(.formatChanged) }
            let (value, depth) = queue[index]; index += 1
            guard depth <= 32 else { throw CollectionFailure(.formatChanged) }
            if let object = value as? [String: Any] {
                if object["__typename"] as? String == "User", identifier(object["id"]) == expected.stableID { users.append(object) }
                let delegated = object["__typename"] as? String == "User" && identifier((object["delegate_page"] as? [String: Any])?["id"]) != nil
                if object["__typename"] as? String == "Page" || object["__isPage"] as? String == "Page" || delegated,
                   let id = identifier(object["id"]) {
                    let recordURL = object["url"] as? String ?? object["profile_url"] as? String
                    let recordAddress = recordURL
                        .flatMap { URL(string: $0, relativeTo: url)?.absoluteURL }.flatMap(address)
                    let recordNames = Set([recordAddress?.name, (object["username"] as? String)?.lowercased()].compactMap { $0 })
                    let namedMatch = !names.isDisjoint(with: recordNames)
                    if ids.contains(id) || namedMatch || id == expected.stableID && recordNames.isEmpty {
                        if recordURL != nil && recordAddress == nil { throw CollectionFailure(.checkRequired) }
                        if let recordID = recordAddress?.id, recordID != id { throw CollectionFailure(.checkRequired) }
                        if let recordName = recordAddress?.name, !names.isEmpty && !names.contains(recordName) { throw CollectionFailure(.checkRequired) }
                        guard pages.count + sparsePages.count < 512 else { throw CollectionFailure(.formatChanged) }
                        if !names.isEmpty && !namedMatch {
                            guard recordNames.isEmpty, id == expected.stableID else { throw CollectionFailure(.checkRequired) }
                            // A sparse fragment cannot establish a named route's identity on its own.
                            sparsePages.append(object)
                        } else { ids.insert(id); pages.append(object) }
                    }
                }
                guard queue.count + object.count <= 100_000 else { throw CollectionFailure(.formatChanged) }
                queue.append(contentsOf: object.values.filter { $0 is [String: Any] || $0 is [Any] }.map { ($0, depth + 1) })
            } else if let array = value as? [Any] {
                guard queue.count + array.count <= 100_000 else { throw CollectionFailure(.formatChanged) }
                queue.append(contentsOf: array.filter { $0 is [String: Any] || $0 is [Any] }.map { ($0, depth + 1) })
            }
        }
        guard ids == [expected.stableID] else { throw CollectionFailure(.checkRequired) }
        guard !pages.isEmpty else { throw CollectionFailure(.foregroundOnly) }
        pages.append(contentsOf: sparsePages)
        let delegated = pages.contains { $0["__typename"] as? String == "User" && identifier(($0["delegate_page"] as? [String: Any])?["id"]) != nil }
        let ownUsers = users.filter { identifier($0["id"]) == expected.stableID }
        if !delegated && !ownUsers.isEmpty { throw CollectionFailure(.checkRequired) }
        if delegated { pages.append(contentsOf: ownUsers) }

        var counts: Set<Int64> = []
        let labelPattern = #"^(?:([0-9][0-9,\s\u00a0\u202f]*)\s*(?:명의?\s*)?(?:팔로워|followers?)|(?:팔로워|followers?)\s*([0-9][0-9,\s\u00a0\u202f]*)(?:명)?)$"#
        let labelRegex = try NSRegularExpression(pattern: labelPattern, options: .caseInsensitive)
        for page in pages {
            let recordName = (page["url"] as? String ?? page["profile_url"] as? String)
                .flatMap { URL(string: $0, relativeTo: url)?.absoluteURL }.flatMap(address)?.name
            let labelNames = names.union([recordName].compactMap { $0 })
            for value in [page["followers_count"], page["follower_count"], (page["followers"] as? [String: Any])?["count"]].compactMap({ $0 }) {
                if let count = try exact(value) { counts.insert(count) }
            }
            if let content = (page["profile_social_context"] as? [String: Any])?["content"] as? [[String: Any]] {
                guard content.count <= 32 else { throw CollectionFailure(.formatChanged) }
                for item in content {
                    guard let text = item["text"] as? String ?? (item["text"] as? [String: Any])?["text"] as? String,
                          text.count <= 140, let value = item["uri"] as? String,
                          let itemURL = URL(string: value, relativeTo: url)?.absoluteURL,
                          var link = URLComponents(url: itemURL, resolvingAgainstBaseURL: false) else { continue }
                    if link.path.hasSuffix("/followers") { link.path = String(link.path.dropLast(10)) }
                    else if link.path != "/profile.php" || !(link.queryItems ?? []).contains(where: { $0.name == "sk" && $0.value == "followers" }) { continue }
                    guard let target = link.url.flatMap(address),
                          target.id == expected.stableID || target.name.map({ labelNames.contains($0) }) == true else { continue }
                    let label = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let match = labelRegex.firstMatch(in: label, range: NSRange(label.startIndex..., in: label)),
                       let range = Range(match.range(at: match.range(at: 1).location == NSNotFound ? 2 : 1), in: label),
                       let count = exactDisplayedCount(String(label[range])), count <= 9_007_199_254_740_991 { counts.insert(count) }
                }
            }
        }
        guard counts.count <= 1 else { throw CollectionFailure(.formatChanged) }
        guard let followers = counts.first, let display = pages.compactMap({ $0["name"] as? String }).first,
              !display.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, display.utf16.count <= 256 else { throw CollectionFailure(.foregroundOnly) }
        let username = pages.compactMap { $0["username"] as? String }.first ?? current.name ?? expected.username
        guard !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, username.utf16.count <= 256 else { throw CollectionFailure(.formatChanged) }
        var account = try Account(provider: .facebook, stableID: expected.stableID, username: username,
            displayName: display, profileURL: expected.profileURL, connectedAt: expected.connectedAt,
            accountType: .page, sessionOwnerID: owner)
        account.status = .ready; account.lastAttemptAt = now
        account.capabilities.count = .observed; account.capabilities.background = .unverified
        account.capabilities.followers = .unavailable; account.capabilities.following = .unavailable
        try Task.checkCancellation()
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: now, followers: followers,
            following: nil, source: "facebook-page-session"))
    }

    /// Redirects remain navigation only until the final document proves the originally selected Page ID.
    public static func redirect(_ source: URL, location: String, pageID: String) -> URL? {
        guard pageID.range(of: "^[0-9]+$", options: .regularExpression) != nil,
              let origin = address(source), origin.id == nil || origin.id == pageID, location.utf8.count <= 16_384,
              let next = URL(string: location, relativeTo: source)?.absoluteURL, next.fragment == nil, let target = address(next),
              target.id == nil || target.id == pageID else { return nil }
        return next
    }
    private static func identifier(_ value: Any?) -> String? {
        if let value = value as? String, value.range(of: "^[0-9]+$", options: .regularExpression) != nil { return value }
        if let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
           let count = exactDisplayedCount(value.stringValue), count <= 9_007_199_254_740_991 { return String(count) }
        return nil
    }
    private static func exact(_ value: Any) throws -> Int64? {
        if let number = value as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID(), let count = exactDisplayedCount(number.stringValue),
                  count <= 9_007_199_254_740_991 else { throw CollectionFailure(.formatChanged) }
            return count
        }
        if let text = value as? String {
            if let count = exactDisplayedCount(text) {
                guard count <= 9_007_199_254_740_991 else { throw CollectionFailure(.formatChanged) }
                return count
            }
            if text.range(of: #"^[0-9]+(?:\.[0-9]+)?\s*(?:[kmb]|천|만|억)$"#, options: [.regularExpression, .caseInsensitive]) != nil { return nil }
            throw CollectionFailure(.formatChanged)
        }
        if value is NSNull { return nil }
        throw CollectionFailure(.formatChanged)
    }
    private struct Address { let id: String?; let name: String? }
    private static func address(_ url: URL) -> Address? {
        guard FacebookPageAddress.isTarget(url),
              canAutoConnect(.facebook, url: url, authenticated: true, loading: false, busy: false) else { return nil }
        let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path == "profile.php" {
            return Address(id: URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value, name: nil)
        }
        if path.range(of: "^[0-9]+$", options: .regularExpression) != nil { return Address(id: path, name: nil) }
        if path.hasPrefix("pages/") || path.hasPrefix("people/") { return Address(id: path.split(separator: "/").last.map(String.init), name: nil) }
        if path.hasPrefix("p/"), let id = path.split(separator: "-").last, id.allSatisfy(\.isNumber) { return Address(id: String(id), name: nil) }
        return Address(id: nil, name: path.lowercased())
    }
}

/// Small bounded reader for response metadata and JSON script elements; form values are never read.
private struct PageHTML {
    var canonicals: [String] = [], pageIDs: [String] = [], roots: [Any] = []
    init(_ text: String) throws {
        try Task.checkCancellation()
        let input = Array(text.utf8)
        var cursor = 0, tags = 0, jsonBytes = 0
        let entityRegex = try NSRegularExpression(pattern: #"&(#(?:x[0-9a-f]{1,8}|[0-9]{1,10})|amp|quot|apos|lt|gt);"#, options: .caseInsensitive)
        func space(_ byte: UInt8) -> Bool { [9, 10, 12, 13, 32].contains(byte) }
        func slice(_ start: Int, _ end: Int) -> String { String(decoding: input[start..<end], as: UTF8.self) }
        func locate(_ value: String, after: Int, tagBoundary: Bool = false) throws -> Int? {
            let needle = Array(value.utf8)
            var position = after
            while position + needle.count <= input.count {
                if position & 65_535 == 0 { try Task.checkCancellation() }
                if needle.indices.allSatisfy({ index in
                    let byte = input[position + index]
                    return (byte >= 65 && byte <= 90 ? byte + 32 : byte) == needle[index]
                }) {
                    let end = position + needle.count
                    if !tagBoundary || end < input.count && (space(input[end]) || input[end] == 62 || input[end] == 47) { return position }
                }
                position += 1
            }
            return nil
        }
        while cursor < input.count {
            guard let start = input[cursor...].firstIndex(of: 60) else { break }
            cursor = start + 1; tags += 1
            if tags & 255 == 0 { try Task.checkCancellation() }
            guard cursor < input.count else { throw CollectionFailure(.formatChanged) }
            guard tags <= 100_000 else { throw CollectionFailure(.formatChanged) }
            if cursor + 2 < input.count, slice(cursor, cursor + 3) == "!--" {
                guard let end = try locate("-->", after: cursor + 3) else { throw CollectionFailure(.formatChanged) }; cursor = end + 3; continue
            }
            let nameStart = cursor
            while cursor < input.count, !space(input[cursor]), input[cursor] != 62, input[cursor] != 47 { cursor += 1 }
            guard cursor - nameStart <= 256 else { throw CollectionFailure(.formatChanged) }
            let tag = slice(nameStart, cursor).lowercased()
            if tag.isEmpty { cursor += 1; continue }
            let capturesAttributes = ["meta", "link", "script"].contains(tag)
            var attributes: [String: String] = [:]
            while cursor < input.count && input[cursor] != 62 {
                if space(input[cursor]) || input[cursor] == 47 { cursor += 1; continue }
                let keyStart = cursor
                while cursor < input.count, !space(input[cursor]), input[cursor] != 61, input[cursor] != 62, input[cursor] != 47 { cursor += 1 }
                let key = slice(keyStart, cursor).lowercased()
                while cursor < input.count && space(input[cursor]) { cursor += 1 }
                var value = ""
                if cursor < input.count && input[cursor] == 61 {
                    cursor += 1
                    while cursor < input.count && space(input[cursor]) { cursor += 1 }
                    if cursor < input.count && (input[cursor] == 34 || input[cursor] == 39) {
                        let quote = input[cursor]; cursor += 1; let begin = cursor
                        while cursor < input.count && input[cursor] != quote { cursor += 1 }
                        if capturesAttributes { value = slice(begin, cursor) }
                        if cursor < input.count { cursor += 1 }
                    } else {
                        let begin = cursor
                        while cursor < input.count && !space(input[cursor]) && input[cursor] != 62 { cursor += 1 }
                        if capturesAttributes { value = slice(begin, cursor) }
                    }
                }
                guard cursor - start <= 16_384, !key.isEmpty else { throw CollectionFailure(.formatChanged) }
                if capturesAttributes {
                    let decoded = try Self.decoded(value, using: entityRegex)
                    if let old = attributes[key], old != decoded { throw CollectionFailure(.formatChanged) }
                    attributes[key] = decoded
                }
            }
            guard cursor < input.count else { throw CollectionFailure(.formatChanged) }; cursor += 1
            if tag == "plaintext" { break }
            if tag == "link", attributes["rel"]?.lowercased() == "canonical", let href = attributes["href"] { canonicals.append(href) }
            if tag == "meta" {
                let property = attributes["property"]?.lowercased(), content = attributes["content"] ?? ""
                if property == "og:url" { canonicals.append(content) }
                if property == "fb:page_id", content.range(of: "^[0-9]+$", options: .regularExpression) != nil { pageIDs.append(content) }
                if property == "al:android:url", let match = try NSRegularExpression(pattern: #"^fb://(?:page|profile)/([0-9]+)(?:[/?#]|$)"#)
                    .firstMatch(in: content, range: NSRange(content.startIndex..., in: content)), let range = Range(match.range(at: 1), in: content) { pageIDs.append(String(content[range])) }
            }
            if ["script", "style", "template", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript"].contains(tag) {
                guard let end = try locate("</" + tag, after: cursor, tagBoundary: true),
                      let closing = input[(end + 2 + tag.utf8.count)...].firstIndex(of: 62),
                      closing - end <= 16_384 else { throw CollectionFailure(.formatChanged) }
                if tag == "template", let nested = try locate("<template", after: cursor, tagBoundary: true), nested < end { throw CollectionFailure(.formatChanged) }
                if tag == "script" && (attributes["type"]?.lowercased() == "application/json" || attributes["data-sjs"] != nil) {
                    jsonBytes += end - cursor
                    guard jsonBytes <= 4 * 1024 * 1024, roots.count < 256 else { throw CollectionFailure(.formatChanged) }
                    let data = Data(input[cursor..<end])
                    guard let root = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) else { throw CollectionFailure(.formatChanged) }
                    roots.append(root)
                }
                cursor = closing + 1
            }
        }
    }
    private static func decoded(_ value: String, using regex: NSRegularExpression) throws -> String {
        guard value.utf8.contains(38) else { return value }
        let result = NSMutableString(string: value)
        for match in regex.matches(in: value, range: NSRange(value.startIndex..., in: value)).reversed() {
            guard let range = Range(match.range(at: 1), in: value) else { continue }
            let code = String(value[range]).lowercased(), replacement: String
            if code.hasPrefix("#") {
                let hex = code.hasPrefix("#x"), digits = code.dropFirst(hex ? 2 : 1)
                guard let number = UInt32(digits, radix: hex ? 16 : 10), let scalar = UnicodeScalar(number), number != 0 else { throw CollectionFailure(.formatChanged) }
                replacement = String(scalar)
            } else { replacement = ["amp": "&", "quot": "\"", "apos": "'", "lt": "<", "gt": ">"][code]! }
            result.replaceCharacters(in: match.range, with: replacement)
        }
        return result as String
    }
}
