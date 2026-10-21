import Foundation
import XCTest
import FollowerCore
@testable import FollowerTracker

private struct PageHTTPReply: Sendable {
    var status = 200
    var headers: [String: String] = ["Content-Type": "text/html"]
    var body = Data()
    var delay: TimeInterval = 0
    var bodyDelay: TimeInterval = 0
    var responseURL: URL?
}

/// Every request is intercepted, including unexpected URLs. These fixtures cannot reach a real SNS.
private final class PageHTTPProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: @Sendable (URLRequest) -> PageHTTPReply] = [:]
    private let stateLock = NSLock()
    private var stopped = false
    private var pending: DispatchWorkItem?
    static func register(_ id: String, handler: @escaping @Sendable (URLRequest) -> PageHTTPReply) {
        lock.lock(); defer { lock.unlock() }; handlers[id] = handler
    }
    static func unregister(_ id: String) { lock.lock(); defer { lock.unlock() }; handlers.removeValue(forKey: id) }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let handler = request.value(forHTTPHeaderField: "X-Follower-Fixture").flatMap { Self.handlers[$0] }
        Self.lock.unlock()
        guard let handler else { client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return }
        let reply = handler(request)
        let work = DispatchWorkItem { [weak self] in self?.deliver(reply) }
        stateLock.lock(); pending = work; stateLock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay, execute: work)
    }
    private var isStopped: Bool { stateLock.lock(); defer { stateLock.unlock() }; return stopped }
    private func deliver(_ reply: PageHTTPReply) {
        guard !isStopped, let url = reply.responseURL ?? request.url,
              let response = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: reply.headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if reply.bodyDelay > 0 {
            let work = DispatchWorkItem { [weak self] in self?.deliverBody(reply.body) }
            stateLock.lock()
            guard !stopped else { stateLock.unlock(); return }
            pending = work; stateLock.unlock()
            DispatchQueue.global().asyncAfter(deadline: .now() + reply.bodyDelay, execute: work)
        } else { deliverBody(reply.body) }
    }
    private func deliverBody(_ body: Data) {
        if !isStopped && !body.isEmpty { client?.urlProtocol(self, didLoad: body) }
        if !isStopped { client?.urlProtocolDidFinishLoading(self) }
    }
    override func stopLoading() {
        stateLock.lock(); stopped = true; pending?.cancel(); stateLock.unlock()
    }
}

private final class PageHTTPFixture: @unchecked Sendable {
    enum CookieResult { case keep, changeOwner, changeVersion, changeDocument, storageFailure }
    let id: String
    let documentFence = DocumentObservationFence()
    let transport: URLSession
    private let lock = NSLock()
    private var saved: SavedSession?
    private var requests: [URLRequest] = []
    private var cookieWrites = 0
    private var instant = ContinuousClock.now
    private let replies: [PageHTTPReply]
    private let advances: [Double]
    private let cookieResult: CookieResult
    init(_ replies: [PageHTTPReply], saved: SavedSession? = PageHTTPFixture.session(),
         advances: [Double] = [], cookieResult: CookieResult = .keep) {
        let identifier = UUID().uuidString; id = identifier
        self.replies = replies; self.saved = saved; self.advances = advances; self.cookieResult = cookieResult
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PageHTTPProtocol.self]
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false; configuration.urlCache = nil
        configuration.httpAdditionalHeaders = ["X-Follower-Fixture": identifier]
        transport = URLSession(configuration: configuration)
        PageHTTPProtocol.register(id) { [weak self] request in
            self?.reply(request) ?? PageHTTPReply(status: 500)
        }
    }
    func close() { PageHTTPProtocol.unregister(id); transport.invalidateAndCancel() }
    private func reply(_ request: URLRequest) -> PageHTTPReply {
        lock.lock(); defer { lock.unlock() }
        let index = requests.count; requests.append(request)
        if index < advances.count { instant = instant.advanced(by: .seconds(advances[index])) }
        return replies.isEmpty ? PageHTTPReply(status: 500) : replies[min(index, replies.count - 1)]
    }
    func snapshot() -> SavedSession? { lock.lock(); defer { lock.unlock() }; return saved }
    func received() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return requests }
    func writes() -> Int { lock.lock(); defer { lock.unlock() }; return cookieWrites }
    func now() -> ContinuousClock.Instant { lock.lock(); defer { lock.unlock() }; return instant }
    private func cookies() throws {
        lock.lock(); defer { lock.unlock() }; cookieWrites += 1
        switch cookieResult {
        case .keep: break
        case .changeOwner: saved = Self.session(owner: "43")
        case .changeVersion: saved?.savedAt += 1
        case .changeDocument: documentFence.advance()
        case .storageFailure: throw StorageFailure.database
        }
    }
    var client: SessionHTTPClient {
        SessionHTTPClient(vault: SessionVault(), transport: transport,
            loadSession: { [self] _ in snapshot() }, saveCookies: { [self] _, _, _, _ in try cookies() },
            clockNow: { [self] in now() })
    }
    var collector: SessionCollector {
        SessionCollector(vault: SessionVault(), http: client, loadSession: { [self] _ in snapshot() })
    }
    static func session(owner: String = "42") -> SavedSession {
        func cookie(_ name: String, _ value: String, _ domain: String = ".facebook.com", _ path: String = "/") -> CookieRecord {
            CookieRecord(HTTPCookie(properties: [.name: name, .value: value, .domain: domain, .path: path, .secure: "TRUE"])!)
        }
        return SavedSession(cookies: [cookie("c_user", owner), cookie("xs", "synthetic-page-http"),
            cookie("external", "synthetic-external", ".google.com"), cookie("path_only", "synthetic-path", ".facebook.com", "/fixture.page")],
            userAgent: "fixture-agent", expectedID: owner, savedAt: 1)
    }
}

