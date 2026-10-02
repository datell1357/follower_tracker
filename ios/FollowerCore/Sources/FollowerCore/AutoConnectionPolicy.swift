import Foundation

/// Bounds network attempts separately from local reads of a hydrated profile.
public struct AutoConnectionPolicy: Sendable {
    private var attemptedPage: String?
    private var retryCount = 0
    public private(set) var nextAllowedAt: Int64 = 0
    public init() {}

    public mutating func begin(pageKey: String, now: Int64) -> Bool {
        guard attemptedPage != pageKey, now >= nextAllowedAt else { return false }
        attemptedPage = pageKey
        return true
    }
    public mutating func failed(_ failure: CollectionFailure, now: Int64) {
        let seconds: Int64
        switch failure.status {
        case .rateLimited: seconds = min(86_400, max(60, failure.retryAfterSeconds ?? 900))
        case .offline: seconds = 30
        default: return
        }
        nextAllowedAt = now + seconds * 1_000
        if failure.status == .rateLimited || retryCount < 2 { attemptedPage = nil }
        if failure.status == .offline { retryCount += 1 }
    }
    public mutating func requestRetry() { attemptedPage = nil; retryCount = 0 }
}

public func canAutoConnect(_ provider: Provider, url: URL, authenticated: Bool, loading: Bool, busy: Bool) -> Bool {
    guard authenticated, !loading, !busy, provider.allows(url) else { return false }
    let path = url.path.removingPercentEncoding?.lowercased() ?? url.path.lowercased()
    return path.range(of: "(^|/)(login|challenge|checkpoint|two_factor|two-factor|twofactor)(/|$)", options: .regularExpression) == nil
}

public func connectionFailureMessage(_ failure: CollectionFailure) -> String {
    switch failure.status {
    case .rateLimited: "SNS가 데이터 요청을 잠시 제한했어요. 로그인 창을 유지하면 대기 시간이 지난 뒤 다시 확인해요."
    case .offline: "데이터를 읽지 못했어요. 인터넷 연결을 확인해주세요."
    case .reauthRequired: "로그인을 완료해주세요. 완료되면 계정을 자동으로 연결해요."
    case .checkRequired: "SNS의 추가 인증을 완료하거나 로그인한 내 계정의 프로필을 열어주세요."
    case .formatChanged: "로그인 페이지에서 정확한 팔로워 수를 읽지 못했어요. 내 프로필에서 다시 확인해주세요."
    case .foregroundOnly: "이 SNS는 로그인 창에서 내 프로필을 열어 갱신해요."
    case .listIncomplete: "명단을 끝까지 읽지 못했어요. 기존 명단을 유지했어요."
    default: failure.status.label
    }
}
