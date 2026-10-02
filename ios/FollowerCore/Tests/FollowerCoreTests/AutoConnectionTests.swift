import Foundation
import XCTest
@testable import FollowerCore

final class AutoConnectionTests: XCTestCase {
    private func session(id: String = "42", token: String = "synthetic-fixture") -> SavedSession {
        let cookies = [("ds_user_id", id), ("sessionid", token)].map { name, value in
            CookieRecord(HTTPCookie(properties: [.name: name, .value: value, .domain: ".instagram.com", .path: "/", .secure: "TRUE"])!)
        }
        return SavedSession(cookies: cookies, userAgent: "synthetic-agent", expectedID: nil, savedAt: 1)
    }
    private var fixture: [String: Any] { ["provider": "INSTAGRAM", "stableId": "42", "username": "sample",
        "displayName": "Sample", "profileURL": "https://www.instagram.com/sample/", "followers": 0,
        "following": 2, "precision": "EXACT", "source": "instagram-webview-dom"] }
    private func capture(_ changes: [String: Any] = [:], saved: SavedSession? = nil, expected: Account? = nil) throws -> (Account, MetricSnapshot) {
        let root = fixture.merging(changes) { _, new in new }
        let data = try JSONSerialization.data(withJSONObject: root)
        return try ResponseParser.capturedProfile(.instagram, payload: String(decoding: data, as: UTF8.self), session: saved ?? session(), expected: expected, now: 100)
    }

