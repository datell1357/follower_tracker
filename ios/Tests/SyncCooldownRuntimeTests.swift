import XCTest
import FollowerCore
@testable import FollowerTracker

private actor CountingCollector: SessionCollecting {
    private(set) var metricRequests = 0
    private(set) var listRequests = 0
    func native(_ provider: Provider, expected: Account?, timeout: TimeInterval) async throws -> (Account, MetricSnapshot) {
        metricRequests += 1
        guard var account = expected else { throw CollectionFailure(.checkRequired) }
        account.status = .ready; account.nextAllowedAt = nil
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: nowMillis(), followers: 9, following: 0, source: "synthetic-cooldown"))
    }
    func relationships(_ account: Account) async throws -> (RelationshipSnapshot, RelationshipSnapshot) {
        listRequests += 1
        throw CollectionFailure(.listIncomplete)
    }
}

final class SyncCooldownRuntimeTests: XCTestCase {
    private func fixture(status: SyncStatus = .rateLimited, nextAllowedAt: Int64?) async throws -> (TrackerRepository, Account) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-sync-qa-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // This explicit database never uses the user's App Group or session Vault.
        let repository = try TrackerRepository(databaseURL: folder.appendingPathComponent("Tracker.sqlite"))
        var account = try Account(provider: .instagram, stableID: "fixture", username: "sample", displayName: "Sample",
            profileURL: URL(string: "https://www.instagram.com/sample/")!, connectedAt: 1)
        account.status = status; account.nextAllowedAt = nextAllowedAt
        let metric = try MetricSnapshot(accountKey: account.id, observedAt: 1_000, followers: 0, following: 0, source: "synthetic-baseline")
        try await repository.saveObservation(account, metric)
        return (repository, account)
    }

    func testManualRefreshDuringCooldownDoesNotRequestOrReplaceTheLastRecord() async throws {
        let until = nowMillis() + 60_000
        let (repository, account) = try await fixture(nextAllowedAt: until)
        let collector = CountingCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id)
        let requests = await collector.metricRequests, stored = try await repository.account(account.id)
        let history = try await repository.history(account.id)
        XCTAssertEqual(requests, 0)
        XCTAssertEqual(stored?.status, .rateLimited); XCTAssertEqual(stored?.nextAllowedAt, until)
        XCTAssertEqual(history.count, 1); XCTAssertEqual(history.last?.followers, 0)
        let leaseReleased = try await repository.claimSync(account.id, token: "qa-after-skipped-request")
        XCTAssertTrue(leaseReleased)
        try await repository.releaseSync(account.id, token: "qa-after-skipped-request")
    }
    func testBackgroundAndListRefreshUseTheSameCooldown() async throws {
        let until = nowMillis() + 60_000
        let (repository, account) = try await fixture(nextAllowedAt: until)
        let collector = CountingCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id, background: true)
        try await sync.relationships(account.id)
        let metrics = await collector.metricRequests, lists = await collector.listRequests
        let stored = try await repository.account(account.id)
        XCTAssertEqual(metrics, 0); XCTAssertEqual(lists, 0)
        XCTAssertEqual(stored?.nextAllowedAt, until)
    }
    func testElapsedCooldownAllowsOneExplicitRefresh() async throws {
        let (repository, account) = try await fixture(nextAllowedAt: nowMillis() - 1)
        let collector = CountingCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id)
        let requests = await collector.metricRequests, stored = try await repository.account(account.id)
        let history = try await repository.history(account.id)
        XCTAssertEqual(requests, 1); XCTAssertEqual(stored?.status, .ready); XCTAssertNil(stored?.nextAllowedAt)
        XCTAssertEqual(history.count, 2); XCTAssertEqual(history.last?.followers, 9)
    }
    func testBlockedBackgroundStatesNeverCallTheCollector() async throws {
        for status: SyncStatus in [.reauthRequired, .checkRequired, .formatChanged, .foregroundOnly] {
            let (repository, account) = try await fixture(status: status, nextAllowedAt: nil)
            let collector = CountingCollector(), sync = SyncService(repository: repository, collector: collector)
            try await sync.refresh(account.id, background: true)
            let requests = await collector.metricRequests, stored = try await repository.account(account.id)
            XCTAssertEqual(requests, 0); XCTAssertEqual(stored?.status, status)
        }
    }
}
