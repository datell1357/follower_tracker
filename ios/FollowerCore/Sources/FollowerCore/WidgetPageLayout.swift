import Foundation

/// A bounded slice of all connections, including Pages. No connection is discarded when the widget fills up.
public struct WidgetPageLayout: Equatable, Sendable {
    public let capacity: Int
    public let pageCount: Int
    public let index: Int
    public let range: Range<Int>
    public var hasPrevious: Bool { index > 0 }
    public var hasNext: Bool { index < pageCount - 1 }

    public init(itemCount: Int, requestedPage: Int, height: Double) {
        let count = max(0, itemCount)
        let availableHeight = height.isFinite ? max(0, height) : 0
        func rows(reservingControls: Bool) -> Int {
            let reserved: Double = reservingControls ? 119 : 71
            return Int(min(5, max(1, floor((availableHeight - reserved) / 57))))
        }
        let withoutControls = rows(reservingControls: false)
        capacity = count <= withoutControls ? withoutControls : rows(reservingControls: true)
        pageCount = max(1, count / capacity + (count % capacity == 0 ? 0 : 1))
        index = min(max(0, requestedPage), pageCount - 1)
        let start = index * capacity
        range = start..<(start + min(capacity, count - start))
    }
}
