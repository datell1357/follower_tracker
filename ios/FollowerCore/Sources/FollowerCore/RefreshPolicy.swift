import Foundation

public enum RefreshPolicy {
    /// A user's explicit refresh can retry failures, but cannot shorten a service cooldown.
    public static func canRefresh(_ account: Account, now: Int64, background: Bool) -> Bool {
        guard (account.nextAllowedAt ?? 0) <= now else { return false }
        if !background { return true }
        guard (account.transientRetry?.nextAttemptAt ?? 0) <= now else { return false }
        return !account.status.blocksAutomaticRetry && account.status != .foregroundOnly
    }

    public static func nextTransientRetry(_ account: Account, failedAt: Int64, status: SyncStatus = .offline) -> TransientRetryState {
        let count = min(4, max(0, account.transientRetry?.failureCount ?? 0)) + 1
        let delay: Int64 = status == .formatChanged ? 900_000 : min(900_000, Int64(60_000) << (count - 1))
        return TransientRetryState(failureCount: count, nextAttemptAt: saturatedDeadline(failedAt, delay: delay))
    }

    public static func serviceRetryAt(_ failure: CollectionFailure, failedAt: Int64) -> Int64? {
        let seconds: Int64?
        switch failure.status {
        case .rateLimited: seconds = failure.retryAfterSeconds ?? 900
        case .offline: seconds = failure.retryAfterSeconds
        default: seconds = nil
        }
        guard let seconds else { return nil }
        let (milliseconds, overflow) = max(0, seconds).multipliedReportingOverflow(by: 1_000)
        return saturatedDeadline(failedAt, delay: overflow ? .max : milliseconds)
    }

    private static func saturatedDeadline(_ now: Int64, delay: Int64) -> Int64 {
        let (deadline, overflow) = now.addingReportingOverflow(delay)
        return overflow ? .max : deadline
    }
}
