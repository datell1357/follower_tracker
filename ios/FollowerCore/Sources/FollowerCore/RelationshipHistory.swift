import Foundation

public enum RelationshipChangeState: String, Codable, Sendable {
    case candidate = "CANDIDATE", repeatedAbsence = "REPEATED_ABSENCE", reobserved = "REOBSERVED"
    public var label: String {
        switch self {
        case .candidate: "처음 미관측"
        case .repeatedAbsence: "연속 미관측"
        case .reobserved: "다시 관측됨"
        }
    }
}

public struct RelationshipChange: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let accountKey: String
    public let member: Member
    public let previousScanID: String
    public let currentScanID: String
    public let detectedAt: Int64
    public var checkedScanID: String
    public var checkedAt: Int64
    public var absenceChecks: Int
    public var state: RelationshipChangeState
}

public enum RelationshipHistory {
    public static func observe(current: RelationshipSnapshot, previous: RelationshipSnapshot?,
                               existing: [RelationshipChange]) throws -> [RelationshipChange] {
        func validate(_ scan: RelationshipSnapshot) throws {
            guard scan.direction == .followers, scan.complete, scan.consistent,
                  scan.finishedAt >= scan.startedAt, scan.accountKey == current.accountKey,
                  Set(scan.members.map(\.id)).count == scan.members.count else { throw CollectionFailure(.listIncomplete) }
        }
        try validate(current)
        if let previous {
            try validate(previous)
            guard previous.finishedAt < current.startedAt else { throw CollectionFailure(.listIncomplete) }
        }
        let pending = existing.filter { $0.state != .reobserved }
        guard existing.allSatisfy({ $0.accountKey == current.accountKey && !$0.id.isEmpty && $0.checkedAt >= $0.detectedAt && $0.absenceChecks > 0 }),
              Set(existing.map(\.id)).count == existing.count,
              Set(pending.map { $0.member.id }).count == pending.count,
              pending.allSatisfy({ $0.checkedAt < current.startedAt }) else { throw CollectionFailure(.listIncomplete) }
        let currentIDs = Set(current.members.map(\.id)), pendingIDs = Set(pending.map { $0.member.id })
        let scanID = scanID(current)
        var result = try existing.map { original -> RelationshipChange in
            guard original.state != .reobserved else { return original }
            var change = original
            change.checkedScanID = scanID; change.checkedAt = current.finishedAt
            if currentIDs.contains(change.member.id) { change.state = .reobserved }
            else {
                let (checks, overflow) = change.absenceChecks.addingReportingOverflow(1)
                guard !overflow else { throw CollectionFailure(.listIncomplete) }
                change.absenceChecks = checks; change.state = .repeatedAbsence
            }
            return change
        }
        if let previous {
            for member in previous.members where !currentIDs.contains(member.id) && !pendingIDs.contains(member.id) {
                result.append(RelationshipChange(id: UUID().uuidString, accountKey: current.accountKey, member: member,
                    previousScanID: self.scanID(previous), currentScanID: scanID, detectedAt: current.finishedAt,
                    checkedScanID: scanID, checkedAt: current.finishedAt, absenceChecks: 1, state: .candidate))
            }
        }
        return result
    }

    public static func scanID(_ scan: RelationshipSnapshot) -> String {
        "\(scan.accountKey):\(scan.direction.rawValue):\(scan.finishedAt)"
    }
}
