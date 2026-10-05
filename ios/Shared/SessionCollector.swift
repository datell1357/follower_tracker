import Foundation
import CoreFoundation
import FollowerCore

private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct SessionHTTPClient {
    let vault: SessionVault
    // Reuse TLS/connections across all providers. Cookies still come from the current Vault session.
    private static let pooledSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false; configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 25; configuration.timeoutIntervalForResource = 35
        return URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
    }()
    let transport: URLSession = SessionHTTPClient.pooledSession
    func read(_ provider: Provider, _ url: URL, expectedID: String?, timeout: TimeInterval = 25) async throws -> String {
        guard provider.allows(url) else { throw CollectionFailure(.checkRequired) }
        guard let saved = try await vault.load(provider), saved.authenticated(provider) else { throw CollectionFailure(.reauthRequired) }
        if [.instagram, .x, .facebook].contains(provider), let expectedID, saved.identity(provider) != expectedID { throw CollectionFailure(.checkRequired) }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpMethod = "GET"; request.timeoutInterval = timeout
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue(saved.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json,text/html;q=0.9", forHTTPHeaderField: "Accept")
        request.setValue(saved.cookies.filter { $0.matches(url) }.sorted { $0.path.count > $1.path.count }.map { "\($0.name)=\($0.value)" }.joined(separator: "; "), forHTTPHeaderField: "Cookie")
        if provider == .instagram {
            request.setValue("936619743392459", forHTTPHeaderField: "X-IG-App-ID")
            request.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
            if let csrf = saved.cookies.first(where: { $0.name == "csrftoken" && $0.matches(url) }) { request.setValue(csrf.value, forHTTPHeaderField: "X-CSRFToken") }
        }
        #if DEBUG
        let started = ContinuousClock.now
        var receivedBodyBytes = 0
        defer {
            print("collectionMetrics provider=\(provider.rawValue) elapsed=\(ContinuousClock.now - started) receivedBodyBytes=\(receivedBodyBytes)")
        }
        #endif
        do {
            let (bytes, response) = try await transport.bytes(for: request)
            guard let response = response as? HTTPURLResponse else { throw CollectionFailure(.formatChanged) }
            if (300..<400).contains(response.statusCode) { throw CollectionFailure(.reauthRequired) }
            if let status = statusForHTTP(response.statusCode) {
                let retry = response.value(forHTTPHeaderField: "Retry-After").flatMap(Int64.init).map { min(86_400, max(60, $0)) }
                throw CollectionFailure(status, retryAfterSeconds: retry)
            }
            let limit = 8 * 1024 * 1024
            guard response.expectedContentLength <= Int64(limit) else { throw CollectionFailure(.formatChanged) }
            var data = Data()
            for try await byte in bytes {
                if data.count >= limit { throw CollectionFailure(.formatChanged) }
                data.append(byte)
                #if DEBUG
                receivedBodyBytes += 1
                #endif
            }
            try Task.checkCancellation()
            var headers: [String: String] = [:]
            response.allHeaderFields.forEach { key, value in if let key = key as? String, let value = value as? String { headers[key] = value } }
            try await vault.responseCookies(provider, url: url, headers: headers, sessionVersion: saved.savedAt)
            guard let body = String(data: data, encoding: .utf8) else { throw CollectionFailure(.formatChanged) }
            return body
        } catch is CancellationError { throw CancellationError() }
        catch let error as URLError where error.code == .cancelled { throw CancellationError() }
        catch let failure as CollectionFailure { throw failure }
        catch { throw CollectionFailure(.offline) }
    }
}

protocol SessionCollecting: Sendable {
    func native(_ provider: Provider, expected: Account?, timeout: TimeInterval) async throws -> (Account, MetricSnapshot)
    func relationships(_ account: Account) async throws -> (RelationshipSnapshot, RelationshipSnapshot)
}

