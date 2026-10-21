import XCTest
import SQLite3
import FollowerCore
@testable import FollowerTracker

final class FacebookPageStorageRuntimeTests: XCTestCase {
    func testExplicitLogoutHidesCardsAndWidgetsAndFreshLoginRestoresOnlyItsHistory() async throws {
        let repository = try TrackerRepository(databaseURL: location())
        let personal = try account("42", page: false), page = try account("99", page: true)
        let other = try Account(provider: .reddit, stableID: "fixture", username: "sample", displayName: "Sample",
            profileURL: URL(string: "https://www.reddit.com/user/sample/")!, connectedAt: 3)
        for owner in [personal, page, other] { try await repository.saveObservation(owner, metric(owner, at: 1_000, count: 95)) }
        try await repository.updateStatus(personal.id, .reauthRequired)
        let expired = try await repository.accounts()
        XCTAssertEqual(expired.count, 3, "Session expiry must keep the reconnect card")
        try await repository.markLoggedOut(.facebook)
        let connected = try await repository.accounts(), overviews = try await repository.overviews(), widgets = try await repository.widgetOverviews()
        XCTAssertEqual(connected.map(\.id), [other.id]); XCTAssertEqual(overviews.map(\.id), [other.id]); XCTAssertEqual(widgets.map(\.id), [other.id])
        for owner in [personal, page] {
            let retained = try await repository.account(owner.id), history = try await repository.history(owner.id)
            let selectedWidget = try await repository.widgetOverviews(key: owner.id)
            XCTAssertTrue(retained?.loggedOut == true); XCTAssertEqual(retained?.connectedAt, owner.connectedAt)
            XCTAssertEqual(history.map(\.observedAt), [1_000]); XCTAssertTrue(selectedWidget.isEmpty)
        }
        try await repository.saveObservation(personal, metric(personal, at: 2_000, count: 100), requireExisting: true)
        let afterLateWrite = try await repository.history(personal.id)
        XCTAssertEqual(afterLateWrite.map(\.observedAt), [1_000])
        var freshLogin = personal; freshLogin.connectedAt = 3_000
        try await repository.saveObservation(freshLogin, metric(personal, at: 3_000, count: 0))
        let restored = try await repository.account(personal.id), history = try await repository.history(personal.id)
        let restoredWidgets = try await repository.widgetOverviews(), pageWidget = try await repository.widgetOverviews(key: page.id)
        XCTAssertFalse(try XCTUnwrap(restored).loggedOut); XCTAssertEqual(restored?.connectedAt, personal.connectedAt)
        XCTAssertEqual(history.map(\.followers), [95, 0]); XCTAssertEqual(Set(restoredWidgets.map(\.id)), [personal.id, other.id])
        XCTAssertTrue(pageWidget.isEmpty)
    }

    func testLogoutAllowsAnotherPersonalIdentityWithoutDeletingTheArchivedHistory() async throws {
        let repository = try TrackerRepository(databaseURL: location())
        let original = try account("42", page: false), replacement = try account("77", page: false)
        try await repository.saveObservation(original, metric(original, at: 1_000, count: 5))
        try await repository.markLoggedOut(.facebook)
        try await repository.saveObservation(replacement, metric(replacement, at: 2_000, count: 0))
        let connected = try await repository.accounts(), archived = try await repository.account(original.id)
        XCTAssertEqual(connected.map(\.id), [replacement.id]); XCTAssertTrue(archived?.loggedOut == true)
        do { try await repository.saveObservation(original, metric(original, at: 3_000, count: 5)); XCTFail("Only one active personal profile per SNS") }
        catch let failure as CollectionFailure { XCTAssertEqual(failure.status, .checkRequired) }
        let originalHistory = try await repository.history(original.id), replacementHistory = try await repository.history(replacement.id)
        XCTAssertEqual(originalHistory.map(\.followers), [5]); XCTAssertEqual(replacementHistory.map(\.followers), [0])
    }

