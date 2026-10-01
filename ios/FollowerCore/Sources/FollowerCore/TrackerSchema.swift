import Foundation

/// The app and widget apply these statements atomically to their shared database.
public enum TrackerSchema {
    public static let version: Int64 = 2
    public static func migrationStatements(from previousVersion: Int64) throws -> [String] {
        guard (0...version).contains(previousVersion) else { throw CollectionFailure(.checkRequired) }
        guard previousVersion < version else { return [] }
        var statements: [String] = []
        if previousVersion == 0 {
            statements += [
                "CREATE TABLE IF NOT EXISTS accounts (key TEXT PRIMARY KEY, provider TEXT UNIQUE NOT NULL, connected INTEGER NOT NULL, payload BLOB NOT NULL)",
                "CREATE TABLE IF NOT EXISTS metrics (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, observed INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner, observed))",
                "CREATE TABLE IF NOT EXISTS scans (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, direction TEXT NOT NULL, finished INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner,direction,finished))",
                "CREATE TABLE IF NOT EXISTS sync_leases (owner TEXT PRIMARY KEY, token TEXT NOT NULL, expires INTEGER NOT NULL)"
            ]
        }
        statements += [
            "CREATE TABLE relationship_changes (id TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, detected INTEGER NOT NULL, state TEXT NOT NULL, payload BLOB NOT NULL)",
            "CREATE INDEX relationship_changes_owner_detected ON relationship_changes(owner,detected)",
            "CREATE INDEX relationship_changes_owner_state ON relationship_changes(owner,state)",
            "CREATE TABLE relationship_history_cursor (owner TEXT PRIMARY KEY NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, finished INTEGER NOT NULL)",
            "PRAGMA user_version=2"
        ]
        return statements
    }
}
