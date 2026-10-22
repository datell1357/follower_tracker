import XCTest
@testable import FollowerCore

final class YouTubeStudioTests: XCTestCase {
    private let id = "UC" + String(repeating: "a", count: 22)
    private func session() -> SavedSession {
        SavedSession(cookies: ["SID", "SAPISID"].map { CookieRecord(HTTPCookie(properties: [.name: $0, .value: "synthetic-studio", .domain: ".youtube.com", .path: "/", .secure: "TRUE"])!) }, userAgent: "fixture", expectedID: nil, savedAt: 1)
    }
    private func payload(_ changes: [String: Any] = [:]) throws -> String {
        var body: [String: Any] = ["provider": "YOUTUBE", "stableId": id, "studioChannelId": id, "username": id,
            "displayName": "Fixture Channel", "profileURL": "https://studio.youtube.com/channel/" + id,
            "followers": 0, "following": NSNull(), "precision": "EXACT", "source": "youtube-studio-dom"]
        changes.forEach { body[$0.key] = $0.value }
        return String(decoding: try JSONSerialization.data(withJSONObject: body), as: UTF8.self)
    }
    func testExactStudioCountUsesASeparateChannelIdentityAndKeepsItsHistory() throws {
        let (account, metric) = try ResponseParser.capturedProfile(.youtube, payload: payload(), session: session(), expected: nil, now: 100)
        XCTAssertEqual(account.id, "YOUTUBE:" + id); XCTAssertEqual(account.identityLabel, "Fixture Channel")
        XCTAssertEqual(Provider.youtube.metricLabel, "구독자"); XCTAssertEqual(metric.followers, 0); XCTAssertNil(metric.following)
        XCTAssertEqual(try ResponseParser.capturedProfile(.youtube, payload: payload(), session: session(), expected: account, now: 200).0.connectedAt, 100)
        XCTAssertEqual(account.status, .foregroundOnly, "No iOS background read has yet been observed")
        XCTAssertEqual(account.capabilities.followers, .unavailable); XCTAssertEqual(account.capabilities.following, .unavailable)
    }
    func testPublicCountsAndAnotherChannelNeverPassAsOwnedExactStudioData() throws {
        for change: [String: Any] in [["profileURL": "https://www.youtube.com/channel/" + id], ["studioChannelId": "UC" + String(repeating: "b", count: 22)],
            ["source": "youtube-public-api"], ["precision": "ROUNDED"], ["followers": true], ["followers": "1.2K"]] {
            XCTAssertThrowsError(try ResponseParser.capturedProfile(.youtube, payload: payload(change), session: session(), expected: nil, now: 100))
        }
    }
    func testOnlyYouTubeCookiesAuthenticateStudioAndOldProvidersStillDecode() throws {
        var saved = session(); XCTAssertTrue(saved.authenticated(.youtube))
        saved.cookies.removeAll { $0.name == "SAPISID" }; XCTAssertFalse(saved.authenticated(.youtube))
        XCTAssertFalse(Provider.youtube.allows(URL(string: "https://accounts.google.com/")!))
        XCTAssertEqual(try JSONDecoder().decode(Provider.self, from: Data(#""INSTAGRAM""#.utf8)), .instagram)
    }
}
