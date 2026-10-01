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
    private var task: Task<Void, Never>?
    init(provider: Provider) {
        self.provider = provider; host = provider.domain
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self; webView.uiDelegate = self; webView.isInspectable = false
    }
    func open() {
        task = Task {
            do {
                if let saved = try await SessionVault.shared.load(provider) {
                    for record in saved.cookies {
                        if let cookie = record.cookie {
                            await withCheckedContinuation { continuation in webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { continuation.resume() } }
                        }
                    }
                }
                try Task.checkCancellation()
                webView.load(URLRequest(url: provider.loginURL))
            } catch is CancellationError { }
            catch { notice = "저장된 로그인 세션을 읽지 못했어요. 다시 로그인해주세요."; webView.load(URLRequest(url: provider.loginURL)) }
        }
    }
    func cancel() { task?.cancel(); task = nil; webView.stopLoading() }
    func connect(model: TrackerModel, completed: @escaping () -> Void) {
        guard !checking, !model.busy, let url = webView.url, provider.allows(url) else { return }
        notice = nil; ownProfile = nil; checking = true
        task = Task {
            defer { checking = false }
            do {
                let cookies = await withCheckedContinuation { continuation in webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { continuation.resume(returning: $0) } }
                let agent = try await webView.evaluateJavaScript("navigator.userAgent") as? String ?? "Mozilla/5.0"
                let saved = SavedSession(cookies: cookies.map(CookieRecord.init), userAgent: agent,
                    expectedID: try await SessionVault.shared.load(provider)?.expectedID, savedAt: nowMillis())
                var payload: String?
                if ![Provider.instagram, .reddit].contains(provider) {
                    guard let scriptURL = Bundle.main.url(forResource: "web-session-capture", withExtension: "js") else { throw CollectionFailure(.formatChanged) }
                    let script = try String(contentsOf: scriptURL, encoding: .utf8)
                    let arguments = try JSONSerialization.data(withJSONObject: [provider.rawValue, saved.identity(provider) as Any? ?? NSNull()])
                    let encoded = String(data: arguments, encoding: .utf8)!
                    payload = try await webView.evaluateJavaScript(script + ";JSON.stringify(FollowerTrackerCapture.capture(..." + encoded + "));") as? String
                    guard let payload else { throw CollectionFailure(.formatChanged) }
                    let result = try ResponseParser.objectBody(payload)
                    if let error = result["error"] as? String {
                        if let text = result["profileURL"] as? String, let profile = URL(string: text), provider.allows(profile) { ownProfile = profile }
                        notice = error == "exact_count_missing" ? "정확한 팔로워 수를 읽지 못했어요. 내 프로필이 열린 상태인지 확인해주세요." : "직접 로그인한 뒤 내 계정의 프로필 페이지를 열어주세요."
                        return
                    }
                }
                try Task.checkCancellation()
                try await model.connect(provider, session: saved, payload: payload)
                try Task.checkCancellation()
                completed()
            } catch is CancellationError { }
            catch let failure as CollectionFailure { notice = failure.status.label + ". 공식 로그인 페이지에서 계정을 확인해주세요." }
            catch { notice = "연결을 마치지 못했어요. 기존 기록을 유지했어요." }
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, action.targetFrame?.isMainFrame != false else { decisionHandler(.allow); return }
        guard provider.allows(url) else { notice = "이 연결 화면은 \(provider.domain)의 HTTPS 페이지에서만 이동할 수 있어요."; decisionHandler(.cancel); return }
        if action.targetFrame == nil { webView.load(action.request); decisionHandler(.cancel) }
        else { decisionHandler(.allow) }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { loading = true; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading = false; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 열지 못했어요. 연결 상태를 확인해주세요." }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 읽지 못했어요. 다시 확인해주세요." }
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
        _browser = State(initialValue: LoginBrowser(provider: provider))
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack { Image(systemName: "lock"); Text(browser.host).lineLimit(1); Spacer() }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 8)
                Text("공식 페이지에서 로그인한 뒤 내 프로필을 열고 ‘연결 확인’을 눌러주세요.").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.bottom, 8)
                if browser.loading || browser.checking || model.busy { ProgressView().progressViewStyle(.linear) }
                LoginWebView(browser: browser)
                if let notice = browser.notice { Text(notice).font(.caption).foregroundStyle(.red).padding(.horizontal, 20).padding(.top, 10) }
                if let url = browser.ownProfile { Button("내 프로필 열기") { browser.notice = nil; browser.ownProfile = nil; browser.webView.load(URLRequest(url: url)) }.padding(.top, 8) }
                Button(browser.checking || model.busy ? "계정 확인 중…" : "연결 확인") { browser.connect(model: model, completed: completed) }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity).controlSize(.large).padding(16).disabled(browser.loading || browser.checking || model.busy)
            }.navigationTitle(provider.title + " 연결").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { browser.cancel(); completed() } }
                    ToolbarItem(placement: .topBarTrailing) { Button("이전 페이지", systemImage: "chevron.left") { if browser.webView.canGoBack { browser.webView.goBack() } }.disabled(model.busy) } }
        }.onAppear { browser.open() }.onDisappear { browser.cancel() }
    }
}
