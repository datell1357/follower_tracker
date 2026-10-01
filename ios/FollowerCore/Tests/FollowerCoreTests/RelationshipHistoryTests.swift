import XCTest
@testable import FollowerCore

final class RelationshipHistoryTests: XCTestCase {
    private func scan(_ ids: [String], at: Int64, owner: String = "INSTAGRAM:owner",
                      complete: Bool = true, consistent: Bool = true, direction: Direction = .followers) throws -> RelationshipSnapshot {
        RelationshipSnapshot(accountKey: owner, direction: direction, startedAt: at, finishedAt: at + 1,
            members: try ids.map { try Member(id: $0, username: "user_" + $0) }, complete: complete, consistent: consistent, endReason: "terminal")
    }

    func testBaselineAndOriginalEvidence() throws {
        let first = try scan(["a", "b"], at: 100), second = try scan(["b"], at: 200)
        XCTAssertTrue(try RelationshipHistory.observe(current: first, previous: nil, existing: []).isEmpty)
        let event = try XCTUnwrap(RelationshipHistory.observe(current: second, previous: first, existing: []).first)
        XCTAssertEqual(event.member.id, "a")
        XCTAssertEqual(event.previousScanID, RelationshipHistory.scanID(first))
        XCTAssertEqual(event.currentScanID, RelationshipHistory.scanID(second))
        XCTAssertEqual(event.detectedAt, 201)
        XCTAssertEqual(event.state, .candidate)
    }

    func testRepeatedAbsencePersistsBeyondThreeScans() throws {
        var previous = try scan(["a"], at: 100), events: [RelationshipChange] = []
        for time: Int64 in [200, 300, 400, 500, 600] {
            let current = try scan([], at: time)
            events = try RelationshipHistory.observe(current: current, previous: previous, existing: events)
            previous = current
        }
        XCTAssertEqual(events.count, 1)
        let event = try XCTUnwrap(events.first)
        XCTAssertEqual(event.detectedAt, 201); XCTAssertEqual(event.checkedAt, 601)
        XCTAssertEqual(event.absenceChecks, 5); XCTAssertEqual(event.state, .repeatedAbsence)
        XCTAssertEqual(event.currentScanID, "INSTAGRAM:owner:FOLLOWERS:201")
    }

    func testReappearanceAndSubsequentLossAreSeparateEpisodes() throws {
        let first = try scan(["a"], at: 100), absent = try scan([], at: 200), returned = try scan(["a"], at: 300)
        let events = try RelationshipHistory.observe(current: absent, previous: first, existing: [])
        let closed = try RelationshipHistory.observe(current: returned, previous: absent, existing: events)
        XCTAssertEqual(closed[0].id, events[0].id); XCTAssertEqual(closed[0].state, .reobserved)
        XCTAssertEqual(closed[0].absenceChecks, 1)
        let later = try RelationshipHistory.observe(current: scan([], at: 400), previous: returned, existing: closed)
        XCTAssertEqual(later.count, 2); XCTAssertEqual(later[0], closed[0]); XCTAssertNotEqual(later[0].id, later[1].id)
    }

    func testUsernameChangesAndSerialization() throws {
        let first = try scan(["a"], at: 100)
        let renamed = RelationshipSnapshot(accountKey: first.accountKey, direction: .followers, startedAt: 200, finishedAt: 201,
            members: [try Member(id: "a", username: "renamed")], complete: true, consistent: true, endReason: "terminal")
        XCTAssertTrue(try RelationshipHistory.observe(current: renamed, previous: first, existing: []).isEmpty)
        let event = try RelationshipHistory.observe(current: scan([], at: 300), previous: renamed, existing: [])[0]
        XCTAssertEqual(event.member.username, "renamed")
        XCTAssertEqual(try JSONDecoder().decode(RelationshipChange.self, from: JSONEncoder().encode(event)), event)
    }

    func testPartialMismatchedDuplicateAndStaleScansCannotAdvanceEvents() throws {
        let first = try scan(["a"], at: 100), missing = try scan([], at: 200)
        let events = try RelationshipHistory.observe(current: missing, previous: first, existing: [])
        let invalid = try [scan([], at: 300, complete: false), scan([], at: 300, consistent: false),
            scan([], at: 300, owner: "INSTAGRAM:other"), scan(["a", "a"], at: 300), missing,
            scan([], at: 300, direction: .following)]
        for current in invalid { XCTAssertThrowsError(try RelationshipHistory.observe(current: current, previous: missing, existing: events)) }
        XCTAssertEqual(events[0].checkedAt, 201); XCTAssertEqual(events[0].state, .candidate)
    }
}