struct SessionCollector: SessionCollecting {
    let vault: SessionVault
    func native(_ provider: Provider, expected: Account?, timeout: TimeInterval = 25) async throws -> (Account, MetricSnapshot) {
        guard let saved = try await vault.load(provider), saved.authenticated(provider) else { throw CollectionFailure(.reauthRequired) }
        let http = SessionHTTPClient(vault: vault)
        let observation: (Account, MetricSnapshot)
        switch provider {
        case .instagram:
            guard let id = saved.identity(provider), expected == nil || expected?.stableID == id else { throw CollectionFailure(.checkRequired) }
            let url = URL(string: "https://www.instagram.com/api/v1/users/\(id)/info/")!
            observation = try ResponseParser.instagramProfile(await http.read(provider, url, expectedID: id, timeout: timeout), expectedID: id, now: nowMillis())
        case .reddit:
            observation = try ResponseParser.redditProfile(await http.read(provider, URL(string: "https://www.reddit.com/api/me.json")!, expectedID: expected?.stableID, timeout: timeout), expectedID: expected?.stableID, now: nowMillis())
        case .tiktok:
            guard let expected else { throw CollectionFailure(.checkRequired) }
            observation = try ResponseParser.tiktokProfile(await http.read(provider, expected.profileURL, expectedID: expected.stableID, timeout: timeout), expectedID: expected.stableID, now: nowMillis())
        case .x, .facebook: throw CollectionFailure(.foregroundOnly)
        }
        var updated = try Account(provider: provider, stableID: observation.0.stableID, username: observation.0.username,
            displayName: observation.0.displayName, profileURL: observation.0.profileURL, connectedAt: expected?.connectedAt ?? observation.0.connectedAt)
        updated.capabilities = expected?.capabilities ?? Capabilities(); updated.capabilities.count = .observed
        if updated.capabilities.background == .foregroundOnly { updated.capabilities.background = .unverified }
        updated.status = .ready; updated.lastAttemptAt = nowMillis()
        return (updated, observation.1)
    }
    func captured(_ provider: Provider, payload: String, expected: Account?) async throws -> (Account, MetricSnapshot) {
        guard let saved = try await vault.load(provider) else { throw CollectionFailure(.reauthRequired) }
        return try ResponseParser.capturedProfile(provider, payload: payload, session: saved, expected: expected, now: nowMillis())
    }
    func relationships(_ account: Account) async throws -> (RelationshipSnapshot, RelationshipSnapshot) {
        guard account.provider == .instagram else { throw CollectionFailure(.foregroundOnly) }
        let startedAt = nowMillis(), started = ContinuousClock.now
        let before = try await native(account.provider, expected: account).1
        let http = SessionHTTPClient(vault: vault)
        func collect(_ direction: Direction) async throws -> [Member] {
            var accumulator = PageAccumulator(ownerKey: account.id), cursor: String?
            repeat {
                try Task.checkCancellation()
                guard ContinuousClock.now - started < .seconds(180) else { throw CollectionFailure(.listIncomplete) }
                var url = URLComponents(string: "https://www.instagram.com/api/v1/friendships/\(account.stableID)/\(direction.rawValue.lowercased())/")!
                url.queryItems = [URLQueryItem(name: "count", value: "100")] + (cursor.map { [URLQueryItem(name: "max_id", value: $0)] } ?? [])
                let page = try ResponseParser.instagramPage(await http.read(account.provider, url.url!, expectedID: account.stableID), ownerKey: account.id)
                try accumulator.append(page); cursor = page.nextCursor
            } while cursor != nil
            return try accumulator.finish()
        }
        let followers = try await collect(.followers), following = try await collect(.following)
        let after = try await native(account.provider, expected: account).1
        guard before.followers == after.followers, before.following == after.following,
              Int64(followers.count) == after.followers, Int64(following.count) == after.following else { throw CollectionFailure(.listIncomplete) }
        let finished = nowMillis()
        return (RelationshipSnapshot(accountKey: account.id, direction: .followers, startedAt: startedAt, finishedAt: finished,
            members: followers, complete: true, consistent: true, endReason: "terminal-cursor; counters-stable; non-atomic"),
            RelationshipSnapshot(accountKey: account.id, direction: .following, startedAt: startedAt, finishedAt: finished,
            members: following, complete: true, consistent: true, endReason: "terminal-cursor; counters-stable; non-atomic"))
    }
}

