import XCTest
import SwiftUI
import FollowerCore
@testable import FollowerTracker

@MainActor
final class DesignRuntimeTests: XCTestCase {
    private func row(count: Int64 = 1_234, status: SyncStatus = .ready) throws -> AccountOverview {
        var account = try Account(provider: .instagram, stableID: "fixture", username: "sample", displayName: "Sample",
            profileURL: URL(string: "https://www.instagram.com/sample/")!, connectedAt: 1)
        account.status = status
        return AccountOverview(account: account, history: [try metric(1_700_000_000_000, count)])
    }
    private func metric(_ time: Int64, _ count: Int64, precision: Precision = .exact) throws -> MetricSnapshot {
        try MetricSnapshot(accountKey: "INSTAGRAM:fixture", observedAt: time, followers: count, following: 2, precision: precision, source: "synthetic-ui")
    }
    func testHourWindowDoesNotCompareAgainstAnOlderOrFutureRecord() throws {
        let now: Int64 = 1_800_000_000_000
        let older = try metric(now - 3_600_001, 100), first = try metric(now - 3_600_000, 7)
        let last = try metric(now - 1, 9), future = try metric(now + 1, 200)
        let records = TrackerTrendRange.hour.records([last, future, older, first], now: now)
        XCTAssertEqual(records, [first, last])
        XCTAssertEqual(MetricComparison.between(previous: records.first, current: records.last)?.change, 2)
        let single = TrackerTrendRange.hour.records([older, last], now: now)
        XCTAssertNil(MetricComparison.between(previous: single.first, current: single.last))
    }
    func testTodayWindowAndRecentRecordsUseActualObservationBoundaries() throws {
        let date = try XCTUnwrap(Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 2, hour: 12)))
        let now = Int64(date.timeIntervalSince1970 * 1_000)
        let start = Int64(Calendar.current.startOfDay(for: date).timeIntervalSince1970 * 1_000)
        let previousDay = try metric(start - 1, 10), today = try metric(start, 8)
        XCTAssertEqual(TrackerTrendRange.today.records([previousDay, today], now: now), [today])
        let history = try (0..<20).map { try metric(now - Int64(20 - $0) * 60_000, Int64($0)) }
        XCTAssertEqual(TrackerTrendRange.recent.records(history, now: now), Array(history.suffix(14)))
        let rounded = try metric(now, 20, precision: .rounded)
        XCTAssertNil(MetricComparison.between(previous: history.last, current: rounded))
    }
    func testWidgetFailureKeepsTheActualSuccessfulDateAndValidZero() throws {
        let value = try row(count: 0, status: .rateLimited)
        let status = TrackerStyle.widgetStatus(value)
        XCTAssertTrue(status.contains("갱신 대기"))
        XCTAssertTrue(status.contains(TrackerStyle.observationTime(value.latest!.observedAt, compact: true)))
        XCTAssertFalse(status.contains("분 전")); XCTAssertFalse(status.contains("방금"))
        XCTAssertEqual(TrackerStyle.count(value.latest!.followers), "0")
        XCTAssertEqual(TrackerStyle.collectedAt(nil), "아직 기록 없음")
    }
    func testPrimaryTextContrastInLightAndDarkAppearance() {
        func luminance(_ color: Color, _ traits: UITraitCollection) -> Double {
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
            XCTAssertTrue(UIColor(color).resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha))
            func linear(_ value: CGFloat) -> Double { let value = Double(value); return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }
        for appearance: UIUserInterfaceStyle in [.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: appearance)
            for pair in [(TrackerStyle.ink, TrackerStyle.surface), (TrackerStyle.muted, TrackerStyle.surface),
                         (TrackerStyle.blue, TrackerStyle.blueSurface), (TrackerStyle.onBlueSurface, TrackerStyle.blueSurface),
                         (TrackerStyle.green, TrackerStyle.surface), (TrackerStyle.onBlue, TrackerStyle.blue)] {
                let foreground = luminance(pair.0, traits), background = luminance(pair.1, traits)
                XCTAssertGreaterThanOrEqual((max(foreground, background) + 0.05) / (min(foreground, background) + 0.05), 4.5)
            }
        }
    }
    func testSyntheticCardsAndWidgetSizesRenderWithoutSavingAnAccount() throws {
        let value = try row()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tracker-ui-qa", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        func save<V: View>(_ view: V, _ name: String) throws {
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage)
            XCTAssertGreaterThan(image.size.width, 100); XCTAssertGreaterThan(image.size.height, 100)
            try XCTUnwrap(image.pngData()).write(to: folder.appendingPathComponent(name + ".png"))
        }
        for scheme: ColorScheme in [.light, .dark] {
            let name = scheme == .light ? "light" : "dark"
            try save(AccountCard(row: value).frame(width: 280).environment(\.colorScheme, scheme), "account-" + name)
            try save(AccountCard(row: value).frame(width: 280).environment(\.dynamicTypeSize, .accessibility1).environment(\.colorScheme, scheme), "account-large-" + name)
            for size: TrackerWidgetSize in [.small, .medium, .large] {
                let width: CGFloat = size == .small ? 172 : 340, height: CGFloat = size == .large ? 360 : 184
                try save(TrackerWidgetContent(rows: [value], size: size).padding(16).frame(width: width, height: height).background(TrackerStyle.surface).environment(\.colorScheme, scheme), "widget-\(size)-" + name)
            }
        }
    }
}
