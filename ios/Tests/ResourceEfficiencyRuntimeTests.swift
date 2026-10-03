import XCTest
import Foundation
import FollowerCore
@testable import FollowerTracker

private actor ResourceCollector: SessionCollecting {
    private(set) var requests = 0
    func native(_ provider: Provider, expected: Account?, timeout: TimeInterval) async throws -> (Account, MetricSnapshot) {
        requests += 1
        var account = try XCTUnwrap(expected); account.status = .ready
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: 5_000, followers: 0, following: 2, source: "synthetic-resource"))
    }
    func relationships(_ account: Account) async throws -> (RelationshipSnapshot, RelationshipSnapshot) {
        requests += 1; throw CollectionFailure(.listIncomplete)
    }
}

final class ResourceEfficiencyRuntimeTests: XCTestCase {
    private let providers: [Provider] = [.instagram, .tiktok, .x, .facebook, .reddit]
    private func repository() throws -> TrackerRepository {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-resource-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return try TrackerRepository(databaseURL: folder.appendingPathComponent("Tracker.sqlite"))
    }
    private func account(_ provider: Provider) throws -> Account {
        try Account(provider: provider, stableID: "42", username: "fixture", displayName: "Fixture",
            profileURL: URL(string: "https://\(provider.domain)/fixture/")!, connectedAt: 1)
    }

    func testOfflineAutomaticCollectionPreservesEveryProviderAndManualRefreshStillRuns() async throws {
        let repository = try repository(), collector = ResourceCollector()
        let sync = SyncService(repository: repository, collector: collector, isDefinitelyOffline: { true })
        for provider in providers {
            let account = try account(provider)
            try await repository.saveObservation(account, MetricSnapshot(accountKey: account.id, observedAt: 1_000,
                followers: 7, following: 2, source: "synthetic-baseline"))
            try await sync.refresh(account.id, background: true)
            try await sync.relationships(account.id, background: true)
            let stored = try await repository.account(account.id), history = try await repository.history(account.id)
            XCTAssertEqual(stored?.status, .ready); XCTAssertNil(stored?.lastAttemptAt)
            XCTAssertEqual(history.map(\.observedAt), [1_000])
        }
        let skippedRequests = await collector.requests
        XCTAssertEqual(skippedRequests, 0)
        for provider in providers { try await sync.refresh(account(provider).id) }
        let manualRequests = await collector.requests
        XCTAssertEqual(manualRequests, 5)
    }

    func testWidgetReadsTwoFreshMetricsWithoutLosingHistoryOrProviderSelection() async throws {
        let repository = try repository()
        for provider in providers {
            let account = try account(provider)
            for i in 1...4 {
                try await repository.saveObservation(account, MetricSnapshot(accountKey: account.id,
                    observedAt: Int64(i) * 1_000, followers: Int64(4 - i), following: 2, source: "synthetic-history"))
            }
        }
        let rows = try await repository.widgetOverviews()
        XCTAssertEqual(rows.count, 5)
        for row in rows {
            XCTAssertEqual(row.history.count, 2); XCTAssertEqual(row.latest?.followers, 0)
            XCTAssertEqual(row.latest?.observedAt, 4_000); XCTAssertEqual(row.change, -1)
            let fullHistory = try await repository.history(row.id), selected = try await repository.widgetOverviews(provider: row.account.provider)
            XCTAssertEqual(fullHistory.count, 4); XCTAssertEqual(selected.map(\.id), [row.id])
            try await repository.updateStatus(row.id, .rateLimited, nextAllowedAt: 9_000)
            let failed = try await repository.widgetOverviews(provider: row.account.provider).first
            XCTAssertEqual(failed?.account.status, .rateLimited)
            XCTAssertEqual(failed?.latest, row.latest); XCTAssertEqual(failed?.change, row.change)
        }
    }

    func testProvidersShareAnEphemeralTransportWithNoCookieOrResponseCache() {
        let first = SessionHTTPClient(vault: SessionVault()), second = SessionHTTPClient(vault: SessionVault())
        XCTAssertTrue(first.transport === second.transport)
        let configuration = first.transport.configuration
        XCTAssertNil(configuration.httpCookieStorage); XCTAssertFalse(configuration.httpShouldSetCookies)
        XCTAssertNil(configuration.urlCache)
        XCTAssertEqual(configuration.requestCachePolicy, .reloadIgnoringLocalCacheData)
    }
}