    func testLoginChallengeLoadingAndBusyPagesCannotAutoConnect() {
        let profile = URL(string: "https://www.instagram.com/sample/")!
        XCTAssertTrue(canAutoConnect(.instagram, url: profile, authenticated: true, loading: false, busy: false))
        for path in ["/accounts/login/", "/challenge/", "/checkpoint/", "/two_factor/", "/two-factor/"] {
            XCTAssertFalse(canAutoConnect(.instagram, url: URL(string: "https://www.instagram.com" + path)!, authenticated: true, loading: false, busy: false))
        }
        XCTAssertFalse(canAutoConnect(.instagram, url: profile, authenticated: false, loading: false, busy: false))
        XCTAssertFalse(canAutoConnect(.instagram, url: profile, authenticated: true, loading: true, busy: false))
        XCTAssertFalse(canAutoConnect(.instagram, url: profile, authenticated: true, loading: false, busy: true))
        XCTAssertFalse(canAutoConnect(.instagram, url: URL(string: "https://instagram.com.example.test/sample/")!, authenticated: true, loading: false, busy: false))
    }
    func testDuplicateRequestsAndRateLimitCannotBeBypassedByNavigationOrRetry() {
        var policy = AutoConnectionPolicy()
        XCTAssertTrue(policy.begin(pageKey: "one", now: 1_000))
        XCTAssertFalse(policy.begin(pageKey: "one", now: 2_000))
        policy.failed(CollectionFailure(.rateLimited, retryAfterSeconds: 120), now: 2_000)
        XCTAssertFalse(policy.begin(pageKey: "another-page", now: 3_000))
        policy.requestRetry()
        XCTAssertFalse(policy.begin(pageKey: "one", now: 121_999))
        XCTAssertTrue(policy.begin(pageKey: "one", now: 122_000))
    }
    func testOfflineRetriesAreBoundedAndExplicitRetryRestartsTheBudget() {
        var policy = AutoConnectionPolicy()
        for index in 0..<3 {
            let now = Int64(index) * 30_000
            XCTAssertTrue(policy.begin(pageKey: "one", now: now))
            policy.failed(CollectionFailure(.offline), now: now)
        }
        XCTAssertFalse(policy.begin(pageKey: "one", now: 90_000))
        policy.requestRetry()
        XCTAssertTrue(policy.begin(pageKey: "one", now: 90_000))
    }
    func testPayloadErrorsKeepRateLimitSeparateFromAuthentication() throws {
        let rate = ResponseParser.webCaptureFailure(["error": "http", "status": 429, "retryAfterSeconds": 1])!
        XCTAssertEqual(rate.status, .rateLimited); XCTAssertEqual(rate.retryAfterSeconds, 60)
        XCTAssertEqual(ResponseParser.webCaptureFailure(["error": "http", "status": 403])?.status, .checkRequired)
        XCTAssertEqual(ResponseParser.webCaptureFailure(["error": "http", "status": 401])?.status, .reauthRequired)
        XCTAssertEqual(ResponseParser.webCaptureFailure(["error": "http", "status": true])?.status, .formatChanged)
        XCTAssertThrowsError(try capture(["error": "offline"])) { XCTAssertEqual(($0 as? CollectionFailure)?.status, .offline) }
        XCTAssertFalse(connectionFailureMessage(CollectionFailure(.rateLimited)).contains("로그인 페이지에서 계정을 확인"))
    }
    func testZeroIsAValidObservationButDOMDoesNotProveBackgroundSupport() throws {
        let (account, metric) = try capture()
        XCTAssertEqual(metric.followers, 0); XCTAssertEqual(metric.observedAt, 100)
        XCTAssertEqual(account.status, .foregroundOnly)
        XCTAssertEqual(account.capabilities.background, .foregroundOnly)
        let (sessionAccount, _) = try capture(["source": "instagram-webview-session"])
        XCTAssertEqual(sessionAccount.status, .ready)
        XCTAssertEqual(sessionAccount.capabilities.background, .unverified)
    }
    func testUnauthenticatedAndChangedIdentityCannotSaveProfileObservations() {
        XCTAssertThrowsError(try capture(saved: session(token: ""))) { XCTAssertEqual(($0 as? CollectionFailure)?.status, .reauthRequired) }
        XCTAssertThrowsError(try capture(["stableId": "other"])) { XCTAssertEqual(($0 as? CollectionFailure)?.status, .checkRequired) }
        XCTAssertThrowsError(try capture(["provider": "TIKTOK"])) { XCTAssertEqual(($0 as? CollectionFailure)?.status, .checkRequired) }
        var saved = session(); saved.expectedID = "previous-owner"
        XCTAssertThrowsError(try capture(saved: saved)) { XCTAssertEqual(($0 as? CollectionFailure)?.status, .checkRequired) }
    }
    func testExistingConnectionIdentityAndDateArePreserved() throws {
        let expected = try Account(provider: .instagram, stableID: "42", username: "old_name", displayName: "Old",
            profileURL: URL(string: "https://www.instagram.com/old_name/")!, connectedAt: 12)
        XCTAssertEqual(try capture(expected: expected).0.connectedAt, 12)
        let other = try Account(provider: .instagram, stableID: "43", username: "other", displayName: "Other",
            profileURL: URL(string: "https://www.instagram.com/other/")!, connectedAt: 12)
        XCTAssertThrowsError(try capture(expected: other))
    }
    func testRoundedMissingInvalidAndBooleanCountsAreRejected() {
        for count: Any in [true, -1, 1.2, "1.2K", NSNull(), 9_007_199_254_740_992 as Int64] {
            XCTAssertThrowsError(try capture(["followers": count]))
        }
        XCTAssertThrowsError(try capture(["following": true]))
        XCTAssertThrowsError(try capture(["precision": "ROUNDED"]))
        XCTAssertThrowsError(try capture(["source": "unverified-source"]))
    }
    func testOfficialLoginAliasesDoNotExpandCookieDestinations() {
        let facebook = URL(string: "https://www.facebook.com/login/")!
        XCTAssertTrue(Provider.instagram.allowsLogin(facebook)); XCTAssertFalse(Provider.instagram.allows(facebook))
        let twitter = URL(string: "https://twitter.com/i/flow/login")!
        XCTAssertTrue(Provider.x.allowsLogin(twitter)); XCTAssertFalse(Provider.x.allows(twitter))
        for text in ["https://twitter.com.example.test/", "http://twitter.com/", "https://twitter.com:444/", "https://user:password@twitter.com/"] {
            XCTAssertFalse(Provider.x.allowsLogin(URL(string: text)!))
        }
    }
}
