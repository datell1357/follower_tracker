import Foundation

public enum RefreshPolicy {
    /// A user's explicit refresh can retry failures, but cannot shorten a service cooldown.
    public static func canRefresh(_ account: Account, now: Int64, background: Bool) -> Bool {
        guard (account.nextAllowedAt ?? 0) <= now else { return false }
        if !background { return true }
        return !account.status.blocksAutomaticRetry && account.status != .foregroundOnly
    }
}
