import XCTest
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import FollowerCore

final class FacebookPageTests: XCTestCase {
    private var session: SavedSession {
        let cookies = [("c_user", "42"), ("xs", "synthetic-page-session")].map { name, value in
            CookieRecord(HTTPCookie(properties: [.name: name, .value: value, .domain: ".facebook.com", .path: "/", .secure: "TRUE"])!)
        }
        return SavedSession(cookies: cookies, userAgent: "fixture", expectedID: "42", savedAt: 1)
    }
    private let payload = """
        {"provider":"FACEBOOK","accountType":"PAGE","stableId":"99","sessionOwnerId":"42","username":"fixture.page",
         "displayName":"Fixture Page","profileURL":"https://www.facebook.com/profile.php?id=99","followers":0,"following":900,
         "precision":"EXACT","source":"facebook-webview-page"}
        """
    private func changed(_ key: String, _ value: Any) throws -> String {
        var root = try ResponseParser.objectBody(payload); root[key] = value
        return String(data: try JSONSerialization.data(withJSONObject: root), encoding: .utf8)!
    }
    func testPageIdentityAndExactZeroStaySeparateFromTheLoginOwner() throws {
        let (page, metric) = try ResponseParser.capturedFacebookPage(payload, session: session, expected: nil, now: 10)
        XCTAssertEqual(page.id, "FACEBOOK:PAGE:99"); XCTAssertEqual(page.authenticationOwnerID, "42")
        XCTAssertEqual(page.connectionTitle, "Facebook 페이지"); XCTAssertEqual(page.identityLabel, "Fixture Page")
        XCTAssertEqual(metric.accountKey, page.id); XCTAssertEqual(metric.followers, 0); XCTAssertNil(metric.following)
        XCTAssertEqual(page.status, .foregroundOnly); XCTAssertEqual(page.capabilities.followers, .unavailable)
        XCTAssertFalse(RefreshPolicy.canRefresh(page, now: 10, background: true))
        XCTAssertEqual(try JSONDecoder().decode(Account.self, from: JSONEncoder().encode(page)), page)
        let updated = try ResponseParser.capturedFacebookPage(payload, session: session, expected: page, now: 20)
        XCTAssertEqual(updated.0.connectedAt, 10)
        XCTAssertThrowsError(try ResponseParser.capturedProfile(.facebook, payload: payload, session: session, expected: nil, now: 20))
    }
    func testAmbiguousTargetsSourcesOwnersAndCountsAreRejected() throws {
        for (key, value) in [("provider", "INSTAGRAM"), ("accountType", "PROFILE"), ("sessionOwnerId", "77"),
            ("stableId", "42"), ("stableId", "not-id"), ("source", "facebook-webview-profile"), ("precision", "ROUNDED")] {
            XCTAssertThrowsError(try ResponseParser.capturedFacebookPage(changed(key, value), session: session, expected: nil, now: 10))
        }
        let invalidCounts: [Any] = [true, -1, 1.5, "1.2K", NSNull(), 9_007_199_254_740_992 as Int64]
        for count in invalidCounts {
            XCTAssertThrowsError(try ResponseParser.capturedFacebookPage(changed("followers", count), session: session, expected: nil, now: 10))
        }
        for url in ["http://www.facebook.com/profile.php?id=99", "https://facebook.com.example.test/profile.php?id=99",
            "https://user@www.facebook.com/profile.php?id=99", "https://www.facebook.com:8443/profile.php?id=99",
            "https://www.facebook.com/profile.php?id=88", "https://www.facebook.com/profile.php?id=99&id=88", "https://www.facebook.com/fixture.page"] {
            XCTAssertThrowsError(try ResponseParser.capturedFacebookPage(changed("profileURL", url), session: session, expected: nil, now: 10))
        }
        let expected = try ResponseParser.capturedFacebookPage(payload, session: session, expected: nil, now: 10).0
        XCTAssertThrowsError(try ResponseParser.capturedFacebookPage(changed("stableId", "88"), session: session, expected: expected, now: 20))
    }
    func testLegacyAccountsKeepTheirKeysAndOnlyPersonalProfilesConflict() throws {
        let data = Data(#"{"provider":"FACEBOOK","stableID":"42","username":"fixture.owner","displayName":"Fixture Owner","profileURL":"https://www.facebook.com/profile.php?id=42","connectedAt":1}"#.utf8)
        let personal = try JSONDecoder().decode(Account.self, from: data)
        XCTAssertFalse(personal.loggedOut)
        let page = try ResponseParser.capturedFacebookPage(payload, session: session, expected: nil, now: 10).0
        XCTAssertEqual(personal.id, "FACEBOOK:42"); XCTAssertEqual(personal.accountType, .profile)
        XCTAssertEqual(personal.authenticationOwnerID, "42"); XCTAssertNil(personal.sessionOwnerID)
        XCTAssertFalse(personal.conflicts(with: page)); XCTAssertFalse(page.conflicts(with: personal))
        let other = try Account(provider: .facebook, stableID: "77", username: "other", displayName: "Other", profileURL: personal.profileURL, connectedAt: 20)
        XCTAssertTrue(personal.conflicts(with: other))
    }
    func testPageLinkInputAndDesktopSelectionNeverAcceptOtherOriginsOrAuthenticationRoutes() throws {
        XCTAssertEqual(FacebookPageAddress.input("http://facebook.com/fixture.page?locale=ko_KR")?.absoluteString, "https://facebook.com/fixture.page?locale=ko_KR")
        XCTAssertTrue(FacebookPageAddress.isTarget(URL(string: "https://www.facebook.com/profile.php?id=99")!))
        XCTAssertTrue(FacebookPageAddress.isTarget(URL(string: "https://m.facebook.com/fixture.page/")!))
        for text in ["https://facebook.com.example.test/page", "https://user@facebook.com/page", "https://facebook.com:8443/page", "file:///facebook.com/page"] {
            XCTAssertNil(FacebookPageAddress.input(text))
        }
        for text in ["https://www.facebook.com/login.php", "https://www.facebook.com/checkpoint/", "https://www.facebook.com/two_step_verification/",
            "https://www.facebook.com/profile.php?id=99&id=88", "https://www.facebook.com/fixture.page/posts/99", "https://www.facebook.com/"] {
            XCTAssertFalse(FacebookPageAddress.isTarget(URL(string: text)!))
        }
    }
}