actor SyncService {
    private var active: Set<String> = []
    let repository: TrackerRepository
    let collector: any SessionCollecting
    private let isDefinitelyOffline: @Sendable () -> Bool
    init(repository: TrackerRepository, collector: any SessionCollecting = SessionCollector(vault: .shared),
         isDefinitelyOffline: @escaping @Sendable () -> Bool = { NetworkAvailability.shared.isDefinitelyOffline }) {
        self.repository = repository; self.collector = collector; self.isDefinitelyOffline = isDefinitelyOffline
    }
    func refresh(_ key: String, background: Bool = false, timeout: TimeInterval = 25) async throws {
        guard !active.contains(key), let account = try await repository.account(key) else { return }
        guard RefreshPolicy.canRefresh(account, now: nowMillis(), background: background) else { return }
        if background && isDefinitelyOffline() { return }
        let token = UUID().uuidString
        guard try await repository.claimSync(key, token: token) else { return }
        active.insert(key); defer { active.remove(key) }
        do { try await performRefresh(account, background: background, timeout: timeout) }
        catch {
            // Storage failures can occur in the collector or while committing its result.
            try? await repository.updateStatus(key, account.status, nextAllowedAt: account.nextAllowedAt,
                expectedConnectedAt: account.connectedAt, transientRetry: account.transientRetry)
            try? await repository.releaseSync(key, token: token)
            throw error
        }
        try await repository.releaseSync(key, token: token)
    }
    private func performRefresh(_ account: Account, background: Bool, timeout: TimeInterval) async throws {
        let key = account.id
        try await repository.updateStatus(key, .refreshing, nextAllowedAt: account.nextAllowedAt,
            expectedConnectedAt: account.connectedAt, transientRetry: account.transientRetry)
        do {
            var (updated, metric) = try await collector.native(account.provider, expected: account, timeout: timeout)
            if background { updated.capabilities.background = .observed }
            try Task.checkCancellation()
            try await repository.saveObservation(updated, metric, requireExisting: true)
        } catch let failure as CollectionFailure {
            let failedAt = nowMillis()
            try await repository.updateStatus(key, failure.status, nextAllowedAt: RefreshPolicy.serviceRetryAt(failure, failedAt: failedAt),
                expectedConnectedAt: account.connectedAt,
                transientRetry: [.offline, .formatChanged].contains(failure.status) ?
                    RefreshPolicy.nextTransientRetry(account, failedAt: failedAt, status: failure.status) : nil)
        }
    }
    func relationships(_ key: String, background: Bool = false) async throws {
        guard !active.contains(key), let account = try await repository.account(key), !account.status.blocksAutomaticRetry,
              RefreshPolicy.canRefresh(account, now: nowMillis(), background: background) else { return }
        if background && isDefinitelyOffline() { return }
        let token = UUID().uuidString
        guard try await repository.claimSync(key, token: token) else { return }
        active.insert(key); defer { active.remove(key) }
        do { try await performRelationships(account) }
        catch {
            if !(error is CancellationError) {
                try? await repository.listStatus(key, account.relationshipStatus, expectedConnectedAt: account.connectedAt)
                try? await repository.updateStatus(key, account.status, nextAllowedAt: account.nextAllowedAt,
                    expectedConnectedAt: account.connectedAt, transientRetry: account.transientRetry)
            }
            try? await repository.releaseSync(key, token: token)
            throw error
        }
        try await repository.releaseSync(key, token: token)
    }
    private func performRelationships(_ account: Account) async throws {
        let key = account.id
        try await repository.listStatus(key, .refreshing, expectedConnectedAt: account.connectedAt)
        do { let (followers, following) = try await collector.relationships(account); try await repository.saveScans(followers, following, expectedConnectedAt: account.connectedAt) }
        catch is CancellationError { try await repository.listStatus(key, .listIncomplete, expectedConnectedAt: account.connectedAt); throw CancellationError() }
        catch let failure as CollectionFailure {
            try await repository.listStatus(key, failure.status, expectedConnectedAt: account.connectedAt)
            if failure.status.blocksAutomaticRetry || [.rateLimited, .offline, .formatChanged].contains(failure.status) {
                let failedAt = nowMillis()
                try await repository.updateStatus(key, failure.status, nextAllowedAt: RefreshPolicy.serviceRetryAt(failure, failedAt: failedAt),
                    expectedConnectedAt: account.connectedAt,
                    transientRetry: [.offline, .formatChanged].contains(failure.status) ?
                        RefreshPolicy.nextTransientRetry(account, failedAt: failedAt, status: failure.status) : nil)
            }
        }
    }
}
