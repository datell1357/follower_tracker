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

private actor TransientCollector: SessionCollecting {
    private(set) var metricRequests = 0
    private(set) var listRequests = 0
    private var failure: SyncStatus? = .offline
    private var cancels = false
    private var retryAfterSeconds: Int64?
    func succeed() { failure = nil; cancels = false }
    func cancelNext() { cancels = true }
    func serverRetryAfter(_ seconds: Int64) { failure = .offline; retryAfterSeconds = seconds }
    func native(_ provider: Provider, expected: Account?, timeout: TimeInterval) async throws -> (Account, MetricSnapshot) {
        metricRequests += 1
        if cancels { throw CancellationError() }
        if let failure { throw CollectionFailure(failure, retryAfterSeconds: retryAfterSeconds) }
        guard var account = expected else { throw CollectionFailure(.checkRequired) }
        account.status = .ready; account.nextAllowedAt = nil
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: nowMillis(), followers: 9, following: 0, source: "synthetic-transient-recovery"))
    }
    func relationships(_ account: Account) async throws -> (RelationshipSnapshot, RelationshipSnapshot) {
        listRequests += 1
        if cancels { throw CancellationError() }
        if let failure { throw CollectionFailure(failure, retryAfterSeconds: retryAfterSeconds) }
        let at = nowMillis()
        func scan(_ direction: Direction) -> RelationshipSnapshot {
            RelationshipSnapshot(accountKey: account.id, direction: direction, startedAt: at, finishedAt: at,
                members: [], complete: true, consistent: true, endReason: "synthetic-terminal")
        }
        return (scan(.followers), scan(.following))
    }
}

final class SyncTransientRetryRuntimeTests: XCTestCase {
    private func fixture() async throws -> (TrackerRepository, Account, URL) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-transient-qa-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent("Tracker.sqlite")
        let repository = try TrackerRepository(databaseURL: url)
        let account = try Account(provider: .instagram, stableID: "fixture", username: "sample", displayName: "Sample",
            profileURL: URL(string: "https://www.instagram.com/sample/")!, connectedAt: 1)
        try await repository.saveObservation(account, MetricSnapshot(accountKey: account.id, observedAt: 1_000,
            followers: 0, following: 0, source: "synthetic-baseline"))
        return (repository, account, url)
    }

    func testAutomaticNetworkRetriesWaitAndKeepTheLastMetric() async throws {
        let (repository, account, url) = try await fixture()
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id, background: true)
        try await sync.refresh(account.id, background: true)
        let requests = await collector.metricRequests, stored = try await repository.account(account.id)
        let history = try await repository.history(account.id)
        XCTAssertEqual(requests, 1, "Automatic retries must wait after a transient network failure.")
        XCTAssertEqual(stored?.status, .offline)
        XCTAssertEqual(history.count, 1); XCTAssertEqual(history.last?.observedAt, 1_000)
        XCTAssertEqual(history.last?.followers, 0)
        XCTAssertEqual(stored?.transientRetry?.failureCount, 1)
        let reopened = try TrackerRepository(databaseURL: url)
        let decoded = try await reopened.account(account.id)
        XCTAssertEqual(decoded?.transientRetry, stored?.transientRetry)
    }

    func testAnExplicitRetryCanRecoverAndResetTheLocalBackoff() async throws {
        let (repository, account, _) = try await fixture()
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id, background: true)
        await collector.succeed()
        try await sync.refresh(account.id)
        let requests = await collector.metricRequests, stored = try await repository.account(account.id)
        let history = try await repository.history(account.id)
        XCTAssertEqual(requests, 2); XCTAssertEqual(stored?.status, .ready)
        XCTAssertNil(stored?.transientRetry); XCTAssertEqual(history.count, 2)
        XCTAssertEqual(history.last?.followers, 9)
    }

    func testConsecutiveFailuresIncreaseThePersistedDelay() async throws {
        let (repository, account, _) = try await fixture()
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id)
        let firstState = try await repository.account(account.id)?.transientRetry
        let first = try XCTUnwrap(firstState)
        try await sync.refresh(account.id)
        let secondState = try await repository.account(account.id)?.transientRetry
        let second = try XCTUnwrap(secondState)
        let requests = await collector.metricRequests
        XCTAssertEqual(requests, 2); XCTAssertEqual(second.failureCount, 2)
        XCTAssertGreaterThanOrEqual(second.nextAttemptAt, first.nextAttemptAt + 60_000)
        let history = try await repository.history(account.id)
        XCTAssertEqual(history.count, 1)
    }

    func testServerRetryAfterBlocksEvenAnExplicitNetworkRetry() async throws {
        let (repository, account, _) = try await fixture()
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        await collector.serverRetryAfter(120)
        let began = nowMillis()
        try await sync.refresh(account.id, background: true)
        try await sync.refresh(account.id)
        let requests = await collector.metricRequests, stored = try await repository.account(account.id)
        XCTAssertEqual(requests, 1)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(stored?.nextAllowedAt), began + 120_000)
        XCTAssertNotNil(stored?.transientRetry)
    }

    func testListFailuresShareTheAutomaticBackoffAndKeepTheCompletedReport() async throws {
        let (repository, account, _) = try await fixture()
        func baseline(_ direction: Direction) -> RelationshipSnapshot {
            RelationshipSnapshot(accountKey: account.id, direction: direction, startedAt: 1, finishedAt: 2,
                members: [], complete: true, consistent: true, endReason: "synthetic-terminal")
        }
        try await repository.saveScans(baseline(.followers), baseline(.following))
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.relationships(account.id, background: true)
        try await sync.refresh(account.id, background: true)
        try await sync.relationships(account.id, background: true)
        let lists = await collector.listRequests, metrics = await collector.metricRequests
        let report = try await repository.report(account.id)
        XCTAssertEqual(lists, 1); XCTAssertEqual(metrics, 0); XCTAssertEqual(report?.comparedAt, 2)
        await collector.succeed()
        try await sync.relationships(account.id)
        let stored = try await repository.account(account.id), history = try await repository.history(account.id)
        XCTAssertEqual(stored?.relationshipStatus, .ready); XCTAssertNil(stored?.transientRetry)
        XCTAssertEqual(history.count, 1)
    }

    func testCancellationRestoresTheOriginalRetryState() async throws {
        let (repository, account, _) = try await fixture()
        let collector = TransientCollector(), sync = SyncService(repository: repository, collector: collector)
        try await sync.refresh(account.id, background: true)
        let before = try await repository.account(account.id)
        await collector.cancelNext()
        do { try await sync.refresh(account.id); XCTFail("The cancelled collection must propagate cancellation") }
        catch is CancellationError { }
        let after = try await repository.account(account.id), history = try await repository.history(account.id)
        XCTAssertEqual(after?.status, before?.status); XCTAssertEqual(after?.nextAllowedAt, before?.nextAllowedAt)
        XCTAssertEqual(after?.transientRetry, before?.transientRetry); XCTAssertEqual(history.count, 1)
    }
}
