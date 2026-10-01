import Foundation
import SQLite3
import FollowerCore

struct AccountOverview: Identifiable, Sendable {
    let account: Account
    let history: [MetricSnapshot]
    var id: String { account.id }
    var latest: MetricSnapshot? { history.last }
    var previous: MetricSnapshot? { history.dropLast().last }
    var change: Int64? {
        guard let latest, let previous, latest.precision == .exact, previous.precision == .exact else { return nil }
        return latest.followers - previous.followers
    }
}

private enum SQLValue { case text(String), integer(Int64), blob(Data) }
private final class SQLiteDatabase {
    private var handle: OpaquePointer?
    init(url: URL) throws {
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else { throw StorageFailure.database }
        sqlite3_busy_timeout(handle, 5_000)
        try run("PRAGMA foreign_keys=ON")
        try run("PRAGMA journal_mode=WAL")
        let version = try scalar("PRAGMA user_version")
        guard version <= 1 else { throw StorageFailure.corrupted }
        try run("CREATE TABLE IF NOT EXISTS accounts (key TEXT PRIMARY KEY, provider TEXT UNIQUE NOT NULL, connected INTEGER NOT NULL, payload BLOB NOT NULL)")
        try run("CREATE TABLE IF NOT EXISTS metrics (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, observed INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner, observed))")
        try run("CREATE TABLE IF NOT EXISTS scans (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, direction TEXT NOT NULL, finished INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner,direction,finished))")
        try run("CREATE TABLE IF NOT EXISTS sync_leases (owner TEXT PRIMARY KEY, token TEXT NOT NULL, expires INTEGER NOT NULL)")
        try run("PRAGMA user_version=1")
    }
    deinit { sqlite3_close(handle) }
    private func prepare(_ sql: String, _ values: [SQLValue]) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw StorageFailure.database }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1), status: Int32
            switch value {
            case .text(let text): status = text.withCString { sqlite3_bind_text(statement, index, $0, -1, transient) }
            case .integer(let number): status = sqlite3_bind_int64(statement, index, number)
            case .blob(let data): status = data.withUnsafeBytes { sqlite3_bind_blob(statement, index, $0.baseAddress, Int32(data.count), transient) }
            }
            guard status == SQLITE_OK else { sqlite3_finalize(statement); throw StorageFailure.database }
        }
        return statement
    }
    func run(_ sql: String, _ values: [SQLValue] = []) throws {
        let statement = try prepare(sql, values); defer { sqlite3_finalize(statement) }
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW { result = sqlite3_step(statement) }
        guard result == SQLITE_DONE else { throw StorageFailure.database }
    }
    func scalar(_ sql: String) throws -> Int64 {
        let statement = try prepare(sql, []); defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { throw StorageFailure.database }
        return sqlite3_column_int64(statement, 0)
    }
    var changes: Int { Int(sqlite3_changes(handle)) }
    func blobs(_ sql: String, _ values: [SQLValue] = []) throws -> [Data] {
        let statement = try prepare(sql, values); defer { sqlite3_finalize(statement) }
        var output: [Data] = [], result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            guard let pointer = sqlite3_column_blob(statement, 0) else { throw StorageFailure.corrupted }
            output.append(Data(bytes: pointer, count: Int(sqlite3_column_bytes(statement, 0))))
            result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE else { throw StorageFailure.database }
        return output
    }
    func transaction<T>(_ operation: () throws -> T) throws -> T {
        try run("BEGIN IMMEDIATE")
        do { let result = try operation(); try run("COMMIT"); return result }
        catch { try? run("ROLLBACK"); throw error }
    }
}

