import SwiftUI
import WebKit
import Observation
import FollowerCore

@MainActor @Observable
final class LoginBrowser: NSObject, WKNavigationDelegate, WKUIDelegate {
    let provider: Provider
    let facebookPage: Bool
    let expectedPage: Account?
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
    private let observationFence = DocumentObservationFence()
    private var urlObservation: NSKeyValueObservation?
    private var activePageGuard: @Sendable () -> Bool = { false }
    func pageObservationGuard() -> @Sendable () -> Bool { activePageGuard }
    private var capturedPages: Set<String> = []
    private var navigatedProfiles: Set<URL> = []
    private let restoreSession: @MainActor () async throws -> SavedSession?
    private let loadPage: @MainActor (WKWebView, URL) -> Void
    private var task: Task<Void, Never>?
    private var pageConfirmationRequested = false
    private var pageRequest: FacebookPageConnectionRequest?
    var canRetry: Bool { !loading && !checking && now >= policy.nextAllowedAt }
    var canOpenPageLink: Bool { !checking && now >= policy.nextAllowedAt }
    var needsPageConfirmation: Bool { pageRequest?.requiresConfirmation == true }
    init(provider: Provider, facebookPage: Bool = false, expectedPage: Account? = nil,
         restoreSession: @escaping @MainActor () async throws -> SavedSession? = { nil },
         loadPage: (@MainActor (WKWebView, URL) -> Void)? = nil) {
        self.provider = provider; self.facebookPage = facebookPage; self.expectedPage = expectedPage; host = provider.domain
        policy = AutoConnectionPolicy(localReadGraceMillis: provider == .instagram ? 15_000 : 0)
        self.restoreSession = restoreSession
        self.loadPage = loadPage ?? { web, url in
            var request = URLRequest(url: url, cachePolicy: facebookPage ? .reloadIgnoringLocalCacheData : .useProtocolCachePolicy)
            if facebookPage { request.setValue("no-cache", forHTTPHeaderField: "Cache-Control") }
            web.load(request)
        }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        if provider == .instagram, let url = Bundle.main.url(forResource: "web-profile-observer", withExtension: "js"),
           let script = try? String(contentsOf: url, encoding: .utf8) {
            configuration.userContentController.addUserScript(WKUserScript(source: script + ";FollowerTrackerObserver.install('INSTAGRAM');",
                injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        let fence = observationFence
        urlObservation = webView.observe(\.url, options: [.new]) { _, _ in fence.advance() }
        webView.navigationDelegate = self; webView.uiDelegate = self; webView.isInspectable = false
    }
    func open(isBusy: @escaping @MainActor () -> Bool = { false },
              onObservation: @escaping @MainActor (SavedSession, String) async throws -> Void,
              completed: @escaping @MainActor () -> Void) {
        guard task == nil else { return }
        observationFence.activate()
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
            loadPage(webView, expectedPage?.profileURL ?? provider.loginURL)
            guard let scriptURL = Bundle.main.url(forResource: "web-session-capture", withExtension: "js"),
                  let script = try? String(contentsOf: scriptURL, encoding: .utf8) else {
                notice = connectionFailureMessage(CollectionFailure(.formatChanged)); return
            }
            while !Task.isCancelled {
                now = nowMillis()
                if let url = webView.url, !loading, !isBusy() {
                    let version = navigationVersion
                    let pageWriteIf = observationFence.capture()
                    let cookies = await withCheckedContinuation { continuation in webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { continuation.resume(returning: $0) } }
                    let candidate = SavedSession(cookies: cookies.map(CookieRecord.init), userAgent: "", expectedID: expectedID, savedAt: nowMillis())
                    sessionReady = candidate.authenticated(provider)
                    let pageKey = (candidate.identity(provider) ?? "") + ":" + url.absoluteString
                    if facebookPage { pageRequest?.observe(url) }
                    if facebookPage, let request = pageRequest {
                        switch request.next(current: url,
                            authenticated: sessionReady, loading: loading, busy: checking || notice != nil || now < policy.nextAllowedAt) {
                        case .openTarget:
                            pageRequest?.returnedAfterLogin = true
                            loadPage(webView, request.target)
                            continue
                        case .capture:
                            pageRequest?.captureAttempted = true
                            pageConfirmationRequested = true
                        case .wait: break
                        }
                    }
                    if facebookPage, pageConfirmationRequested, sessionReady, !checking {
                        pageConfirmationRequested = false; checking = true; notice = nil
                        do {
                            var payload = try await captureWebSession(webView, provider: provider, identity: candidate.identity(provider), script: script,
                                allowRequest: false, facebookPage: true, expectedPageID: expectedPage?.stableID, requestedPageURL: pageRequest?.target)
                            for _ in 0..<30 {
                                let error = try ResponseParser.objectBody(payload)["error"] as? String ?? ""
                                guard ["page_required", "page_identity_missing", "exact_count_missing", "rounded_count_only", "document_changed"].contains(error),
                                      navigationVersion == version else { break }
                                try await Task.sleep(for: .milliseconds(500))
                                payload = try await captureWebSession(webView, provider: provider, identity: candidate.identity(provider), script: script,
                                    allowRequest: false, facebookPage: true, expectedPageID: expectedPage?.stableID, requestedPageURL: pageRequest?.target)
                            }
                            try Task.checkCancellation()
                            guard navigationVersion == version, pageWriteIf() else { checking = false; continue }
                            let result = try ResponseParser.objectBody(payload)
                            if let failure = ResponseParser.webCaptureFailure(result) {
                                policy.failed(failure, now: nowMillis())
                                notice = failure.status == .rateLimited ? connectionFailureMessage(failure) : facebookPageFailureMessage(result["error"] as? String ?? "")
                            }
                            else {
                                let currentCookies = await withCheckedContinuation { continuation in
                                    webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { continuation.resume(returning: $0) }
                                }
                                var session = SavedSession(cookies: currentCookies.map(CookieRecord.init), userAgent: "", expectedID: expectedID, savedAt: nowMillis())
                                guard session.authenticated(provider), session.identity(provider) == candidate.identity(provider) else { throw CollectionFailure(.checkRequired) }
                                session.userAgent = try await webView.evaluateJavaScript("navigator.userAgent") as? String ?? "Mozilla/5.0"
                                try Task.checkCancellation()
                                guard navigationVersion == version, pageWriteIf() else { checking = false; continue }
                                activePageGuard = pageWriteIf
                                try await onObservation(session, payload)
                                try Task.checkCancellation()
                                checking = false; completed(); return
                            }
                        } catch is CancellationError { checking = false; return }
                        catch let failure as CollectionFailure {
                            policy.failed(failure, now: nowMillis()); notice = connectionFailureMessage(failure)
                        }
                        catch { notice = "페이지 연결을 마치지 못했어요. 선택한 페이지에서 다시 확인해주세요." }
                        checking = false
                    }
                    if !facebookPage, canAutoConnect(provider, url: url, authenticated: sessionReady, loading: loading, busy: isBusy() || checking), !capturedPages.contains(pageKey) {
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
    func openPageLink(_ input: String) {
        guard facebookPage, canOpenPageLink else { return }
        guard let request = FacebookPageConnectionRequest(input: input, authenticated: sessionReady) else {
            notice = "Facebook 페이지 링크를 확인해주세요. 페이지 주소나 복사한 공유 링크를 붙여넣을 수 있어요."
            return
        }
        pageRequest = request; notice = nil
        loadPage(webView, request.target)
    }
    func editedPageLink() { pageRequest = nil }
    func confirmPage() { guard facebookPage, sessionReady, canRetry else { return }; pageRequest = nil; pageConfirmationRequested = true }
    func cancel() {
        observationFence.invalidate()
        task?.cancel(); task = nil; checking = false
        webView.stopLoading(); webView.navigationDelegate = nil; webView.loadHTMLString("", baseURL: nil)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        guard let url = action.request.url, action.targetFrame?.isMainFrame != false else { decisionHandler(.allow, preferences); return }
        guard provider.allowsLogin(url) else { notice = "공식 SNS 로그인 주소가 아닌 페이지로의 이동을 중단했어요. SNS의 아이디·비밀번호 또는 이메일 로그인 방식을 선택해주세요."; decisionHandler(.cancel, preferences); return }
        if facebookPage { preferences.preferredContentMode = FacebookPageAddress.isTarget(url) ? .desktop : .mobile }
        if provider == .youtube { preferences.preferredContentMode = url.host == "studio.youtube.com" ? .desktop : .mobile }
        if action.targetFrame == nil { webView.load(action.request); decisionHandler(.cancel, preferences) }
        else { decisionHandler(.allow, preferences) }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { observationFence.advance(); navigationVersion += 1; loading = true; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading = false; ownProfile = nil; host = webView.url?.host ?? provider.domain }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 열지 못했어요. 연결 상태를 확인해주세요." }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { loading = false; notice = "페이지를 읽지 못했어요. 다시 확인해주세요." }
}

@MainActor
func captureWebSession(_ webView: WKWebView, provider: Provider, identity: String?, script: String, allowRequest: Bool = true,
                       facebookPage: Bool = false, expectedPageID: String? = nil, requestedPageURL: URL? = nil) async throws -> String {
    guard let url = webView.url, provider.allows(url) else { throw CollectionFailure(.checkRequired) }
    let body = script + ";return JSON.stringify(page ? FollowerTrackerCapture.captureFacebookPage(identity, target, undefined, requested) : allowNetwork ? await FollowerTrackerCapture.captureAsync(provider, identity) : FollowerTrackerCapture.capture(provider, identity));"
    let value = try await webView.callAsyncJavaScript(body, arguments: ["provider": provider.rawValue, "identity": identity as Any? ?? NSNull(), "allowNetwork": allowRequest,
        "page": facebookPage, "target": expectedPageID as Any? ?? NSNull(),
        "requested": requestedPageURL?.absoluteString as Any? ?? NSNull()], in: nil, contentWorld: .page)
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
    let facebookPage: Bool
    let expectedPage: Account?
    @Environment(\.dismiss) private var dismiss
    @State private var browser: LoginBrowser
    @State private var pageAddress: String
    @FocusState private var pageAddressFocused: Bool
    init(provider: Provider, model: TrackerModel, facebookPage: Bool = false, expectedPage: Account? = nil) {
        self.provider = provider; self.model = model; self.facebookPage = facebookPage; self.expectedPage = expectedPage
        _pageAddress = State(initialValue: expectedPage?.profileURL.absoluteString ?? "")
        _browser = State(initialValue: LoginBrowser(provider: provider, facebookPage: facebookPage, expectedPage: expectedPage, restoreSession: { try await SessionVault.shared.load(provider) }))
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack { Image(systemName: "lock"); Text(browser.host).lineLimit(1); Spacer() }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 8)
                Text(facebookPage ? "페이지 링크를 입력하고 연결을 누르면 자동으로 확인해요." : provider == .youtube ?
                    "YouTube Studio에서 본인 채널에 로그인하면 정확한 구독자 수를 확인해 연결해요." :
                    "공식 페이지에서 로그인하면 내 계정과 정확한 팔로워 수를 확인해 자동으로 연결해요.").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.bottom, 8)
                if facebookPage {
                    HStack {
                        TextField("페이스북 페이지 링크", text: $pageAddress).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($pageAddressFocused).submitLabel(.go).onSubmit { pageAddressFocused = false; browser.openPageLink(pageAddress) }
                            .onChange(of: pageAddress) { browser.editedPageLink() }.disabled(browser.checking || model.busy)
                        Button("연결") { pageAddressFocused = false; browser.openPageLink(pageAddress) }.buttonStyle(.borderedProminent).frame(minHeight: 48)
                            .disabled(!browser.canOpenPageLink || model.busy || FacebookPageConnectionRequest(input: pageAddress, authenticated: browser.sessionReady) == nil)
                    }.font(.subheadline).padding(.horizontal, 20).padding(.vertical, 8)
                    Text(!browser.sessionReady ? "아래에서 Facebook에 로그인해주세요. 로그인 후 입력한 페이지로 이어서 이동해요." :
                        browser.needsPageConfirmation ? "공유 링크가 열리면 아래 ‘이 페이지 연결’을 눌러 대상을 확인해주세요." :
                        "이미 Facebook에 로그인했어요. 페이지 주소나 공유 링크를 붙여넣어주세요.")
                        .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.bottom, 8)
                }
                if browser.loading || browser.checking || model.busy { ProgressView().progressViewStyle(.linear) }
                LoginWebView(browser: browser)
                if let notice = browser.notice { Text(notice).font(.caption).foregroundStyle(.red).padding(.horizontal, 20).padding(.top, 10) }
                if browser.checking || model.busy { Text(facebookPage ? "선택한 페이지와 정확한 팔로워 수를 확인하고 있어요." : "로그인한 내 계정과 팔로워 수를 확인하고 있어요.").font(.caption).foregroundStyle(TrackerStyle.blue).padding(10) }
                if let url = browser.ownProfile { Button("내 프로필 열기") { browser.notice = nil; browser.ownProfile = nil; browser.webView.load(URLRequest(url: url)) }.padding(.top, 8) }
                if facebookPage { Button("이 페이지 연결") { browser.confirmPage() }.buttonStyle(.bordered).padding(12).disabled(!browser.sessionReady || !browser.canRetry || model.busy) }
                else if browser.notice != nil { Button("다시 시도") { browser.requestRetry() }.buttonStyle(.bordered).padding(12).disabled(!browser.canRetry || model.busy) }
            }.navigationTitle((facebookPage ? "Facebook 페이지" : provider.title) + " 연결").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { browser.cancel(); dismiss() } }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("페이지 새로고침", systemImage: "arrow.clockwise") { browser.webView.reload() }.disabled(browser.checking || model.busy)
                        Button("이전 페이지", systemImage: "chevron.left") { if browser.webView.canGoBack { browser.webView.goBack() } }.disabled(model.busy || !browser.webView.canGoBack)
                    } }
        }.presentationDetents([.large])
            .onAppear { browser.open(isBusy: { model.busy }, onObservation: { session, payload in
                let guardDocument: SessionWriteGuard = facebookPage ? browser.pageObservationGuard() : { true }
                try await model.connect(provider, session: session, payload: payload,
                    facebookPage: facebookPage, expectedPageKey: expectedPage?.id, writeIf: guardDocument)
            }, completed: { dismiss() }) }.onDisappear { browser.cancel() }
    }
}

private func facebookPageFailureMessage(_ error: String) -> String {
    switch error {
    case "identity_missing", "reauth_required": "Facebook 로그인을 완료해주세요. 페이지 링크는 위에 입력할 수 있어요."
    case "page_mismatch": "입력하거나 기존에 연결한 페이지와 다른 페이지예요. 페이지 주소를 확인해주세요."
    case "page_identity_missing": "페이지 식별 정보를 읽지 못했어요. 페이지 첫 화면에서 다시 확인해주세요."
    case "rounded_count_only": "축약된 팔로워 수만 보여 정확한 수를 저장할 수 없어요."
    case "page_required", "own_profile_required": "Facebook 페이지 정보를 찾지 못했어요. 개인 프로필이나 게시물 대신 페이지 링크를 입력해주세요."
    default: "이 페이지에서 정확한 팔로워 수를 읽지 못했어요. 페이지 첫 화면에서 다시 확인해주세요."
    }
}
