import XCTest
@testable import FollowerCore

final class MetricComparisonTests: XCTestCase {
    private func metric(_ at: Int64, count: Int64 = 10, precision: Precision = .exact, owner: String = "owner") throws -> MetricSnapshot {
        try MetricSnapshot(accountKey: owner, observedAt: at, followers: count, following: nil, precision: precision, source: "synthetic-comparison-fixture")
    }
    func testIrregularIntervalRetainsActualObservationTimesAndZeroChanges() throws {
        let result = try XCTUnwrap(MetricComparison.between(previous: metric(100), current: metric(900_000, count: 12)))
        XCTAssertEqual(result.previousAt, 100); XCTAssertEqual(result.currentAt, 900_000); XCTAssertEqual(result.change, 2)
        XCTAssertEqual(try MetricComparison.between(previous: metric(100), current: metric(200))?.change, 0)
        XCTAssertEqual(try MetricComparison.between(previous: metric(100), current: metric(200, count: 5))?.change, -5)
    }
    func testBaselineAndImpreciseMetricsHaveNoComparisonTime() throws {
        XCTAssertNil(try MetricComparison.between(previous: nil, current: metric(100)))
        XCTAssertNil(try MetricComparison.between(previous: metric(100), current: nil))
        for precision: Precision in [.rounded, .estimated] {
            XCTAssertNil(try MetricComparison.between(previous: metric(100, precision: precision), current: metric(200)))
            XCTAssertNil(try MetricComparison.between(previous: metric(100), current: metric(200, precision: precision)))
        }
    }
    func testChangedOwnerAndNonIncreasingTimesCannotCreateDeltas() throws {
        XCTAssertNil(try MetricComparison.between(previous: metric(100), current: metric(200, owner: "other")))
        XCTAssertNil(try MetricComparison.between(previous: metric(100), current: metric(100)))
        XCTAssertNil(try MetricComparison.between(previous: metric(100), current: metric(50)))
    }
}
