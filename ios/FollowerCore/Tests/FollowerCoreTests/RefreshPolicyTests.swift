import XCTest
@testable import FollowerCore

final class RefreshPolicyTests: XCTestCase {
    private func account(status: SyncStatus = .ready, nextAllowedAt: Int64? = nil) throws -> Account {
        var account = try Account(provider: .instagram, stableID: "fixture", username: "sample", displayName: "Sample",
            profileURL: URL(string: "https://www.instagram.com/sample/")!, connectedAt: 1)
        account.status = status; account.nextAllowedAt = nextAllowedAt
        return account
    }
    func testManualAndBackgroundRefreshHonorTheExactCooldownBoundary() throws {
        let limited = try account(status: .rateLimited, nextAllowedAt: 60_001)
        for background in [false, true] {
            XCTAssertFalse(RefreshPolicy.canRefresh(limited, now: 60_000, background: background))
            XCTAssertTrue(RefreshPolicy.canRefresh(limited, now: 60_001, background: background))
            XCTAssertTrue(RefreshPolicy.canRefresh(limited, now: 60_002, background: background))
        }
    }
    func testCooldownAppliesEvenWhenAnotherStatusIsDisplayed() throws {
        let pending = try account(status: .offline, nextAllowedAt: 60_001)
        XCTAssertFalse(RefreshPolicy.canRefresh(pending, now: 60_000, background: false))
        XCTAssertFalse(RefreshPolicy.canRefresh(pending, now: 60_000, background: true))
    }
    func testAutomaticRetriesStopButAnExplicitRefreshCanRetryAfterCooldown() throws {
        for status: SyncStatus in [.reauthRequired, .checkRequired, .foregroundOnly] {
            let blocked = try account(status: status)
            XCTAssertFalse(RefreshPolicy.canRefresh(blocked, now: 1, background: true))
            XCTAssertTrue(RefreshPolicy.canRefresh(blocked, now: 1, background: false))
        }
    }
    func testMissingAndElapsedCooldownDoNotBlockAvailableCollection() throws {
        for until: Int64? in [nil, 0, 60_000] {
            let ready = try account(nextAllowedAt: until)
            XCTAssertTrue(RefreshPolicy.canRefresh(ready, now: 60_000, background: false))
            XCTAssertTrue(RefreshPolicy.canRefresh(ready, now: 60_000, background: true))
        }
    }

    func testAProfileFormatFailureCanRecoverAfterItsLocalWaitWithoutReconnecting() throws {
        var waiting = try account(status: .formatChanged)
        waiting.transientRetry = TransientRetryState(failureCount: 1, nextAttemptAt: 900_001)
        XCTAssertFalse(RefreshPolicy.canRefresh(waiting, now: 900_000, background: true))
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 900_001, background: true))
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 1, background: false))
        XCTAssertTrue(RefreshPolicy.canRefresh(try account(status: .formatChanged), now: 1, background: true))
    }

    func testFormatFailuresWaitFifteenMinutesFromCompletionWithoutBlockingAnExplicitRefresh() throws {
        var waiting = try account(status: .formatChanged)
        let retry = RefreshPolicy.nextTransientRetry(waiting, failedAt: 30_000, status: .formatChanged)
        XCTAssertEqual(retry.nextAttemptAt, 930_000)
        waiting.transientRetry = retry
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 30_000, background: false))
        XCTAssertFalse(RefreshPolicy.canRefresh(waiting, now: 929_999, background: true))
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 930_000, background: true))
        XCTAssertEqual(RefreshPolicy.nextTransientRetry(waiting, failedAt: 30_000, status: .formatChanged).nextAttemptAt, 930_000)
    }

    func testTransientRetriesGrowFromOneMinuteToFifteenMinutesAndStayBounded() throws {
        var previous = try account()
        for delay: Int64 in [60_000, 120_000, 240_000, 480_000, 900_000, 900_000] {
            let next = RefreshPolicy.nextTransientRetry(previous, failedAt: 1_000)
            XCTAssertEqual(next.nextAttemptAt, 1_000 + delay)
            previous.transientRetry = next
        }
        XCTAssertEqual(previous.transientRetry?.failureCount, 5)
    }

    func testLocalBackoffOnlyBlocksAutomaticRequestsButServiceCooldownBlocksBoth() throws {
        var waiting = try account(status: .offline)
        waiting.transientRetry = TransientRetryState(failureCount: 1, nextAttemptAt: 60_001)
        XCTAssertFalse(RefreshPolicy.canRefresh(waiting, now: 60_000, background: true))
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 60_001, background: true))
        XCTAssertTrue(RefreshPolicy.canRefresh(waiting, now: 1, background: false))
        waiting.nextAllowedAt = 60_002
        for background in [false, true] {
            XCTAssertFalse(RefreshPolicy.canRefresh(waiting, now: 60_001, background: background))
        }
    }

    func testFailureResponseTimeStartsTheNewDelayAndStoredStateRoundTrips() throws {
        var first = try account()
        first.lastAttemptAt = 1_000
        first.transientRetry = TransientRetryState(failureCount: 1, nextAttemptAt: 61_000)
        let second = RefreshPolicy.nextTransientRetry(first, failedAt: 30_000)
        XCTAssertEqual(second.nextAttemptAt, 150_000)
        first.transientRetry = second
        let decoded = try JSONDecoder().decode(Account.self, from: JSONEncoder().encode(first))
        XCTAssertEqual(decoded.transientRetry, second)
    }

    func testOlderStoredAccountWithoutTransientRetryStillDecodes() throws {
        let data = try JSONEncoder().encode(account())
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("transientRetry"))
        XCTAssertNil(try JSONDecoder().decode(Account.self, from: data).transientRetry)
    }

    func testServerRetryAfterAlsoAppliesToTemporaryServerFailures() {
        XCTAssertEqual(RefreshPolicy.serviceRetryAt(CollectionFailure(.offline, retryAfterSeconds: 120), failedAt: 1_000), 121_000)
        XCTAssertEqual(RefreshPolicy.serviceRetryAt(CollectionFailure(.rateLimited), failedAt: 1_000), 901_000)
        XCTAssertNil(RefreshPolicy.serviceRetryAt(CollectionFailure(.offline), failedAt: 1_000))
        XCTAssertNil(RefreshPolicy.serviceRetryAt(CollectionFailure(.checkRequired, retryAfterSeconds: 120), failedAt: 1_000))
    }

    func testExtremeRetryValuesCannotOverflowIntoAnAlreadyElapsedDeadline() throws {
        XCTAssertEqual(RefreshPolicy.nextTransientRetry(try account(), failedAt: .max - 1).nextAttemptAt, .max)
        XCTAssertEqual(RefreshPolicy.serviceRetryAt(CollectionFailure(.rateLimited, retryAfterSeconds: .max), failedAt: 1_000), .max)
    }
}