    private func account(_ id: String, page: Bool) throws -> Account {
        try Account(provider: .facebook, stableID: id, username: page ? "fixture.page" : "fixture.owner", displayName: page ? "Fixture Page" : "Fixture Owner",
            profileURL: URL(string: "https://www.facebook.com/profile.php?id=\(id)")!, connectedAt: 1,
            accountType: page ? .page : .profile, sessionOwnerID: page ? "42" : nil)
    }
    private func metric(_ account: Account, at: Int64, count: Int64) throws -> MetricSnapshot {
        try MetricSnapshot(accountKey: account.id, observedAt: at, followers: count, following: nil, source: "synthetic-page-storage")
    }
    private func location() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-page-storage-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("Tracker.sqlite")
    }
    func testPersonalAndMultiplePagesKeepIndependentHistoryAndDisconnection() async throws {
        let repository = try TrackerRepository(databaseURL: location())
        let personal = try account("42", page: false), first = try account("99", page: true), second = try account("88", page: true)
        for (account, at, count) in [(personal, Int64(10), Int64(4)), (first, 20, 0), (second, 30, 9), (first, 40, 7)] {
            try await repository.saveObservation(account, metric(account, at: at, count: count))
        }
        let initial = try await repository.accounts(), firstHistory = try await repository.history(first.id)
        XCTAssertEqual(initial.count, 3); XCTAssertEqual(firstHistory.map(\.followers), [0, 7])
        let selectedWidget = try await repository.widgetOverviews(key: second.id)
        XCTAssertEqual(selectedWidget.map(\.id), [second.id]); XCTAssertEqual(selectedWidget.first?.latest?.followers, 9)
        let otherPersonal = try account("77", page: false)
        do { try await repository.saveObservation(otherPersonal, metric(otherPersonal, at: 50, count: 5)); XCTFail("One personal profile per SNS") }
        catch let error as CollectionFailure { XCTAssertEqual(error.status, .checkRequired) }
        try await repository.disconnect(first.id)
        let remaining = try await repository.accounts(), personalHistory = try await repository.history(personal.id), otherHistory = try await repository.history(second.id)
        XCTAssertEqual(Set(remaining.map(\.id)), [personal.id, second.id])
        XCTAssertEqual(personalHistory.map(\.followers), [4]); XCTAssertEqual(otherHistory.map(\.followers), [9])
        let removedWidget = try await repository.widgetOverviews(key: first.id)
        XCTAssertTrue(removedWidget.isEmpty, "A removed Page's widget must not silently show another connected account")
    }
    func testProductionVersionTwoUpgradePreservesMetricsAndRelationships() async throws {
        let url = try location(), personal = try account("42", page: false)
        let snapshot = try metric(personal, at: 10, count: 4)
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &database), SQLITE_OK)
        func run(_ sql: String) throws {
            guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw NSError(domain: "SQLiteFixture", code: 1) }
        }
        let accountData = try JSONEncoder().encode(personal).map { String(format: "%02x", $0) }.joined()
        let metricData = try JSONEncoder().encode(snapshot).map { String(format: "%02x", $0) }.joined()
        try run("""
            PRAGMA foreign_keys=ON;
            CREATE TABLE accounts (key TEXT PRIMARY KEY, provider TEXT UNIQUE NOT NULL, connected INTEGER NOT NULL, payload BLOB NOT NULL);
            CREATE TABLE metrics (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, observed INTEGER, payload BLOB);
            CREATE TABLE scans (owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, direction TEXT, finished INTEGER, payload BLOB);
            CREATE TABLE relationship_changes (id TEXT PRIMARY KEY, owner TEXT REFERENCES accounts(key) ON DELETE CASCADE, detected INTEGER, state TEXT, payload BLOB);
            CREATE TABLE relationship_history_cursor (owner TEXT PRIMARY KEY REFERENCES accounts(key) ON DELETE CASCADE, finished INTEGER);
            CREATE TABLE sync_leases (owner TEXT PRIMARY KEY, token TEXT, expires INTEGER);
            INSERT INTO accounts VALUES('FACEBOOK:42','FACEBOOK',1,X'\(accountData)');
            INSERT INTO metrics VALUES('FACEBOOK:42',10,X'\(metricData)');
            INSERT INTO scans VALUES('FACEBOOK:42','FOLLOWERS',20,X'010203');
            INSERT INTO relationship_changes VALUES('fixture','FACEBOOK:42',20,'CANDIDATE',X'040506');
            INSERT INTO relationship_history_cursor VALUES('FACEBOOK:42',20);
            PRAGMA user_version=2;
            """)
        sqlite3_close(database); database = nil
        let repository = try TrackerRepository(databaseURL: url)
        let saved = try await repository.account(personal.id), history = try await repository.history(personal.id)
        XCTAssertEqual(saved, personal); XCTAssertEqual(history, [snapshot])
        let page = try account("99", page: true)
        try await repository.saveObservation(page, metric(page, at: 30, count: 0))
        XCTAssertEqual(sqlite3_open(url.path, &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        func count(_ table: String) -> Int64 {
            var statement: OpaquePointer?
            XCTAssertEqual(sqlite3_prepare_v2(database, "SELECT COUNT(*) FROM " + table, -1, &statement, nil), SQLITE_OK)
            defer { sqlite3_finalize(statement) }
            XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
            return sqlite3_column_int64(statement, 0)
        }
        for table in ["scans", "relationship_changes", "relationship_history_cursor"] { XCTAssertEqual(count(table), 1) }
        XCTAssertEqual(count("accounts"), 2); XCTAssertEqual(count("pragma_foreign_key_check"), 0)
    }
}
