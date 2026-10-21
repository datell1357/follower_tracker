import XCTest
@testable import FollowerCore

final class FacebookPageConnectionRequestTests: XCTestCase {
    private let page = URL(string: "https://www.facebook.com/fixture.page/")!
    private func action(_ request: FacebookPageConnectionRequest, current: String? = nil,
                        authenticated: Bool = true, loading: Bool = false, busy: Bool = false) -> FacebookPageAction {
        request.next(current: current.flatMap(URL.init(string:)) ?? page,
            authenticated: authenticated, loading: loading, busy: busy)
    }
    func testLoginRedirectOverridesStaleCookiesWithoutCreatingAReturnLoop() throws {
        var request = try XCTUnwrap(FacebookPageConnectionRequest(input: page.absoluteString, authenticated: true))
        request.observe(URL(string: "https://www.facebook.com/login/")!)
        XCTAssertEqual(action(request, current: "https://www.facebook.com/checkpoint/"), .wait)
        XCTAssertEqual(action(request, current: "https://www.facebook.com/"), .openTarget)
        request.returnedAfterLogin = true
        request.observe(URL(string: "https://www.facebook.com/checkpoint/")!)
        XCTAssertEqual(action(request, current: "https://www.facebook.com/"), .wait)
    }
    func testSubmittedLinkWaitsForAuthenticationAndReturnsToItsTargetOnce() throws {
        let request = try XCTUnwrap(FacebookPageConnectionRequest(input: page.absoluteString, authenticated: false))
        XCTAssertEqual(action(request, authenticated: false), .wait)
        XCTAssertEqual(action(request), .openTarget, "Reload a page opened before authentication before reading its count")
        for path in ["login/", "login.php", "checkpoint/", "two_step_verification/", "auth_platform/"] {
            XCTAssertEqual(action(request, current: "https://www.facebook.com/" + path), .wait)
        }
        XCTAssertEqual(action(request, current: "https://www.facebook.com/"), .openTarget)
        var returned = request; returned.returnedAfterLogin = true
        XCTAssertEqual(action(returned, current: "https://www.facebook.com/"), .wait)
        XCTAssertEqual(action(returned), .capture)
    }
    func testOnlyTheMatchingTargetCanCaptureOnce() throws {
        let request = try XCTUnwrap(FacebookPageConnectionRequest(input: page.absoluteString, authenticated: true))
        XCTAssertEqual(action(request, current: "https://m.facebook.com/fixture.page?locale=ko_KR"), .capture)
        var attempted = request; attempted.captureAttempted = true
        XCTAssertEqual(action(attempted), .wait)
        XCTAssertEqual(action(request, loading: true), .wait); XCTAssertEqual(action(request, busy: true), .wait)
        XCTAssertEqual(action(request, current: "https://facebook.com/other.page"), .wait)
        let numeric = try XCTUnwrap(FacebookPageConnectionRequest(input: "https://facebook.com/profile.php?id=42", authenticated: true))
        XCTAssertEqual(action(numeric, current: "https://facebook.com/profile.php?id=99"), .wait)
    }
    func testOpaqueShareLinksRequireConfirmationOfTheResolvedDestination() throws {
        let share = URL(string: "https://www.facebook.com/share/fixture123/?mibextid=example")!
        let request = try XCTUnwrap(FacebookPageConnectionRequest(input: share.absoluteString, authenticated: true))
        XCTAssertTrue(request.requiresConfirmation)
        XCTAssertEqual(action(request), .wait)
        XCTAssertEqual(action(request, current: "https://facebook.com/other.page"), .wait)
        XCTAssertEqual(action(request, current: "https://example.test/fixture.page"), .wait)
        XCTAssertEqual(action(request, current: "https://facebook.com/"), .wait)
    }
    func testInvalidAndNonPageLinksCannotStart() {
        XCTAssertEqual(FacebookPageConnectionRequest(input: "www.facebook.com/fixture.page/", authenticated: false)?.target, page)
        for input in ["", "https://facebook.com/", "https://facebook.com/login/", "https://facebook.com/checkpoint/",
            "https://facebook.com/share/p/fixture/", "https://facebook.com/groups/123/", "https://facebook.com/fixture.page/posts/123",
            "https://facebook.com/profile.php?id=42&id=99", "https://example.test/fixture.page", "https://user@facebook.com/fixture.page",
            "https://facebook.com.evil.test/fixture.page", "https://facebook.com/" + String(repeating: "a", count: 17_000)] {
            XCTAssertNil(FacebookPageConnectionRequest(input: input, authenticated: true), String(input.prefix(80)))
        }
    }
}