/// Actual URLSession/SessionCollector with local responses; no App Group, Vault writes or real credentials.
final class FacebookPageHTTPRuntimeTests: XCTestCase {
    private func page() throws -> Account {
        try Account(provider: .facebook, stableID: "99", username: "fixture.page", displayName: "Fixture Page",
            profileURL: URL(string: "https://www.facebook.com/profile.php?id=99")!, connectedAt: 12,
            accountType: .page, sessionOwnerID: "42")
    }
    private func html(canonical: String = "/profile.php?id=99", count: Int = 3) -> Data {
        Data("""
        <html><head><link rel="canonical" href="\(canonical)"></head><body>
        <script type="application/json" data-sjs>{"data":{"page":{"__typename":"Page","id":"99","name":"Fixture Page","username":"fixture.page","followers_count":\(count)}}}</script>
        </body></html>
        """.utf8)
    }
    private func captured() throws -> (Account, MetricSnapshot) {
        var account = try page(); account.status = .foregroundOnly
        account.capabilities.count = .foregroundOnly; account.capabilities.background = .foregroundOnly
        return (account, try MetricSnapshot(accountKey: account.id, observedAt: 1_000, followers: 7, following: nil, source: "facebook-webview-page"))
    }
    private func failure(_ status: SyncStatus, _ operation: () async throws -> Void) async {
        do { try await operation(); XCTFail("Expected the native collection boundary to reject this response") }
        catch let error as CollectionFailure { XCTAssertEqual(error.status, status) }
        catch { XCTFail("Unexpected error type") }
    }

