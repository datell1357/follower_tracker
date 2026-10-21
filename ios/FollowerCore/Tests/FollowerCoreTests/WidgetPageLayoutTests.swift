import XCTest
@testable import FollowerCore

final class WidgetPageLayoutTests: XCTestCase {
    func testFiveConnectionsFitWithoutUnnecessaryControlsAtTheGalleryHeight() {
        let page = WidgetPageLayout(itemCount: 5, requestedPage: 0, height: 360)
        XCTAssertEqual(page.range, 0..<5)
        XCTAssertEqual(page.pageCount, 1)
        XCTAssertFalse(page.hasPrevious); XCTAssertFalse(page.hasNext)
    }

    func testExtraPagesRemainReachableWithoutRepeatingOrDroppingAConnection() {
        for height in [300.0, 338, 360, 420] {
            for count in [6, 7, 11, 27] {
                let first = WidgetPageLayout(itemCount: count, requestedPage: 0, height: height)
                var reached: [Int] = []
                for index in 0..<first.pageCount {
                    let page = WidgetPageLayout(itemCount: count, requestedPage: index, height: height)
                    XCTAssertLessThanOrEqual(page.range.count, page.capacity)
                    XCTAssertEqual(page.hasPrevious, index > 0)
                    XCTAssertEqual(page.hasNext, index + 1 < first.pageCount)
                    reached.append(contentsOf: page.range)
                }
                XCTAssertEqual(reached, Array(0..<count))
            }
        }
    }

    func testSmallHostsReserveRoomForNavigationInsteadOfShrinkingAllRows() {
        let page = WidgetPageLayout(itemCount: 8, requestedPage: 0, height: 338)
        XCTAssertEqual(page.capacity, 3)
        XCTAssertEqual(page.range, 0..<3)
        XCTAssertEqual(page.pageCount, 3)
    }

    func testRemovedConnectionsAndStaleOrNegativeSelectionNeverProduceAnEmptyLastPage() {
        XCTAssertEqual(WidgetPageLayout(itemCount: 2, requestedPage: 9, height: 360).range, 0..<2)
        let last = WidgetPageLayout(itemCount: 11, requestedPage: Int.max, height: 360)
        XCTAssertEqual(last.index, last.pageCount - 1)
        XCTAssertEqual(last.range, 8..<11)
        XCTAssertEqual(WidgetPageLayout(itemCount: 11, requestedPage: Int.min, height: 360).range, 0..<4)
    }

    func testEmptyAndInvalidGeometryStayBounded() {
        for height in [0.0, -20, .nan, .infinity, -.infinity, Double.greatestFiniteMagnitude] {
            let empty = WidgetPageLayout(itemCount: 0, requestedPage: Int.max, height: height)
            XCTAssertEqual(empty.range, 0..<0)
            XCTAssertEqual(empty.index, 0); XCTAssertEqual(empty.pageCount, 1)
            XCTAssertFalse(empty.hasPrevious); XCTAssertFalse(empty.hasNext)
            let populated = WidgetPageLayout(itemCount: 10, requestedPage: 0, height: height)
            XCTAssertTrue((1...5).contains(populated.capacity))
        }
    }

    func testExtremeCountsAndSelectionsDoNotOverflow() {
        for height in [0.0, 338, 360, 420] {
            let page = WidgetPageLayout(itemCount: Int.max, requestedPage: Int.max, height: height)
            XCTAssertEqual(page.range.upperBound, Int.max)
            XCTAssertGreaterThan(page.range.count, 0)
            XCTAssertLessThanOrEqual(page.range.count, 5)
            XCTAssertFalse(page.hasNext)
        }
        XCTAssertEqual(WidgetPageLayout(itemCount: -1, requestedPage: 0, height: 360).range, 0..<0)
    }
}
