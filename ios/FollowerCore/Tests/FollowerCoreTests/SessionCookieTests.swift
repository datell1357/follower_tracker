import Foundation
import XCTest
@testable import FollowerCore

final class SessionCookieTests: XCTestCase {
    private func cookie(domain: String = ".instagram.com", path: String = "/", expires: Date? = nil) -> CookieRecord {
        var values: [HTTPCookiePropertyKey: Any] = [.name: "sessionid", .value: "synthetic-fixture", .domain: domain, .path: path, .secure: "TRUE"]
        if let expires { values[.expires] = expires }
        return CookieRecord(HTTPCookie(properties: values)!)
    }
    func testCookieDomainDoesNotMatchLookalikes() {
        let record = cookie()
        XCTAssertTrue(record.matches(URL(string: "https://www.instagram.com/api/")!))
        XCTAssertFalse(record.matches(URL(string: "https://instagram.com.example.test/api/")!))
        XCTAssertFalse(record.matches(URL(string: "https://evilinstagram.com/api/")!))
    }
    func testHostOnlyCookieIsNotSentToSiblingSubdomain() {
        let record = cookie(domain: "www.instagram.com")
        XCTAssertTrue(record.matches(URL(string: "https://www.instagram.com/api/")!))
        XCTAssertFalse(record.matches(URL(string: "https://other.www.instagram.com/api/")!))
    }
    func testPathBoundaryAndHTTPSAreRequired() {
        let record = cookie(path: "/api")
        XCTAssertTrue(record.matches(URL(string: "https://www.instagram.com/api/v1/")!))
        XCTAssertFalse(record.matches(URL(string: "https://www.instagram.com/apiculture")!))
        XCTAssertFalse(record.matches(URL(string: "http://www.instagram.com/api/")!))
    }
    func testExpiredCookieDoesNotAuthenticateSession() {
        let record = cookie(expires: Date(timeIntervalSince1970: 1))
        XCTAssertFalse(record.matches(Provider.instagram.loginURL))
        let session = SavedSession(cookies: [record], userAgent: "fixture-agent", expectedID: "42", savedAt: 1)
        XCTAssertFalse(session.authenticated(.instagram))
    }
    func testSavedCookieRoundTripPreservesScopeAndValue() throws {
        let record = cookie(path: "/api")
        let encoded = try JSONEncoder().encode(record)
        let restored = try JSONDecoder().decode(CookieRecord.self, from: encoded)
        XCTAssertEqual("/api", restored.cookie?.path)
        XCTAssertEqual("synthetic-fixture", restored.cookie?.value)
        XCTAssertEqual(record.secure, restored.cookie?.isSecure)
    }
}
