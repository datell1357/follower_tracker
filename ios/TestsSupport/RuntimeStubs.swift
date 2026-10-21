#if os(macOS)
import Foundation
import Security
import FollowerCore

// Test-only OS substitutes. The app and widget project do not compile this directory.
// These tests exercise real SQLite/HTTP code, not encryption, Keychain entitlements, or OS scheduling.
enum StorageFailure: Error { case unavailable, keychain(OSStatus), database, corrupted }
typealias SessionWriteGuard = @Sendable () throws -> Bool
struct KeychainStore: Sendable {
    func seal(_ data: Data) throws -> Data { data }
    func open(_ data: Data) throws -> Data { data }
}
struct NetworkAvailability: Sendable {
    static let shared = NetworkAvailability()
    let isDefinitelyOffline = false
}
actor SessionVault {
    static let shared = SessionVault()
    func load(_ provider: Provider) throws -> SavedSession? { nil }
    func captureGuard(_ provider: Provider) throws -> SessionWriteGuard { { true } }
    func responseCookies(_ provider: Provider, url: URL, headers: [String: String], sessionVersion: Int64) throws { }
}
#endif
