import Foundation
import SQLite3
import FollowerCore

struct AccountOverview: Identifiable, Sendable {
    let account: Account
    let history: [MetricSnapshot]
    var id: String { account.id }
    var latest: MetricSnapshot? { history.last }
    var previous: MetricSnapshot? { history.dropLast().last }
    var comparison: MetricComparison? { MetricComparison.between(previous: previous, current: latest) }
    var change: Int64? { comparison?.change }
    var comparisonAt: Int64? { comparison?.previousAt }
}

private enum SQLValue { case text(String), integer(Int64), blob(Data) }
private final class SQLiteDatabase {
    private var handle: OpaquePointer?
    init(url: URL) throws {
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else { throw StorageFailure.database }
        sqlite3_busy_timeout(handle, 5_000)
        try run("PRAGMA foreign_keys=ON")
        try run("PRAGMA journal_mode=WAL")
        try transaction {
            let version = try scalar("PRAGMA user_version")
            for statement in try TrackerSchema.migrationStatements(from: version) { try run(statement) }
        }
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
    func scalar(_ sql: String, _ values: [SQLValue] = []) throws -> Int64 {
        let statement = try prepare(sql, values); defer { sqlite3_finalize(statement) }
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
            try restoreRelationshipHistory(followers.accountKey)
            try recordRelationshipChanges(followers, previous: scans(followers.accountKey, .followers).first)
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
    private func pendingRelationshipChanges(_ key: String) throws -> [RelationshipChange] {
        try database.blobs("SELECT payload FROM relationship_changes WHERE owner=? AND state!='REOBSERVED' ORDER BY detected DESC,id", [.text(key)])
            .map { try decoder.decode(RelationshipChange.self, from: keychain.open($0)) }
    }
    private func recordRelationshipChanges(_ current: RelationshipSnapshot, previous: RelationshipSnapshot?) throws {
        for change in try RelationshipHistory.observe(current: current, previous: previous, existing: pendingRelationshipChanges(current.accountKey)) {
            try database.run("INSERT INTO relationship_changes(id,owner,detected,state,payload) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,payload=excluded.payload",
                [.text(change.id), .text(change.accountKey), .integer(change.detectedAt), .text(change.state.rawValue), .blob(try keychain.seal(encoder.encode(change)))])
        }
        try database.run("INSERT INTO relationship_history_cursor(owner,finished) VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET finished=excluded.finished",
            [.text(current.accountKey), .integer(current.finishedAt)])
    }
    private func restoreRelationshipHistory(_ key: String) throws {
        guard try database.scalar("SELECT COUNT(*) FROM relationship_history_cursor WHERE owner=?", [.text(key)]) == 0 else { return }
        var previous: RelationshipSnapshot?
        var after = Int64.min
        while let data = try database.blobs("SELECT payload FROM scans WHERE owner=? AND direction='FOLLOWERS' AND finished>? ORDER BY finished LIMIT 1",
            [.text(key), .integer(after)]).first {
            let current = try decoder.decode(RelationshipSnapshot.self, from: keychain.open(data))
            guard current.accountKey == key else { throw StorageFailure.corrupted }
            try recordRelationshipChanges(current, previous: previous)
            previous = current; after = current.finishedAt
        }
    }
    func relationshipChanges(_ key: String) throws -> [RelationshipChange] {
        try database.transaction {
            guard try account(key) != nil else { return [] }
            try restoreRelationshipHistory(key)
            return try database.blobs("SELECT payload FROM relationship_changes WHERE owner=? ORDER BY detected DESC,id", [.text(key)])
                .map { try decoder.decode(RelationshipChange.self, from: keychain.open($0)) }
        }
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
