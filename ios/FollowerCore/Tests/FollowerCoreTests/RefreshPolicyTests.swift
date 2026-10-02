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
        for status: SyncStatus in [.reauthRequired, .checkRequired, .formatChanged, .foregroundOnly] {
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
}
