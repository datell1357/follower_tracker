import Foundation
import Observation
import WidgetKit
import FollowerCore

@MainActor @Observable
final class TrackerModel {
    var accounts: [AccountOverview] = []
    var loading = true
    var busy = false
    var storageError = false
    var message: String?
    var selectedKey: String?
    var report: RelationshipReport?
    var relationshipChanges: [RelationshipChange] = []
    private var reloadVersion = 0
    let repository: TrackerRepository?
    let sync: SyncService?
    init() {
        do { let repository = try TrackerRepository(); self.repository = repository; sync = SyncService(repository: repository) }
        catch { repository = nil; sync = nil; storageError = true; loading = false }
    }
    func reload() async {
        guard let repository else { return }
        reloadVersion += 1
        let version = reloadVersion
        do {
            let rows = try await repository.overviews()
            guard version == reloadVersion else { return }
            let key = rows.contains(where: { $0.id == selectedKey }) ? selectedKey : rows.first?.id
            let result: RelationshipReport?
            if let key { result = try await repository.report(key) } else { result = nil }
            let changes: [RelationshipChange]
            if let key { changes = try await repository.relationshipChanges(key) } else { changes = [] }
            guard version == reloadVersion else { return }
            accounts = rows; selectedKey = key; report = result; relationshipChanges = changes
            storageError = false; loading = false
        } catch {
            guard version == reloadVersion else { return }
            storageError = true; loading = false; message = "저장된 기록을 읽지 못했어요. 데이터를 보존했어요."
        }
    }
    func select(_ key: String) async { selectedKey = key; report = nil; relationshipChanges = []; await reload() }
    func refresh(_ key: String? = nil) async {
        guard !busy, let sync else { return }
        busy = true; message = nil; defer { busy = false }
        do {
            let keys = accounts.filter { key == nil || $0.id == key }.map(\.id)
            let failures = try await sync.refreshAll(keys)
            await reload(); await WidgetUpdates.shared.request()
            if !failures.isEmpty && !storageError { message = "일부 계정을 갱신하지 못했어요. 해당 계정의 마지막 기록을 유지했어요." }
        }
        catch is CancellationError { }
        catch { message = "갱신을 마치지 못했어요. 마지막 기록을 유지했어요." }
    }
    func relationships() async {
        guard !busy, let selectedKey, let sync else { return }
        busy = true; defer { busy = false }
        do { try await sync.relationships(selectedKey); await reload() }
        catch is CancellationError { }
        catch { message = "명단 갱신을 마치지 못했어요. 마지막 완료된 비교를 유지했어요." }
    }
    func connect(_ provider: Provider, session: SavedSession, payload: String?, facebookPage: Bool = false,
                 expectedPageKey: String? = nil, writeIf: @escaping SessionWriteGuard = { true }) async throws {
        guard !busy, let repository else { throw StorageFailure.unavailable }
        busy = true; message = nil; defer { busy = false }
        guard try writeIf() else { throw CollectionFailure(.checkRequired) }
        let accounts = try await repository.accounts()
        let pageID = try payload.flatMap { try ResponseParser.objectBody($0)["stableId"] as? String }
        let expected = accounts.first { account in
            guard account.provider == provider else { return false }
            if facebookPage { return account.accountType == .page && (expectedPageKey.map { account.id == $0 } ?? (account.stableID == pageID)) }
            return account.accountType == .profile
        }
        if facebookPage && (provider != .facebook || payload == nil) { throw CollectionFailure(.checkRequired) }
        let observation: (Account, MetricSnapshot)
        let sessionCurrent: SessionWriteGuard
        if let payload {
            let captured = try facebookPage ? ResponseParser.capturedFacebookPage(payload, session: session, expected: expected, now: nowMillis()) :
                ResponseParser.capturedProfile(provider, payload: payload, session: session, expected: expected, now: nowMillis())
            if let expectedPageKey, captured.0.id != expectedPageKey { throw CollectionFailure(.checkRequired) }
            var saved = session; saved.expectedID = captured.0.authenticationOwnerID
            try Task.checkCancellation()
            guard try writeIf() else { throw CollectionFailure(.checkRequired) }
            try await SessionVault.shared.save(provider, saved)
            sessionCurrent = try await SessionVault.shared.captureGuard(provider)
            if facebookPage { observation = try await SessionCollector(vault: .shared).connectedFacebookPage(captured, existing: expected,
                writeIf: { try writeIf() && sessionCurrent() }) }
            else { observation = captured }
        } else {
            try await SessionVault.shared.save(provider, session)
            sessionCurrent = try await SessionVault.shared.captureGuard(provider)
            observation = try await SessionCollector(vault: .shared).native(provider, expected: expected)
        }
        try Task.checkCancellation()
        let stored = try await repository.saveObservation(observation.0, observation.1,
            writeIf: { try writeIf() && sessionCurrent() })
        guard stored else { throw CollectionFailure(.checkRequired) }
        await WidgetUpdates.shared.request()
        await reload()
    }
    func disconnect(_ key: String) async {
        guard !busy, let repository else { return }
        busy = true; defer { busy = false }
        do {
            guard let account = try await repository.account(key) else { return }
            try await repository.disconnect(key)
            let remaining = try await repository.accounts()
            if !remaining.contains(where: { $0.provider == account.provider }) { try await SessionVault.shared.remove(account.provider) }
            await reload(); await WidgetUpdates.shared.request()
        } catch { message = "연결 해제를 마치지 못했어요. 다시 확인해주세요." }
    }
    func logout(_ provider: Provider) async {
        guard !busy, let repository else { return }
        busy = true; defer { busy = false }
        do {
            try await SessionVault.shared.remove(provider)
            try await repository.markLoggedOut(provider)
            await reload(); await WidgetUpdates.shared.request()
            message = provider.title + "에서 로그아웃했어요. 다시 로그인하면 이전 기록을 볼 수 있어요."
        } catch { message = "로그아웃을 마치지 못했어요. 기존 기록은 보존했어요." }
    }
}
