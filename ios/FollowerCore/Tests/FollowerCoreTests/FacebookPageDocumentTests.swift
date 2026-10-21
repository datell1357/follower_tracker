import Foundation
import XCTest
@testable import FollowerCore

final class FacebookPageDocumentTests: XCTestCase {
    private let url = URL(string: "https://www.facebook.com/profile.php?id=99")!
    private func session(owner: String = "42", authenticated: Bool = true) -> SavedSession {
        let values = [("c_user", owner), ("xs", authenticated ? "synthetic-page-http" : "")]
        return SavedSession(cookies: values.map { CookieRecord(HTTPCookie(properties: [
            .name: $0.0, .value: $0.1, .domain: ".facebook.com", .path: "/", .secure: "TRUE"
        ])!) }, userAgent: "fixture-agent", expectedID: owner, savedAt: 1)
    }
    private func page() throws -> Account {
        try Account(provider: .facebook, stableID: "99", username: "fixture.page", displayName: "Old Page",
            profileURL: url, connectedAt: 12, accountType: .page, sessionOwnerID: "42")
    }
    private func record(_ count: Any? = 0, id: String = "99", type: String = "Page") -> [String: Any] {
        var value: [String: Any] = ["__typename": type, "id": id, "name": "Fixture Page", "username": "fixture.page"]
        if let count { value["followers_count"] = count }
        return value
    }
    private func script(_ value: Any, attributes: String = #"type="application/json" data-sjs"#) throws -> String {
        "<script \(attributes)>" + String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self) + "</script>"
    }
    private func document(_ value: Any, metadata: String? = nil) throws -> String {
        "<html><head>" + (metadata ?? #"<link rel="canonical" href="https://www.facebook.com/profile.php?id=99">"#) +
            "</head><body>" + (try script(value)) + "</body></html>"
    }
    private func read(_ html: String, current: URL? = nil, expected: Account? = nil, saved: SavedSession? = nil) throws -> (Account, MetricSnapshot) {
        try FacebookPageDocument.observation(html, url: current ?? url, expected: expected ?? page(), session: saved ?? session(), now: 100)
    }
    private func assertFailure(_ html: String, status: SyncStatus? = nil, current: URL? = nil, saved: SavedSession? = nil) {
        XCTAssertThrowsError(try read(html, current: current, saved: saved)) { error in
            if let status { XCTAssertEqual((error as? CollectionFailure)?.status, status) }
        }
    }

    func testAnExactNativePageObservationPreservesIdentityHistoryAndUnverifiedBackground() throws {
        let (page, metric) = try read(document(["data": ["page": record()]]))
        XCTAssertEqual(page.id, "FACEBOOK:PAGE:99"); XCTAssertEqual(page.authenticationOwnerID, "42")
        XCTAssertEqual(page.connectedAt, 12); XCTAssertEqual(page.displayName, "Fixture Page"); XCTAssertEqual(page.status, .ready)
        XCTAssertEqual(page.capabilities.count, .observed); XCTAssertEqual(page.capabilities.background, .unverified)
        XCTAssertEqual(page.capabilities.followers, .unavailable); XCTAssertEqual(page.capabilities.following, .unavailable)
        XCTAssertTrue(RefreshPolicy.canRefresh(page, now: 100, background: true))
        XCTAssertEqual(metric.followers, 0); XCTAssertNil(metric.following); XCTAssertEqual(metric.observedAt, 100)
        XCTAssertEqual(metric.source, "facebook-page-session")
    }
    func testDelegatedPagesCanUseAnotherFragmentOfTheSamePublicProfile() throws {
        var proof = record(nil, type: "User"); proof["delegate_page"] = ["id": "88"]
        let count: [String: Any] = ["__typename": "User", "id": "99", "follower_count": 23]
        let result = try read(document(["proof": proof, "count": count]))
        XCTAssertEqual(result.0.stableID, "99"); XCTAssertEqual(result.1.followers, 23)
    }
    func testNamedFinalURLsMustBindTheExpectedPageRatherThanRecommendations() throws {
        var own = record(17); own["url"] = "https://www.facebook.com/fixture.page/"
        let metadata = #"<meta property="og:url" content="https://www.facebook.com/fixture.page/">"#
        let current = URL(string: "https://m.facebook.com/fixture.page/")!
        let html = try document([own, record(999, id: "88")], metadata: metadata)
        assertFailure(html, status: .checkRequired, current: current) // Both fragments claim the current username.
        var unrelated = record(999, id: "88"); unrelated["username"] = "other.page"
        XCTAssertEqual(try read(document([own, unrelated], metadata: metadata), current: current).1.followers, 17)
        assertFailure(try document([record(999, id: "88")], metadata: metadata), current: current)
    }
    func testNamedPagesCanUseSparseFragmentsOnlyAfterTheirIdentityIsBound() throws {
        let proof = record(nil)
        let sparse: [String: Any] = ["__typename": "Page", "id": "99", "followers_count": 23]
        let metadata = #"<link rel="canonical" href="https://www.facebook.com/fixture.page/">"#
        let current = URL(string: "https://www.facebook.com/fixture.page/")!
        for fragments in [[proof, sparse], [sparse, proof]] {
            XCTAssertEqual(try read(document(fragments, metadata: metadata), current: current).1.followers, 23)
        }
        assertFailure(try document(sparse, metadata: metadata), current: current)
        var contradictory = sparse; contradictory["username"] = "other.page"
        assertFailure(try document([proof, contradictory], metadata: metadata), status: .checkRequired, current: current)
    }
    func testAuthenticationOwnerAndArchivedConnectionAreCheckedBeforeReading() throws {
        let html = try document(record())
        assertFailure(html, status: .reauthRequired, saved: session(authenticated: false))
        assertFailure(html, status: .checkRequired, saved: session(owner: "43"))
        var archived = try page(); archived.loggedOut = true
        XCTAssertThrowsError(try read(html, expected: archived))
    }
    func testCanonicalMetadataAndHTMLCharacterReferencesCannotChangeTheTarget() throws {
        for metadata in [
            #"<link href="https://www.facebook.com/profile.php?id=88" rel="canonical">"#,
            #"<meta property="al:android:url" content="fb://profile/88">"#,
            #"<meta property="fb:page_id" content="&#56;&#56;">"#,
            #"<link rel="canonical" href="https://facebook.com.example.test/profile.php?id=99">"#,
            #"<link rel="canonical" href="https://user@facebook.com/profile.php?id=99">"#
        ] { assertFailure(try document(record(), metadata: metadata), status: .checkRequired) }
        let valid = #"<LINK href='https://www.facebook.com/profile.php?id=99&amp;locale=ko_KR' REL='canonical'><meta property='fb:page_id' content='&#x39;&#57;'>"#
        XCTAssertEqual(try read(document(record(), metadata: valid)).1.followers, 0)
        for value in ["https://example.test/fixture.page/", "https://user@www.facebook.com/fixture.page/", "http://www.facebook.com/fixture.page/"] {
            var invalid = record(); invalid["url"] = value
            assertFailure(try document(invalid), status: .checkRequired)
        }
    }
    func testPersonalProfilesAndUnboundRecommendationsAreNeverNativePageObservations() throws {
        assertFailure(try document(record(9, type: "User")))
        var unrelated = record(9, id: "88"); unrelated["username"] = "other.page"
        assertFailure(try document(unrelated))
        assertFailure(try document([record(9), record(9, type: "User")]), status: .checkRequired)
        for current in ["https://www.facebook.com/login.php", "https://www.facebook.com/checkpoint/",
                        "https://www.facebook.com/fixture.page/posts/99", "http://www.facebook.com/profile.php?id=99"] {
            assertFailure(try document(record()), current: URL(string: current)!)
        }
    }
    func testInvalidAndConflictingCountsNeverCreateZeroOrReplacePreciseCounts() throws {
        for count: Any in [true, -1, 1.5, "unknown", 9_007_199_254_740_992 as Int64] {
            assertFailure(try document(record(count)), status: .formatChanged)
        }
        assertFailure(try document([record(1), record(2)]), status: .formatChanged)
        var conflicting = record(1); conflicting["follower_count"] = 2
        assertFailure(try document(conflicting), status: .formatChanged)
        for count: Any in ["1.2K", NSNull()] { assertFailure(try document(record(count)), status: .foregroundOnly) }
        XCTAssertEqual(try read(document(record("1,234"))).1.followers, 1_234)
        var oversized = record(); oversized["username"] = String(repeating: "x", count: 257)
        assertFailure(try document(oversized), status: .formatChanged)
    }
    func testOnlyTheBoundPagesOfficialFollowerLabelCanSupplyAnExactCount() throws {
        for label: Any in ["12,345 followers", ["text": "팔로워 12,345명"]] {
            var value = record(nil, type: "User"); value["delegate_page"] = ["id": "88"]
            value["profile_social_context"] = ["content": [["text": label, "uri": "https://www.facebook.com/profile.php?id=99&sk=followers"]]]
            XCTAssertEqual(try read(document(value)).1.followers, 12_345)
        }
        var named = record(nil); named["url"] = "https://www.facebook.com/fixture.page/"
        named["profile_social_context"] = ["content": [["text": "12,345 followers", "uri": "/fixture.page/followers"]]]
        XCTAssertEqual(try read(document(named)).1.followers, 12_345)
        for link in ["https://www.facebook.com/profile.php?id=88&sk=followers", "https://example.test/fixture.page/followers",
                     "https://user@www.facebook.com/profile.php?id=99&sk=followers", "https://www.facebook.com/profile.php?id=99&id=88&sk=followers", "http://["] {
            var value = record(nil)
            value["profile_social_context"] = ["content": [["text": "999 followers", "uri": link]]]
            assertFailure(try document(value), status: .foregroundOnly)
        }
    }
    func testRoundedLabelsDoNotHideASeparateExactJSONCountOrBecomeIntegers() throws {
        var value = record(nil)
        value["profile_social_context"] = ["content": [["text": "팔로워 1.2천명", "uri": "/profile.php?id=99&sk=followers"]]]
        assertFailure(try document(value), status: .foregroundOnly)
        value["followers_count"] = 1_234
        XCTAssertEqual(try read(document(value)).1.followers, 1_234)
    }
    func testCommentsTemplatesExecutableScriptsAndFormValuesAreNotJSONSources() throws {
        let payload = try script(record(999))
        let html = #"<link rel="canonical" href="/profile.php?id=99">"# +
            "<!--" + payload + "--><template>" + payload + "</template><style>/*" + payload + "*/</style>" +
            ["textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript"].map { "<\($0)>" + payload + "</\($0)>" }.joined() +
            "<script>" + String(decoding: try JSONSerialization.data(withJSONObject: record(999)), as: UTF8.self) + "</script>" +
            #"<input type='password' value='<script type="application/json">{"__typename":"Page","id":"99","name":"Fake","followers_count":999}</script>'>"#
        assertFailure(html, status: .foregroundOnly)
        assertFailure(#"<link rel="canonical" href="/profile.php?id=99"><plaintext>"# + payload, status: .foregroundOnly)
        XCTAssertEqual(try read(html + script(record(3), attributes: #"data-note="a > b" data-sjs TYPE='application/json'"#)).1.followers, 3)
    }
    func testMalformedAndTruncatedJSONAndConflictingAttributesFailClosed() throws {
        let good = try document(record())
        for suffix in [#"<script type="application/json">{"data":"#, #"<script type="application/json">{bad}</script>"#,
                       #"<meta property="fb:page_id" content="99" content="88">"#, "<template><template></template></template>"] {
            assertFailure(good + suffix, status: .formatChanged)
        }
    }
    func testResponseJSONNodeDepthAndScriptBudgetsCannotProducePartialSuccess() throws {
        let good = try document(record())
        assertFailure(good + String(repeating: " ", count: 8 * 1024 * 1024), status: .formatChanged)
        assertFailure(good + (try script(["padding": String(repeating: "x", count: 4 * 1024 * 1024)])), status: .formatChanged)
        assertFailure(good + String(repeating: #"<script type="application/json">{}</script>"#, count: 256), status: .formatChanged)
        var deep: [String: Any] = record()
        for _ in 0..<35 { deep = ["nested": deep] }
        assertFailure(try document(deep), status: .formatChanged)
        let wide: [String: Any] = ["items": Array(repeating: [String: Any](), count: 100_001)]
        assertFailure(good + (try script(wide)), status: .formatChanged)
    }
    func testNativeRedirectsStayOnOfficialPageRoutesAndTheOriginalNumericID() {
        XCTAssertEqual(FacebookPageDocument.redirect(url, location: "/fixture.page/", pageID: "99")?.absoluteString, "https://www.facebook.com/fixture.page/")
        XCTAssertEqual(FacebookPageDocument.redirect(url, location: "https://m.facebook.com/profile.php?id=99", pageID: "99")?.host, "m.facebook.com")
        for value in ["http://www.facebook.com/profile.php?id=99", "https://facebook.com.example.test/profile.php?id=99",
                      "https://user:password@facebook.com/profile.php?id=99", "https://facebook.com:444/profile.php?id=99",
                      "/login.php", "/checkpoint/", "/profile.php?id=88", "/profile.php?id=99&id=88", "/share/fixture/", "/l.php?u=https://example.test/", "/fixture.page/#token"] {
            XCTAssertNil(FacebookPageDocument.redirect(url, location: value, pageID: "99"), value)
        }
        XCTAssertNil(FacebookPageDocument.redirect(url, location: "/fixture.page/", pageID: "not-id"))
    }
    func testDocumentGuardsExpireOnNavigationAndCannotReviveAfterClosing() {
        let fence = DocumentObservationFence(), first = fence.capture()
        XCTAssertTrue(first())
        fence.advance()
        XCTAssertFalse(first())
        let second = fence.capture()
        XCTAssertTrue(second())
        fence.invalidate(); fence.advance()
        XCTAssertFalse(second()); XCTAssertFalse(fence.capture()())
        fence.activate()
        XCTAssertFalse(first()); XCTAssertFalse(second()); XCTAssertTrue(fence.capture()())
    }
    func testDocumentGuardsDoNotRetainAClosedBrowser() {
        var fence: DocumentObservationFence? = DocumentObservationFence()
        let current = fence!.capture()
        XCTAssertTrue(current())
        fence = nil
        XCTAssertFalse(current())
    }
}
