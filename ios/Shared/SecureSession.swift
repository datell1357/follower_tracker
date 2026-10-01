import CryptoKit
import Foundation
import Security
import FollowerCore

enum StorageFailure: Error { case unavailable, keychain(OSStatus), database, corrupted }

struct KeychainStore {
    private func query(_ name: String) -> [String: Any] {
        var result: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.datell.followertracker", kSecAttrAccount as String: name]
        if let group = Bundle.main.object(forInfoDictionaryKey: "SharedKeychainGroup") as? String, !group.contains("$(") {
            result[kSecAttrAccessGroup as String] = group
        }
        return result
    }
    func read(_ name: String) throws -> Data? {
        var request = query(name)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else { throw StorageFailure.keychain(status) }
        return data
    }
    func write(_ name: String, _ data: Data) throws {
        let request = query(name)
        let status = SecItemUpdate(request as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var insert = request
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let result = SecItemAdd(insert as CFDictionary, nil)
            guard result == errSecSuccess else { throw StorageFailure.keychain(result) }
        } else if status != errSecSuccess { throw StorageFailure.keychain(status) }
    }
    func remove(_ name: String) throws {
        let status = SecItemDelete(query(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw StorageFailure.keychain(status) }
    }
    func seal(_ data: Data) throws -> Data {
        let material: Data
        if let existing = try read("relationship-key") { material = existing }
        else {
            let generated = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            // Add atomically: app and extension may create the key at the same time.
            var request = query("relationship-key")
            request[kSecValueData as String] = generated
            request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let result = SecItemAdd(request as CFDictionary, nil)
            guard result == errSecSuccess || result == errSecDuplicateItem else { throw StorageFailure.keychain(result) }
            guard let persisted = try read("relationship-key") else { throw StorageFailure.corrupted }
            material = persisted
        }
        guard material.count == 32 else { throw StorageFailure.corrupted }
        return try AES.GCM.seal(data, using: SymmetricKey(data: material)).combined!
    }
    func open(_ data: Data) throws -> Data {
        guard let key = try read("relationship-key"), key.count == 32 else { throw StorageFailure.corrupted }
        return try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: SymmetricKey(data: key))
    }
}

actor SessionVault {
    static let shared = SessionVault()
    private let keychain = KeychainStore()
    func load(_ provider: Provider) throws -> SavedSession? {
        try keychain.read("session-" + provider.rawValue).map { try JSONDecoder().decode(SavedSession.self, from: $0) }
    }
    func save(_ provider: Provider, _ session: SavedSession) throws {
        let allowed = session.cookies.filter { cookie in
            let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard let url = URL(string: "https://\(domain)/") else { return false }
            return provider.allows(url)
        }
        var copy = session; copy.cookies = allowed
        try keychain.write("session-" + provider.rawValue, JSONEncoder().encode(copy))
    }
    func responseCookies(_ provider: Provider, url: URL, headers: [String: String], sessionVersion: Int64) throws {
        guard provider.allows(url), var session = try load(provider), session.savedAt == sessionVersion else { return }
        for cookie in HTTPCookie.cookies(withResponseHeaderFields: headers, for: url) {
            let record = CookieRecord(cookie)
            let host = record.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard let domainURL = URL(string: "https://\(host)/"), provider.allows(domainURL),
                  url.host == host || (record.domain.hasPrefix(".") && url.host?.hasSuffix("." + host) == true) else { continue }
            session.cookies.removeAll { $0.name == record.name && $0.domain == record.domain && $0.path == record.path }
            if record.expires == nil || record.expires! > Date() { session.cookies.append(record) }
        }
        try save(provider, session)
    }
    func remove(_ provider: Provider) throws { try keychain.remove("session-" + provider.rawValue) }
}
