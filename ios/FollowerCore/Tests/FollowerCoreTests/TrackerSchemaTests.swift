import XCTest
import SQLite3
@testable import FollowerCore

final class TrackerSchemaTests: XCTestCase {
    func testVersionOneMigrationPreservesRecordsAndCascadesNewHistory() throws {
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(":memory:", &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        func run(_ sql: String) throws {
            let result = sqlite3_exec(database, sql, nil, nil, nil)
            guard result == SQLITE_OK else { throw NSError(domain: "SQLiteTest", code: Int(result), userInfo: [NSLocalizedDescriptionKey: String(cString: sqlite3_errmsg(database))]) }
        }
        func value(_ sql: String) throws -> Int64 {
            var statement: OpaquePointer?
            XCTAssertEqual(sqlite3_prepare_v2(database, sql, -1, &statement, nil), SQLITE_OK)
            defer { sqlite3_finalize(statement) }
            XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
            return sqlite3_column_int64(statement, 0)
        }
        // Independent version-one schema and opaque synthetic payloads.
        try run("PRAGMA foreign_keys=ON; CREATE TABLE accounts (key TEXT PRIMARY KEY, provider TEXT UNIQUE NOT NULL, connected INTEGER NOT NULL, payload BLOB NOT NULL); CREATE TABLE metrics (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, observed INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner, observed)); CREATE TABLE scans (owner TEXT NOT NULL REFERENCES accounts(key) ON DELETE CASCADE, direction TEXT NOT NULL, finished INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner,direction,finished)); CREATE TABLE sync_leases (owner TEXT PRIMARY KEY, token TEXT NOT NULL, expires INTEGER NOT NULL); PRAGMA user_version=1")
        try run("INSERT INTO accounts VALUES('owner','INSTAGRAM',10,X'010203'); INSERT INTO metrics VALUES('owner',20,X'040506'); INSERT INTO scans VALUES('owner','FOLLOWERS',30,X'070809'); INSERT INTO sync_leases VALUES('owner','lease',40)")
        try run("BEGIN IMMEDIATE")
        for sql in try TrackerSchema.migrationStatements(from: 1) { try run(sql) }
        try run("COMMIT")
        XCTAssertEqual(try value("PRAGMA user_version"), 2)
        XCTAssertEqual(try value("SELECT connected FROM accounts WHERE hex(payload)='010203'"), 10)
        XCTAssertEqual(try value("SELECT observed FROM metrics WHERE hex(payload)='040506'"), 20)
        XCTAssertEqual(try value("SELECT finished FROM scans WHERE hex(payload)='070809'"), 30)
        XCTAssertEqual(try value("SELECT expires FROM sync_leases WHERE token='lease'"), 40)
        try run("INSERT INTO relationship_changes VALUES('opaque-uuid','owner',30,'CANDIDATE',X'101112'); INSERT INTO relationship_history_cursor VALUES('owner',30)")
        try run("DELETE FROM accounts WHERE key='owner'")
        for table in ["metrics", "scans", "relationship_changes", "relationship_history_cursor"] {
            XCTAssertEqual(try value("SELECT COUNT(*) FROM " + table), 0)
        }
        XCTAssertEqual(try value("SELECT COUNT(*) FROM pragma_foreign_key_check"), 0)
    }

    func testCurrentVersionIsNoOpAndFutureVersionIsRejected() throws {
        XCTAssertTrue(try TrackerSchema.migrationStatements(from: 2).isEmpty)
        XCTAssertThrowsError(try TrackerSchema.migrationStatements(from: 3))
        XCTAssertThrowsError(try TrackerSchema.migrationStatements(from: -1))
    }
}
