import Foundation

public struct MetricComparison: Equatable, Sendable {
    public let previousAt: Int64
    public let currentAt: Int64
    public let change: Int64
    public static func between(previous: MetricSnapshot?, current: MetricSnapshot?) -> MetricComparison? {
        guard let previous, let current, previous.accountKey == current.accountKey,
              previous.precision == .exact, current.precision == .exact,
              previous.observedAt < current.observedAt else { return nil }
        return MetricComparison(previousAt: previous.observedAt, currentAt: current.observedAt,
            change: current.followers - previous.followers)
    }
}
