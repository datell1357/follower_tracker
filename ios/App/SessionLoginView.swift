import SwiftUI
import WebKit
import Observation
import FollowerCore

@MainActor @Observable
final class LoginBrowser: NSObject, WKNavigationDelegate, WKUIDelegate {
    let provider: Provider
    let webView: WKWebView
    var loading = true
    var checking = false
    var host: String
    var notice: String?
    var ownProfile: URL?
    var sessionReady = false
    var now: Int64 = 0
    private var policy = AutoConnectionPolicy()
    private var navigationVersion = 0
    private var capturedPages: Set<String> = []
    private var navigatedProfiles: Set<URL> = []
    private let restoreSession: @MainActor () async throws -> SavedSession?
    private let loadPage: @MainActor (WKWebView, URL) -> Void
    private var task: Task<Void, Never>?
    var canRetry: Bool { !loading && !checking && now >= policy.nextAllowedAt }
    init(provider: Provider,
         restoreSession: @escaping @MainActor () async throws -> SavedSession? = { nil },
         loadPage: @escaping @MainActor (WKWebView, URL) -> Void = { web, url in web.load(URLRequest(url: url)) }) {
        self.provider = provider; host = provider.domain
        self.restoreSession = restoreSession; self.loadPage = loadPage
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self; webView.uiDelegate = self; webView.isInspectable = false
    }
    func open(isBusy: @escaping @MainActor () -> Bool = { false },
              onObservation: @escaping @MainActor (SavedSession, String) async throws -> Void,
              completed: @escaping @MainActor () -> Void) {
        guard task == nil else { return }
        webView.navigationDelegate = self
        task = Task {
            var expectedID: String?
            do {
                if let saved = try await restoreSession() {
                    expectedID = saved.expectedID
                    for record in saved.cookies {
                        let domain = record.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                        if let destination = URL(string: "https://\(domain)/"), provider.allows(destination), let cookie = record.cookie {
                            await withCheckedContinuation { continuation in webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { continuation.resume() } }
                        }
                    }
                }
                try Task.checkCancellation()
            } catch is CancellationError { return }
            catch { notice = "저장된 로그인 세션을 읽지 못했어요. 다시 로그인해주세요." }
            guard !Task.isCancelled else { return }
            loadPage(webView, provider.loginURL)
            guard let scriptURL = Bundle.main.url(forResource: "web-session-capture", withExtension: "js"),
                  let script = try? String(contentsOf: scriptURL, encoding: .utf8) else {
                notice = connectionFailureMessage(CollectionFailure(.formatChanged)); return
            }
            while !Task.isCancelled {
                now = nowMillis()
                if let url = webView.url, !loading, !isBusy() {
                    let version = navigationVersion
                    let cookies = await withCheckedContinuation { continuation in webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { continuation.resume(returning: $0) } }
                    let candidate = SavedSession(cookies: cookies.map(CookieRecord.init), userAgent: "", expectedID: expectedID, savedAt: nowMillis())
                    sessionReady = candidate.authenticated(provider)
                    let pageKey = (candidate.identity(provider) ?? "") + ":" + url.absoluteString
                    if canAutoConnect(provider, url: url, authenticated: sessionReady, loading: loading, busy: isBusy() || checking), !capturedPages.contains(pageKey) {
                        let allowRequest = policy.begin(pageKey: pageKey, now: now)
                        if !allowRequest && provider == .reddit { try? await Task.sleep(for: .seconds(1)); continue }
                        checking = allowRequest
                        if allowRequest { notice = nil }
                        var submitting = false
                        do {
                            let payload = try await captureWebSession(webView, provider: provider, identity: candidate.identity(provider), script: script, allowRequest: allowRequest)
                            try Task.checkCancellation()
                            guard navigationVersion == version else { checking = false; continue }
                            let result = try ResponseParser.objectBody(payload)
                            if let failure = ResponseParser.webCaptureFailure(result) {
                                if result["error"] as? String == "own_profile_required", let text = result["profileURL"] as? String,
                                   let profile = URL(string: text), provider.allows(profile), profile != webView.url, navigatedProfiles.insert(profile).inserted {
                                    ownProfile = profile; webView.load(URLRequest(url: profile))
                                } else if allowRequest {
                                    policy.failed(failure, now: nowMillis()); notice = connectionFailureMessage(failure)
                                }
                            } else {
                                submitting = true; checking = true; capturedPages.insert(pageKey)
                                var session = candidate
                                session.userAgent = try await webView.evaluateJavaScript("navigator.userAgent") as? String ?? "Mozilla/5.0"
                                try Task.checkCancellation()
                                guard navigationVersion == version else { capturedPages.remove(pageKey); checking = false; continue }
                                try await onObservation(session, payload)
                                try Task.checkCancellation()
                                checking = false; completed(); return
                            }
                        } catch is CancellationError { checking = false; return }
                        catch let failure as CollectionFailure {
                            if allowRequest || submitting { policy.failed(failure, now: nowMillis()); notice = connectionFailureMessage(failure) }
                        } catch { if allowRequest || submitting { notice = "연결을 마치지 못했어요. 기존 기록을 유지했어요." } }
                        checking = false
                    }
                }
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }
    func requestRetry() { guard canRetry else { return }; notice = nil; capturedPages.removeAll(); policy.requestRetry() }
    func cancel() {
        task?.cancel(); task = nil; checking = false
        webView.stopLoading(); webView.navigationDelegate = nil; webView.loadHTMLString("", baseURL: nil)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, action.targetFrame?.isMainFrame != false else { decisionHandler(.allow); return }
        guard provider.allowsLogin(url) else { notice = "공식 SNS 로그인 주소가 아닌 페이지로의 이동을 중단했어요. SNS의 아이디·비밀번호 또는 이메일 로그인 방식을 선택해주세요."; decisionHandler(.cancel); return }
        if action.targetFrame == nil { webView.load(action.request); decisionHandler(.cancel) }
        else { decisionHandler(.allow) }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { navigationVersion += 1; loading = true; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading = false; ownProfile = nil; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 열지 못했어요. 연결 상태를 확인해주세요." }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 읽지 못했어요. 다시 확인해주세요." }
}

@MainActor
func captureWebSession(_ webView: WKWebView, provider: Provider, identity: String?, script: String, allowRequest: Bool = true) async throws -> String {
    guard let url = webView.url, provider.allows(url) else { throw CollectionFailure(.checkRequired) }
    let body = script + ";return JSON.stringify(allowNetwork ? await FollowerTrackerCapture.captureAsync(provider, identity) : FollowerTrackerCapture.capture(provider, identity));"
    let value = try await webView.callAsyncJavaScript(body, arguments: ["provider": provider.rawValue, "identity": identity as Any? ?? NSNull(), "allowNetwork": allowRequest], in: nil, contentWorld: .page)
    try Task.checkCancellation()
    guard webView.url == url else { throw CollectionFailure(.checkRequired) }
    guard let payload = value as? String else { throw CollectionFailure(.formatChanged) }
    return payload
}

private struct LoginWebView: UIViewRepresentable {
    let browser: LoginBrowser
    func makeUIView(context: Context) -> WKWebView { browser.webView }
    func updateUIView(_ view: WKWebView, context: Context) { }
}
struct SessionLoginView: View {
    let provider: Provider
    let model: TrackerModel
    let completed: () -> Void
    @State private var browser: LoginBrowser
    init(provider: Provider, model: TrackerModel, completed: @escaping () -> Void) {
        self.provider = provider; self.model = model; self.completed = completed
        _browser = State(initialValue: LoginBrowser(provider: provider, restoreSession: { try await SessionVault.shared.load(provider) }))
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack { Image(systemName: "lock"); Text(browser.host).lineLimit(1); Spacer() }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 8)
                Text("공식 페이지에서 로그인하면 내 계정과 정확한 팔로워 수를 확인해 자동으로 연결해요.").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.bottom, 8)
                if browser.loading || browser.checking || model.busy { ProgressView().progressViewStyle(.linear) }
                LoginWebView(browser: browser)
                if let notice = browser.notice { Text(notice).font(.caption).foregroundStyle(.red).padding(.horizontal, 20).padding(.top, 10) }
                if browser.checking || model.busy { Text("로그인한 내 계정과 팔로워 수를 확인하고 있어요.").font(.caption).foregroundStyle(TrackerStyle.blue).padding(10) }
                if let url = browser.ownProfile { Button("내 프로필 열기") { browser.notice = nil; browser.ownProfile = nil; browser.webView.load(URLRequest(url: url)) }.padding(.top, 8) }
                if browser.notice != nil { Button("다시 시도") { browser.requestRetry() }.buttonStyle(.bordered).padding(12).disabled(!browser.canRetry || model.busy) }
            }.navigationTitle(provider.title + " 연결").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { browser.cancel(); completed() } }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("페이지 새로고침", systemImage: "arrow.clockwise") { browser.webView.reload() }.disabled(browser.checking || model.busy)
                        Button("이전 페이지", systemImage: "chevron.left") { if browser.webView.canGoBack { browser.webView.goBack() } }.disabled(model.busy)
                    } }
        }.onAppear { browser.open(isBusy: { model.busy }, onObservation: { session, payload in try await model.connect(provider, session: session, payload: payload) }, completed: completed) }.onDisappear { browser.cancel() }
    }
}
