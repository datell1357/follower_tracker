import Foundation

public struct PageAccumulator {
    private let ownerKey: String
    private let limit: Int
    private var members: [String: Member] = [:]
    private var order: [String] = []
    private var cursors: Set<String> = []
    private var terminal = false
    private var failed = false
    public init(ownerKey: String, limit: Int = 100_000) { self.ownerKey = ownerKey; self.limit = limit }
    public mutating func append(_ page: RelationshipPage) throws {
        guard !failed, !terminal, page.ownerKey == ownerKey else { try fail() }
        for member in page.members {
            if members[member.id] == nil { order.append(member.id) }
            members[member.id] = member
        }
        guard members.count <= limit else { try fail() }
        if page.hasMore && (page.nextCursor?.isEmpty ?? true) { try fail() }
        if !page.hasMore && page.nextCursor != nil { try fail() }
        if let cursor = page.nextCursor, !cursors.insert(cursor).inserted { try fail() }
        terminal = !page.hasMore
    }
    public func finish() throws -> [Member] {
        guard !failed, terminal else { throw CollectionFailure(.listIncomplete) }
        return order.compactMap { members[$0] }
    }
    private mutating func fail() throws -> Never {
        failed = true
        throw CollectionFailure(.listIncomplete)
    }
}

public struct RelationshipReport: Sendable {
    public let notFollowingBack: [Member]
    public let youDoNotFollowBack: [Member]
    public let mutual: [Member]
    public let unfollowCandidates: [Member]
    public let repeatedAbsences: [Member]
    public let comparedAt: Int64
    public let baseline: Bool
}
public enum RelationshipAnalyzer {
    private static func usable(_ snapshot: RelationshipSnapshot) -> Bool {
        snapshot.complete && snapshot.consistent && snapshot.finishedAt >= snapshot.startedAt
    }
    public static func compare(followers: RelationshipSnapshot, following: RelationshipSnapshot,
                               previous: RelationshipSnapshot? = nil, beforePrevious: RelationshipSnapshot? = nil,
                               maxSkewMillis: Int64 = 600_000) throws -> RelationshipReport {
        guard usable(followers), usable(following), followers.direction == .followers, following.direction == .following,
              followers.accountKey == following.accountKey,
              abs(followers.finishedAt - following.finishedAt) <= maxSkewMillis else { throw CollectionFailure(.listIncomplete) }
        if let previous {
            guard usable(previous), previous.accountKey == followers.accountKey, previous.direction == .followers,
                  previous.finishedAt < followers.startedAt else { throw CollectionFailure(.listIncomplete) }
        }
        if let earlier = beforePrevious {
            guard let previous, usable(earlier), earlier.accountKey == followers.accountKey, earlier.direction == .followers,
                  earlier.finishedAt < previous.startedAt else { throw CollectionFailure(.listIncomplete) }
        }
        func indexed(_ members: [Member]) -> [String: Member] { members.reduce(into: [:]) { $0[$1.id] = $1 } }
        let current = indexed(followers.members), followed = indexed(following.members)
        let prior = indexed(previous?.members ?? []), earlier = indexed(beforePrevious?.members ?? [])
        return RelationshipReport(
            notFollowingBack: followed.values.filter { current[$0.id] == nil }.sorted { $0.username < $1.username },
            youDoNotFollowBack: current.values.filter { followed[$0.id] == nil }.sorted { $0.username < $1.username },
            mutual: current.values.filter { followed[$0.id] != nil }.sorted { $0.username < $1.username },
            unfollowCandidates: prior.values.filter { current[$0.id] == nil }.sorted { $0.username < $1.username },
            repeatedAbsences: earlier.values.filter { prior[$0.id] == nil && current[$0.id] == nil }.sorted { $0.username < $1.username },
            comparedAt: max(followers.finishedAt, following.finishedAt), baseline: previous == nil)
    }
}