actor TrackerRepository {
    private let database: SQLiteDatabase
    private let encoder = JSONEncoder(), decoder = JSONDecoder()
    private let keychain = KeychainStore()
    init() throws {
        let group = Bundle.main.object(forInfoDictionaryKey: "SharedAppGroup") as? String ?? "group.dev.datell.followertracker"
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { throw StorageFailure.unavailable }
        let directory = container.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var backupURL = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try backupURL.setResourceValues(values)
        database = try SQLiteDatabase(url: directory.appendingPathComponent("Tracker.sqlite"))
    }
    func accounts() throws -> [Account] { try database.blobs("SELECT payload FROM accounts ORDER BY connected").map { try decoder.decode(Account.self, from: $0) } }
    func account(_ key: String) throws -> Account? {
        try database.blobs("SELECT payload FROM accounts WHERE key=?", [.text(key)]).first.map { try decoder.decode(Account.self, from: $0) }
    }
    func history(_ key: String) throws -> [MetricSnapshot] {
        try database.blobs("SELECT payload FROM metrics WHERE owner=? ORDER BY observed DESC LIMIT 366", [.text(key)]).reversed().map { try decoder.decode(MetricSnapshot.self, from: $0) }
    }
    func overviews() throws -> [AccountOverview] { try accounts().map { try AccountOverview(account: $0, history: history($0.id)) } }
    private func put(_ account: Account) throws {
        try database.run("INSERT INTO accounts(key,provider,connected,payload) VALUES(?,?,?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload",
            [.text(account.id), .text(account.provider.rawValue), .integer(account.connectedAt), .blob(try encoder.encode(account))])
    }
    func saveObservation(_ account: Account, _ metric: MetricSnapshot, requireExisting: Bool = false) throws {
        guard account.id == metric.accountKey else { throw StorageFailure.corrupted }
        try database.transaction {
            let existing = try self.account(account.id)
            if requireExisting && existing?.connectedAt != account.connectedAt { return }
            if let occupied = try accounts().first(where: { $0.provider == account.provider }), occupied.id != account.id { throw CollectionFailure(.checkRequired) }
            var preserved = account
            if let existing { preserved.relationshipStatus = existing.relationshipStatus }
            try put(preserved)
            try database.run("INSERT INTO metrics(owner,observed,payload) VALUES(?,?,?)", [.text(metric.accountKey), .integer(metric.observedAt), .blob(try encoder.encode(metric))])
        }
    }
    func updateStatus(_ key: String, _ status: SyncStatus, nextAllowedAt: Int64? = nil, expectedConnectedAt: Int64? = nil) throws {
        try database.transaction {
            guard var account = try account(key) else { return }
            if let expectedConnectedAt, account.connectedAt != expectedConnectedAt { return }
            account.status = status; account.lastAttemptAt = nowMillis(); account.nextAllowedAt = nextAllowedAt
            try put(account)
        }
    }
    func listStatus(_ key: String, _ status: SyncStatus, expectedConnectedAt: Int64? = nil) throws {
        try database.transaction {
            guard var account = try account(key) else { return }
            if let expectedConnectedAt, account.connectedAt != expectedConnectedAt { return }
            account.relationshipStatus = status; try put(account)
        }
    }
    func saveScans(_ followers: RelationshipSnapshot, _ following: RelationshipSnapshot, expectedConnectedAt: Int64? = nil) throws {
        _ = try RelationshipAnalyzer.compare(followers: followers, following: following)
        try database.transaction {
            guard var account = try account(followers.accountKey) else { return }
            if let expectedConnectedAt, account.connectedAt != expectedConnectedAt { return }
            for scan in [followers, following] {
                try database.run("INSERT INTO scans(owner,direction,finished,payload) VALUES(?,?,?,?)",
                    [.text(scan.accountKey), .text(scan.direction.rawValue), .integer(scan.finishedAt), .blob(try keychain.seal(encoder.encode(scan)))])
            }
            account.relationshipStatus = .ready; account.capabilities.followers = .observed; account.capabilities.following = .observed
            try put(account)
        }
    }
    private func scans(_ key: String, _ direction: Direction) throws -> [RelationshipSnapshot] {
        try database.blobs("SELECT payload FROM scans WHERE owner=? AND direction=? ORDER BY finished DESC LIMIT 3", [.text(key), .text(direction.rawValue)])
            .map { try decoder.decode(RelationshipSnapshot.self, from: keychain.open($0)) }
    }
    func report(_ key: String) throws -> RelationshipReport? {
        let followers = try scans(key, .followers), following = try scans(key, .following)
        guard let current = followers.first, let following = following.first else { return nil }
        return try RelationshipAnalyzer.compare(followers: current, following: following, previous: followers.dropFirst().first, beforePrevious: followers.dropFirst(2).first)
    }
    func disconnect(_ key: String) throws { try database.run("DELETE FROM accounts WHERE key=?", [.text(key)]) }
    func claimSync(_ key: String, token: String) throws -> Bool {
        try database.transaction {
            try database.run("INSERT INTO sync_leases(owner,token,expires) VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET token=excluded.token, expires=excluded.expires WHERE expires<=?",
                [.text(key), .text(token), .integer(nowMillis() + 240_000), .integer(nowMillis())])
            return database.changes == 1
        }
    }
    func releaseSync(_ key: String, token: String) throws { try database.run("DELETE FROM sync_leases WHERE owner=? AND token=?", [.text(key), .text(token)]) }
}
