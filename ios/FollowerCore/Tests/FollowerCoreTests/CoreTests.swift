import XCTest
@testable import FollowerCore

final class CoreTests: XCTestCase {
    private func scan(_ ids: [String], _ at: Int64, _ direction: Direction = .followers,
                      complete: Bool = true, account: String = "INSTAGRAM:owner") throws -> RelationshipSnapshot {
        RelationshipSnapshot(accountKey: account, direction: direction, startedAt: at, finishedAt: at + 1,
            members: try ids.map { try Member(id: $0, username: "user_\($0)") }, complete: complete, consistent: true, endReason: "terminal")
    }
    func testFirstScanIsBaselineAndMutualUsesIDs() throws {
        let report = try RelationshipAnalyzer.compare(followers: scan(["a", "b"], 100), following: scan(["a", "c"], 101, .following))
        XCTAssertTrue(report.baseline)
        XCTAssertEqual(report.notFollowingBack.map(\.id), ["c"])
        XCTAssertEqual(report.youDoNotFollowBack.map(\.id), ["b"])
        XCTAssertEqual(report.mutual.map(\.id), ["a"])
        XCTAssertTrue(report.unfollowCandidates.isEmpty)
    }
    func testPartialChangedOwnerAndStaleScansCannotCreateUnfollowers() throws {
        XCTAssertThrowsError(try RelationshipAnalyzer.compare(followers: scan([], 100, complete: false), following: scan(["a"], 101, .following), previous: scan(["a"], 1)))
        XCTAssertThrowsError(try RelationshipAnalyzer.compare(followers: scan([], 100, account: "INSTAGRAM:other"), following: scan(["a"], 101, .following), previous: scan(["a"], 1)))
        XCTAssertThrowsError(try RelationshipAnalyzer.compare(followers: scan(["a"], 1), following: scan(["a"], 900_000, .following)))
    }
    func testCandidateAbsenceIsRecheckedInThirdScan() throws {
        let report = try RelationshipAnalyzer.compare(followers: scan(["c"], 300), following: scan(["c"], 301, .following),
            previous: scan(["b", "c"], 200), beforePrevious: scan(["a", "b", "c"], 100))
        XCTAssertEqual(report.unfollowCandidates.map(\.id), ["b"])
        XCTAssertEqual(report.repeatedAbsences.map(\.id), ["a"])
    }
    func testPaginationRequiresTerminalAndRejectsLoops() throws {
        var accumulator = PageAccumulator(ownerKey: "owner")
        try accumulator.append(RelationshipPage(ownerKey: "owner", members: [Member(id: "a", username: "a")], nextCursor: "next", hasMore: true))
        XCTAssertThrowsError(try accumulator.finish())
        XCTAssertThrowsError(try accumulator.append(RelationshipPage(ownerKey: "owner", members: [], nextCursor: "next", hasMore: true)))
    }
    func testPaginationDeduplicatesStableIDs() throws {
        var accumulator = PageAccumulator(ownerKey: "owner")
        try accumulator.append(RelationshipPage(ownerKey: "owner", members: [Member(id: "a", username: "a")], nextCursor: "next", hasMore: true))
        try accumulator.append(RelationshipPage(ownerKey: "owner", members: [Member(id: "a", username: "renamed"), Member(id: "b", username: "b")], nextCursor: nil, hasMore: false))
        XCTAssertEqual(try accumulator.finish().map(\.id), ["a", "b"])
    }
    func testCookieDestinationsRejectLookalikesAndInsecureURLs() {
        XCTAssertTrue(Provider.instagram.allows(URL(string: "https://www.instagram.com/api/v1/users/1/info/")!))
        for url in ["https://instagram.com.evil.example/", "http://instagram.com/", "https://instagram.com:444/", "https://user:password@instagram.com/", "https://x.com/"] {
            XCTAssertFalse(Provider.instagram.allows(URL(string: url)!))
        }
    }
    func testRoundedCountNeverBecomesExact() {
        XCTAssertEqual(exactDisplayedCount("12,345"), 12_345)
        XCTAssertEqual(exactDisplayedCount("12 345"), 12_345)
        for count in ["1.2K", "1.2만", "-4", "", "123.4"] { XCTAssertNil(exactDisplayedCount(count)) }
    }
    func testZeroMissingAndChangedIdentityAreDifferent() throws {
        let body = #"{"status":"ok","user":{"pk":12,"username":"test","follower_count":0,"following_count":2}}"#
        XCTAssertEqual(try ResponseParser.instagramProfile(body, expectedID: "12", now: 1).1.followers, 0)
        XCTAssertThrowsError(try ResponseParser.instagramProfile(body, expectedID: "13", now: 1))
        XCTAssertThrowsError(try ResponseParser.instagramProfile(body.replacingOccurrences(of: "\"follower_count\":0,", with: ""), expectedID: "12", now: 1))
    }
    func testLoginAndChallengeRemainErrors() {
        XCTAssertThrowsError(try ResponseParser.objectBody("<html>accounts/login</html>")) { error in
            XCTAssertEqual((error as? CollectionFailure)?.status, .reauthRequired)
        }
        XCTAssertThrowsError(try ResponseParser.instagramProfile(#"{"status":"fail","message":"challenge_required"}"#, expectedID: "1", now: 1)) { error in
            XCTAssertEqual((error as? CollectionFailure)?.status, .checkRequired)
        }
        XCTAssertEqual(statusForHTTP(429), .rateLimited)
        XCTAssertEqual(statusForHTTP(403), .checkRequired)
    }
    func testRedditProfileFollowersAreNotKarma() throws {
        let body = #"{"id":"r1","name":"test","total_karma":1234,"subreddit":{"title":"Test","subscribers":12}}"#
        XCTAssertEqual(try ResponseParser.redditProfile(body, expectedID: "r1", now: 1).1.followers, 12)
    }
    func testFailedPageCannotBeResumedAsComplete() throws {
        var accumulator = PageAccumulator(ownerKey: "owner")
        XCTAssertThrowsError(try accumulator.append(RelationshipPage(ownerKey: "other", members: [], nextCursor: nil, hasMore: false)))
        XCTAssertThrowsError(try accumulator.append(RelationshipPage(ownerKey: "owner", members: [], nextCursor: nil, hasMore: false)))
        XCTAssertThrowsError(try accumulator.finish())
    }
    func testTikTokNativeCountRequiresSignedInOwner() throws {
        let html = #"<script type="application/json">{"__DEFAULT_SCOPE__":{"webapp.app-context":{"user":{"id":"42"}},"webapp.user-detail":{"userInfo":{"user":{"id":"42","uniqueId":"self"},"stats":{"followerCount":5,"followingCount":2}}}}}</script>"#
        XCTAssertEqual(try ResponseParser.tiktokProfile(html, expectedID: "42", now: 1).1.followers, 5)
        let anonymous = html.replacingOccurrences(of: #""webapp.app-context":{"user":{"id":"42"}}"#, with: #""webapp.app-context":{}"#)
        XCTAssertThrowsError(try ResponseParser.tiktokProfile(anonymous, expectedID: "42", now: 1))
    }
}
