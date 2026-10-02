import XCTest
import UIKit
import WebKit
import FollowerCore
@testable import FollowerTracker

/// Runs in the empty QA simulator. Fixtures never reach the vault or account database.
@MainActor
final class AutoConnectionRuntimeTests: XCTestCase {
    private var browser: LoginBrowser?
    private var window: UIWindow?
    private let profile = URL(string: "https://www.instagram.com/sample/")!
    private func session() -> SavedSession {
        let cookies = [("sessionid", "synthetic-fixture"), ("ds_user_id", "42")].map { name, value in
            CookieRecord(HTTPCookie(properties: [.name: name, .value: value, .domain: ".instagram.com", .path: "/", .secure: "TRUE"])!)
        }
        return SavedSession(cookies: cookies, userAgent: "synthetic-agent", expectedID: nil, savedAt: 1)
    }
    private func html(count: Int? = 0, late: Bool = false) -> String {
        let owner = count.map { "\"follower_count\":\($0)," } ?? ""
        return """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
        <script type="application/json">{"user":{"pk":"42","username":"sample",\(owner)"following_count":2}}</script>
        <input name="password"><script>
        Object.defineProperty(document.querySelector('input'), 'value', {get() {throw new Error('Form values must not be read')}});
        globalThis.fixtureRequests = 0;
        globalThis.fetch = async function() {
          fixtureRequests++;
          \(late ? "setTimeout(() => { const link = document.createElement('a'); link.href = '/sample/followers/'; link.textContent = '7 followers'; document.body.append(link); }, 900);" : "")
          return {ok:false,status:429,headers:{get:()=>'300'}};
        };
        </script></body></html>
        """
    }
    private func start(html: String, authenticated: Bool = true, url: URL? = nil,
                       onObservation: @escaping @MainActor (SavedSession, String) async throws -> Void,
                       completed: @escaping @MainActor () -> Void = {}) -> LoginBrowser {
        let saved = authenticated ? session() : nil
        let page = url ?? profile
        let browser = LoginBrowser(provider: .instagram, restoreSession: { saved }, loadPage: { web, _ in web.loadHTMLString(html, baseURL: page) })
        self.browser = browser
        let view = UIViewController()
        view.loadViewIfNeeded()
        browser.webView.frame = CGRect(x: 0, y: 0, width: 390, height: 650)
        view.view.addSubview(browser.webView)
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            let window = UIWindow(windowScene: scene); window.rootViewController = view
            window.makeKeyAndVisible(); self.window = window
        }
        browser.open(onObservation: onObservation, completed: completed)
        return browser
    }
    override func tearDown() {
        browser?.cancel(); browser?.webView.removeFromSuperview(); browser = nil
        window?.isHidden = true; window = nil
        super.tearDown()
    }
    private func waitUntil(_ condition: @escaping @MainActor () -> Bool, timeout: TimeInterval = 10) async throws {
        let limit = Date().addingTimeInterval(timeout)
        while !condition() && Date() < limit { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertTrue(condition(), "Expected WebKit state did not arrive")
    }
    private func requests(_ browser: LoginBrowser) async throws -> Int {
        (try await browser.webView.evaluateJavaScript("globalThis.fixtureRequests") as? NSNumber)?.intValue ?? -1
    }

    func testOwnExactZeroConnectsWithoutAConfirmationOrNetworkRequest() async throws {
        let connected = expectation(description: "Automatic connection")
        var observations = 0
        let browser = start(html: html(), onObservation: { session, payload in
            let (_, metric) = try ResponseParser.capturedProfile(.instagram, payload: payload, session: session, expected: nil, now: 1)
            XCTAssertEqual(metric.followers, 0); observations += 1
        }, completed: { connected.fulfill() })
        await fulfillment(of: [connected], timeout: 15)
        XCTAssertEqual(observations, 1)
        let requestCount = try await requests(browser)
        XCTAssertEqual(requestCount, 0)
    }
    func testCookieCandidateCannotConnectWhileTheOfficialLoginPageIsOpen() async throws {
        let connected = expectation(description: "Must stay on login"); connected.isInverted = true
        let browser = start(html: html(), url: Provider.instagram.loginURL, onObservation: { _, _ in connected.fulfill() })
        try await waitUntil { !browser.loading }
        await fulfillment(of: [connected], timeout: 2)
        let requestCount = try await requests(browser)
        XCTAssertEqual(requestCount, 0)
    }
    func testProfileJSONWithoutAnAuthenticatedSessionCannotConnect() async throws {
        let connected = expectation(description: "Must require authentication"); connected.isInverted = true
        let browser = start(html: html(), authenticated: false, onObservation: { _, _ in connected.fulfill() })
        try await waitUntil { !browser.loading }
        await fulfillment(of: [connected], timeout: 2)
        XCTAssertFalse(browser.sessionReady)
        let requestCount = try await requests(browser)
        XCTAssertEqual(requestCount, 0)
    }
    func testRateLimitKeepsReadingLateDOMWithoutAnotherRequest() async throws {
        let connected = expectation(description: "Late DOM observation")
        let browser = start(html: html(count: nil, late: true), onObservation: { session, payload in
            let (account, metric) = try ResponseParser.capturedProfile(.instagram, payload: payload, session: session, expected: nil, now: 1)
            XCTAssertEqual(metric.followers, 7); XCTAssertEqual(metric.source, "instagram-webview-dom")
            XCTAssertEqual(account.status, .foregroundOnly)
            connected.fulfill()
        })
        await fulfillment(of: [connected], timeout: 15)
        let requestCount = try await requests(browser)
        XCTAssertEqual(requestCount, 1)
    }
    func testClosingTheBrowserCancelsPendingAutomaticConnection() async throws {
        let connected = expectation(description: "Cancelled observation"); connected.isInverted = true
        let browser = start(html: html(count: nil, late: true), onObservation: { _, _ in connected.fulfill() })
        try await waitUntil { browser.notice?.contains("잠시 제한") == true }
        XCTAssertFalse(browser.canRetry)
        browser.requestRetry()
        XCTAssertFalse(browser.canRetry)
        browser.cancel()
        await fulfillment(of: [connected], timeout: 2)
    }
}
