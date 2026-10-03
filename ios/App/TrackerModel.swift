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
        busy = true; defer { busy = false }
        do { for row in accounts where key == nil || row.id == key { try await sync.refresh(row.id) }; await reload(); await WidgetUpdates.shared.request() }
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
    func connect(_ provider: Provider, session: SavedSession, payload: String?) async throws {
        guard !busy, let repository else { throw StorageFailure.unavailable }
        busy = true; message = nil; defer { busy = false }
        let expected = try await repository.accounts().first { $0.provider == provider }
        let observation: (Account, MetricSnapshot)
        if let payload {
            let captured = try ResponseParser.capturedProfile(provider, payload: payload, session: session, expected: expected, now: nowMillis())
            var saved = session; saved.expectedID = captured.0.stableID
            try Task.checkCancellation()
            try await SessionVault.shared.save(provider, saved)
            observation = captured
        } else {
            try await SessionVault.shared.save(provider, session)
            observation = try await SessionCollector(vault: .shared).native(provider, expected: expected)
        }
        try Task.checkCancellation()
        try await repository.saveObservation(observation.0, observation.1)
        await WidgetUpdates.shared.request()
        await reload()
    }
    func disconnect(_ key: String) async {
        guard !busy, let repository else { return }
        busy = true; defer { busy = false }
        do {
            guard let account = try await repository.account(key) else { return }
            try await SessionVault.shared.remove(account.provider)
            try await repository.disconnect(key)
            await reload(); await WidgetUpdates.shared.request()
        } catch { message = "연결 해제를 마치지 못했어요. 다시 확인해주세요." }
    }
}