    func testNativeCollectorKeepsThePageNamespaceAndUsesOwnerCookiesWithoutExternalCookies() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(body: html(count: 0))]); defer { fixture.close() }
        let (account, metric) = try await fixture.collector.native(.facebook, expected: page(), timeout: 9)
        XCTAssertEqual(account.id, "FACEBOOK:PAGE:99"); XCTAssertEqual(account.authenticationOwnerID, "42")
        XCTAssertEqual(account.connectedAt, 12); XCTAssertEqual(account.status, .ready)
        XCTAssertEqual(account.capabilities.background, .unverified); XCTAssertEqual(account.capabilities.followers, .unavailable)
        XCTAssertEqual(metric.accountKey, account.id); XCTAssertEqual(metric.followers, 0); XCTAssertNil(metric.following)
        XCTAssertEqual(metric.source, "facebook-page-session")
        let request = try XCTUnwrap(fixture.received().first)
        XCTAssertTrue(request.value(forHTTPHeaderField: "Cookie")?.contains("c_user=42") == true)
        XCTAssertFalse(request.value(forHTTPHeaderField: "Cookie")?.contains("external") == true)
        XCTAssertFalse(request.value(forHTTPHeaderField: "Cookie")?.contains("path_only") == true)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-cache")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "fixture-agent")
        XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
    }
    func testAnOfficialAliasRedirectUsesFreshURLSpecificCookiesAndTheFinalDocument() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/fixture.page/"]),
            PageHTTPReply(body: html(canonical: "/fixture.page/"))]); defer { fixture.close() }
        let document = try await fixture.client.facebookPage(page(), timeout: 9)
        XCTAssertEqual(document.url, URL(string: "https://www.facebook.com/fixture.page/"))
        let requests = fixture.received()
        XCTAssertEqual(requests.count, 2); XCTAssertEqual(fixture.writes(), 2)
        XCTAssertFalse(requests[0].value(forHTTPHeaderField: "Cookie")?.contains("path_only") == true)
        XCTAssertTrue(requests[1].value(forHTTPHeaderField: "Cookie")?.contains("path_only") == true)
        XCTAssertTrue(requests.allSatisfy { $0.url?.host == "www.facebook.com" && $0.value(forHTTPHeaderField: "Cookie")?.contains("external") != true })
    }
    func testUntrustedAuthenticationAndDifferentPageRedirectsNeverReceiveACookieRequest() async throws {
        for location in ["https://example.test/fixture.page", "https://facebook.com.example.test/fixture.page",
                         "http://www.facebook.com/fixture.page", "https://user@www.facebook.com/fixture.page", "/checkpoint/", "/profile.php?id=88"] {
            let fixture = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": location])]); defer { fixture.close() }
            await failure(.checkRequired) { _ = try await fixture.client.facebookPage(self.page(), timeout: 9) }
            XCTAssertEqual(fixture.received().count, 1); XCTAssertEqual(fixture.writes(), 0)
        }
    }
    func testRedirectLoopsAndBudgetsCannotKeepTheCollectorRunning() async throws {
        let loop = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/profile.php?id=99"])]); defer { loop.close() }
        await failure(.checkRequired) { _ = try await loop.client.facebookPage(self.page(), timeout: 9) }
        XCTAssertEqual(loop.received().count, 1)
        let chain = PageHTTPFixture(["one", "two", "three"].map { PageHTTPReply(status: 302, headers: ["Location": "/fixture.\($0)"]) }); defer { chain.close() }
        await failure(.checkRequired) { _ = try await chain.client.facebookPage(self.page(), timeout: 9) }
        XCTAssertEqual(chain.received().count, 3)
    }
    func testGenericSessionRequestsKeepTheirOriginalNoRedirectBoundary() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/fixture.page/"])]); defer { fixture.close() }
        await failure(.reauthRequired) { _ = try await fixture.client.read(.facebook, self.page().profileURL, expectedID: "42") }
        XCTAssertEqual(fixture.received().count, 1)
    }
    func testOwnerChangesAndSessionReplacementDiscardTheInFlightDocument() async throws {
        for result: PageHTTPFixture.CookieResult in [.changeOwner, .changeVersion] {
            let fixture = PageHTTPFixture([PageHTTPReply(body: html())], cookieResult: result); defer { fixture.close() }
            await failure(.checkRequired) { _ = try await fixture.client.facebookPage(self.page(), timeout: 9) }
            XCTAssertEqual(fixture.received().count, 1)
        }
        let redirect = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/fixture.page/"])], cookieResult: .changeOwner)
        defer { redirect.close() }
        await failure(.checkRequired) { _ = try await redirect.client.facebookPage(self.page(), timeout: 9) }
        XCTAssertEqual(redirect.received().count, 1)
    }
    func testServiceErrorsAndResponseSizeNeverBecomePageObservations() async throws {
        for (code, status) in [(401, SyncStatus.reauthRequired), (403, .checkRequired), (429, .rateLimited), (503, .offline)] {
            let fixture = PageHTTPFixture([PageHTTPReply(status: code, headers: ["Retry-After": "300"])]); defer { fixture.close() }
            do { _ = try await fixture.client.facebookPage(page(), timeout: 9); XCTFail("Service error must propagate") }
            catch let error as CollectionFailure { XCTAssertEqual(error.status, status); XCTAssertEqual(error.retryAfterSeconds, 300) }
            XCTAssertEqual(fixture.received().count, 1); XCTAssertEqual(fixture.writes(), 0)
        }
        let oversized = PageHTTPFixture([PageHTTPReply(headers: ["Content-Length": "9437184"])]); defer { oversized.close() }
        await failure(.formatChanged) { _ = try await oversized.client.facebookPage(self.page(), timeout: 9) }
        let replaced = PageHTTPFixture([PageHTTPReply(body: html(), responseURL: URL(string: "https://example.test/"))]); defer { replaced.close() }
        await failure(.checkRequired) { _ = try await replaced.client.facebookPage(self.page(), timeout: 9) }
    }
    func testCookieStorageFailuresAreNotHiddenAsNetworkFailures() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(body: html())], cookieResult: .storageFailure); defer { fixture.close() }
        do { _ = try await fixture.client.facebookPage(page(), timeout: 9); XCTFail("Storage error must propagate") }
        catch let error as StorageFailure { if case .database = error { } else { XCTFail("Wrong storage failure") } }
        do { _ = try await fixture.collector.connectedFacebookPage(captured(), existing: nil); XCTFail("A storage failure cannot promote or silently publish a Page") }
        catch let error as StorageFailure { if case .database = error { } else { XCTFail("Wrong storage failure") } }
    }
    func testMissingChangedOrArchivedSessionsCannotStartARequest() async throws {
        for session in [nil, PageHTTPFixture.session(owner: "43")] {
            let fixture = PageHTTPFixture([], saved: session); defer { fixture.close() }
            await failure(session.map { _ in SyncStatus.checkRequired } ?? .reauthRequired) { _ = try await fixture.client.facebookPage(self.page(), timeout: 9) }
            XCTAssertTrue(fixture.received().isEmpty)
        }
        let fixture = PageHTTPFixture([]); defer { fixture.close() }
        var archived = try page(); archived.loggedOut = true
        await failure(.checkRequired) { _ = try await fixture.client.facebookPage(archived, timeout: 9) }
        for timeout in [0, -1, .infinity, .nan, Double.greatestFiniteMagnitude] {
            await failure(.checkRequired) { _ = try await fixture.client.facebookPage(self.page(), timeout: timeout) }
        }
        XCTAssertTrue(fixture.received().isEmpty)
    }
    func testAllRedirectsShareOneMonotonicDeadline() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/fixture.page/"]),
            PageHTTPReply(body: html(canonical: "/fixture.page/"))], advances: [20, 6]); defer { fixture.close() }
        await failure(.offline) { _ = try await fixture.client.facebookPage(self.page(), timeout: 25) }
        let requests = fixture.received()
        XCTAssertEqual(requests.count, 2); XCTAssertEqual(requests.first?.timeoutInterval, 25)
        XCTAssertEqual(requests.last?.timeoutInterval, 5)
    }
    func testAStalledResponseBodyCannotOutliveTheTotalRequestBudget() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(body: html(), bodyDelay: 5)]); defer { fixture.close() }
        let started = ContinuousClock.now
        await failure(.offline) { _ = try await fixture.client.facebookPage(self.page(), timeout: 0.05) }
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(3))
        XCTAssertEqual(fixture.received().count, 1); XCTAssertEqual(fixture.writes(), 0)
    }
    func testCancellingTheRequestCannotStartALaterRedirectOrPublishCookies() async throws {
        for promotion in [false, true] {
            let fixture = PageHTTPFixture([PageHTTPReply(status: 302, headers: ["Location": "/fixture.page/"], delay: 1)]); defer { fixture.close() }
            let account = try page(), client = fixture.client, collector = fixture.collector, original = try captured()
            let work = Task<Void, Error> {
                if promotion { _ = try await collector.connectedFacebookPage(original, existing: nil) }
                else { _ = try await client.facebookPage(account, timeout: 9) }
            }
            let limit = ContinuousClock.now.advanced(by: .seconds(3))
            while fixture.received().isEmpty && ContinuousClock.now < limit { try await Task.sleep(for: .milliseconds(5)) }
            XCTAssertEqual(fixture.received().count, 1)
            work.cancel()
            do { _ = try await work.value; XCTFail("Cancellation must propagate") } catch is CancellationError { }
            XCTAssertEqual(fixture.received().count, 1); XCTAssertEqual(fixture.writes(), 0)
        }
    }
    func testOnlyASuccessfulNativeProbePromotesTheBrowserConnectionToAutomaticCollection() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(body: html(count: 0))]); defer { fixture.close() }
        let observation = try await fixture.collector.connectedFacebookPage(captured(), existing: nil)
        XCTAssertEqual(observation.1.followers, 0); XCTAssertEqual(observation.1.source, "facebook-page-session")
        XCTAssertEqual(observation.0.status, .ready); XCTAssertEqual(observation.0.capabilities.background, .unverified)
        XCTAssertTrue(RefreshPolicy.canRefresh(observation.0, now: nowMillis(), background: true))
    }
    func testUnsupportedNativeDataKeepsTheActualBrowserValueAndStopsAutomaticAttempts() async throws {
        for status in [200, 401, 403] {
            let fixture = PageHTTPFixture([PageHTTPReply(status: status, body: Data("<html>Fixture with no structured Page data</html>".utf8))]); defer { fixture.close() }
            let original = try captured(), result = try await fixture.collector.connectedFacebookPage(original, existing: nil)
            XCTAssertEqual(result.1, original.1); XCTAssertEqual(result.0.status, .foregroundOnly)
            XCTAssertFalse(RefreshPolicy.canRefresh(result.0, now: nowMillis(), background: true))
        }
    }
    func testNativePromotionPreservesServerCooldownsWithoutAnotherRequest() async throws {
        let limited = PageHTTPFixture([PageHTTPReply(status: 429, headers: ["Retry-After": "300"])]); defer { limited.close() }
        let original = try captured(), result = try await limited.collector.connectedFacebookPage(original, existing: nil)
        XCTAssertEqual(result.1, original.1); XCTAssertEqual(result.0.status, .rateLimited)
        XCTAssertGreaterThan(result.0.nextAllowedAt ?? 0, nowMillis())
        let skipped = PageHTTPFixture([]); defer { skipped.close() }
        let next = try await skipped.collector.connectedFacebookPage(original, existing: result.0)
        XCTAssertEqual(next.0.nextAllowedAt, result.0.nextAllowedAt); XCTAssertEqual(next.1, original.1)
        XCTAssertTrue(skipped.received().isEmpty)
    }
    func testBrowserCapturesCannotBePublishedAfterTheNativeProbeRotatesTheSession() async throws {
        for change: PageHTTPFixture.CookieResult in [.changeOwner, .changeVersion] {
            let fixture = PageHTTPFixture([PageHTTPReply(body: html())], cookieResult: change); defer { fixture.close() }
            await failure(.checkRequired) { _ = try await fixture.collector.connectedFacebookPage(self.captured(), existing: nil) }
        }
    }
    func testBackgroundCollectionStoresOnlyVerifiedNativeCountsAndKeepsEarlierDataOnFailure() async throws {
        for success in [true, false] {
            let fixture = PageHTTPFixture([PageHTTPReply(body: success ? html(count: 0) : Data("<html>Unsupported fixture</html>".utf8))]); defer { fixture.close() }
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-page-http-" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let repository = try TrackerRepository(databaseURL: folder.appendingPathComponent("Tracker.sqlite"))
            var account = try page(); account.capabilities.count = .observed; account.capabilities.background = .unverified
            try await repository.saveObservation(account, MetricSnapshot(accountKey: account.id, observedAt: 1_000,
                followers: 7, following: nil, source: "synthetic-before-native"))
            let sync = SyncService(repository: repository, collector: fixture.collector)
            try await sync.refresh(account.id, background: true, timeout: 9)
            let stored = try await repository.account(account.id), history = try await repository.history(account.id)
            XCTAssertEqual(stored?.accountType, .page); XCTAssertEqual(stored?.connectedAt, 12)
            XCTAssertEqual(stored?.authenticationOwnerID, "42")
            XCTAssertEqual(history.first?.followers, 7)
            XCTAssertEqual(history.count, success ? 2 : 1); XCTAssertEqual(history.last?.followers, success ? 0 : 7)
            XCTAssertEqual(stored?.capabilities.background, success ? .observed : .unverified)
            XCTAssertEqual(stored?.status, success ? .ready : .foregroundOnly)
        }
    }
    func testChangingTheDocumentDiscardsTheProbeAndCannotCommitItsLateObservation() async throws {
        let fixture = PageHTTPFixture([PageHTTPReply(body: html())], cookieResult: .changeDocument); defer { fixture.close() }
        let current = fixture.documentFence.capture(), original = try captured()
        await failure(.checkRequired) { _ = try await fixture.collector.connectedFacebookPage(original, existing: nil, writeIf: current) }
        XCTAssertFalse(current())
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-page-fence-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let repository = try TrackerRepository(databaseURL: folder.appendingPathComponent("Tracker.sqlite"))
        try await repository.saveObservation(original.0, original.1)
        let stale = try MetricSnapshot(accountKey: original.0.id, observedAt: 2_000, followers: 99, following: nil, source: "synthetic-late-page")
        let stored = try await repository.saveObservation(original.0, stale, writeIf: current)
        XCTAssertFalse(stored)
        let history = try await repository.history(original.0.id)
        XCTAssertEqual(history, [original.1])
    }
}
