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
        try run("PRAGMA foreign_keys=OFF")
        try run("BEGIN IMMEDIATE")
        for sql in try TrackerSchema.migrationStatements(from: 1) { try run(sql) }
        try run("COMMIT")
        try run("PRAGMA foreign_keys=ON")
        XCTAssertEqual(try value("PRAGMA user_version"), 3)
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
        XCTAssertTrue(try TrackerSchema.migrationStatements(from: 3).isEmpty)
        XCTAssertThrowsError(try TrackerSchema.migrationStatements(from: 4))
        XCTAssertThrowsError(try TrackerSchema.migrationStatements(from: -1))
    }

    func testVersionTwoMigrationPreservesExistingChildrenAndAllowsIndependentPages() throws {
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(":memory:", &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        func run(_ sql: String) throws {
            guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
                throw NSError(domain: "SQLiteTest", code: 1, userInfo: [NSLocalizedDescriptionKey: String(cString: sqlite3_errmsg(database))])
            }
        }
        func value(_ sql: String) -> Int64 {
            var statement: OpaquePointer?
            XCTAssertEqual(sqlite3_prepare_v2(database, sql, -1, &statement, nil), SQLITE_OK)
            defer { sqlite3_finalize(statement) }
            XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
            return sqlite3_column_int64(statement, 0)
        }
        try run("""
            PRAGMA foreign_keys=ON;
            CREATE TABLE accounts (key TEXT PRIMARY KEY, provider TEXT UNIQUE NOT NULL, connected INTEGER NOT NULL, payload BLOB NOT NULL);
            CREATE TABLE metrics (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, observed INTEGER, payload BLOB);
            CREATE TABLE scans (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, payload BLOB);
            CREATE TABLE relationship_changes (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, payload BLOB);
            CREATE TABLE relationship_history_cursor (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, finished INTEGER);
            CREATE TABLE sync_leases (owner TEXT PRIMARY KEY, token TEXT, expires INTEGER);
            INSERT INTO accounts VALUES('FACEBOOK:42','FACEBOOK',10,X'010203');
            INSERT INTO metrics VALUES('FACEBOOK:42',20,X'040506');
            INSERT INTO scans VALUES('FACEBOOK:42',X'070809');
            INSERT INTO relationship_changes VALUES('FACEBOOK:42',X'101112');
            INSERT INTO relationship_history_cursor VALUES('FACEBOOK:42',30);
            INSERT INTO sync_leases VALUES('FACEBOOK:42','fixture',40);
            PRAGMA user_version=2; PRAGMA foreign_keys=OFF; BEGIN IMMEDIATE;
            """)
        for sql in try TrackerSchema.migrationStatements(from: 2) { try run(sql) }
        try run("COMMIT; PRAGMA foreign_keys=ON")
        XCTAssertEqual(value("PRAGMA user_version"), 3)
        for table in ["accounts", "metrics", "scans", "relationship_changes", "relationship_history_cursor", "sync_leases"] {
            XCTAssertEqual(value("SELECT COUNT(*) FROM " + table), 1)
        }
        XCTAssertEqual(value("SELECT connected FROM accounts WHERE hex(payload)='010203'"), 10)
        XCTAssertEqual(value("SELECT observed FROM metrics WHERE hex(payload)='040506'"), 20)
        try run("INSERT INTO accounts VALUES('FACEBOOK:PAGE:99','FACEBOOK',50,X'13'); INSERT INTO accounts VALUES('FACEBOOK:PAGE:88','FACEBOOK',60,X'14'); INSERT INTO metrics VALUES('FACEBOOK:PAGE:99',70,X'15')")
        XCTAssertEqual(value("SELECT COUNT(*) FROM accounts WHERE provider='FACEBOOK'"), 3)
        try run("DELETE FROM accounts WHERE key='FACEBOOK:PAGE:99'")
        XCTAssertEqual(value("SELECT COUNT(*) FROM metrics"), 1)
        XCTAssertEqual(value("SELECT COUNT(*) FROM scans"), 1)
        XCTAssertEqual(value("SELECT COUNT(*) FROM relationship_changes"), 1)
        XCTAssertEqual(value("SELECT COUNT(*) FROM relationship_history_cursor"), 1)
        XCTAssertEqual(value("SELECT COUNT(*) FROM pragma_foreign_key_check"), 0)
    }
}
