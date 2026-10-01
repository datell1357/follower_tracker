#if os(macOS)
import Foundation
import XCTest
@testable import FollowerCore

final class SessionFileLockTests: XCTestCase {
    func testAnotherProcessCannotAcquireUntilTheSessionMutationFinishes() throws {
        let directory = try fixtureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("session.lock")
        try SessionFileLock.withLock(at: url) { XCTAssertFalse(try childCanAcquire(url)) }
        XCTAssertTrue(try childCanAcquire(url))
    }

    func testFailedMutationReleasesTheLock() throws {
        enum ExpectedFailure: Error { case synthetic }
        let directory = try fixtureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("session.lock")
        XCTAssertThrowsError(try SessionFileLock.withLock(at: url) { throw ExpectedFailure.synthetic })
        XCTAssertTrue(try childCanAcquire(url))
    }

    private func fixtureDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("follower-lock-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }

    private func childCanAcquire(_ url: URL) throws -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["python3", "-c", """
        import fcntl, sys
        with open(sys.argv[1], "a") as file:
            try:
                fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                sys.exit(1)
        """, url.path]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        return process.terminationReason == .exit && process.terminationStatus == 0
    }
}
#endif
