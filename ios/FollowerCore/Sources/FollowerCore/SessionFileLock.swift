import Darwin
import Foundation

public enum SessionFileLock {
    public enum Failure: Error { case invalidLocation, unavailable }

    public static func withLock<T>(at url: URL, operation: () throws -> T) throws -> T {
        guard url.isFileURL else { throw Failure.invalidLocation }
        let descriptor = Darwin.open(url.path, O_CREAT | O_RDWR | O_CLOEXEC, mode_t(S_IRUSR | S_IWUSR))
        guard descriptor >= 0 else { throw Failure.unavailable }
        defer { Darwin.close(descriptor) }
        var result: Int32
        repeat { result = flock(descriptor, LOCK_EX) } while result != 0 && errno == EINTR
        guard result == 0 else { throw Failure.unavailable }
        defer { flock(descriptor, LOCK_UN) }
        return try operation()
    }
}
