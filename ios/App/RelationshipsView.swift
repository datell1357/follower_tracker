import SwiftUI
import FollowerCore

struct RelationshipsView: View {
    @Bindable var model: TrackerModel
    @State private var category = 0
    @State private var direction = 0
    @State private var search = ""
    private var selected: AccountOverview? { model.accounts.first { $0.id == model.selectedKey } }
    private var members: [Member] {
        guard let report = model.report else { return [] }
        let result: [Member]
        switch category {
        case 0: result = direction == 0 ? report.notFollowingBack : report.youDoNotFollowBack
        case 1: result = (report.unfollowCandidates + report.repeatedAbsences).reduce(into: [String: Member]()) { $0[$1.id] = $1 }.values.sorted { $0.username < $1.username }
        default: result = report.mutual
        }
        return result.filter { search.isEmpty || $0.username.localizedCaseInsensitiveContains(search) || $0.displayName.localizedCaseInsensitiveContains(search) }
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if let selected {
                    ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(model.accounts) { row in Button(row.account.provider.title) { Task { await model.select(row.id) } }.buttonStyle(.bordered).tint(row.id == selected.id ? TrackerStyle.blue : .secondary) } } }
                    HStack { VStack(alignment: .leading) { Text("@" + selected.account.username).font(.headline); Text(TrackerStyle.time(model.report?.comparedAt)).font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("명단 갱신") { Task { await model.relationships() } }.disabled(model.busy || selected.account.provider != .instagram) }
                    if selected.account.provider != .instagram {
                        EmptyCard("명단 수집을 확인 중이에요", "이 SNS의 전체 팔로워·팔로잉 명단 접근은 아직 검증되지 않았어요. 팔로워 수 기록은 추적 탭에서 확인해주세요.")
                    } else {
                        if let status = selected.account.relationshipStatus, status != .ready { Text(status.label + (model.report == nil ? " · 완료된 명단을 기다리고 있어요." : " · 마지막 완료된 비교를 표시해요.")).font(.caption).foregroundStyle(.secondary) }
                        Picker("관계", selection: $category) { Text("맞팔 아님").tag(0); Text("언팔로우 추정").tag(1); Text("맞팔").tag(2) }.pickerStyle(.segmented)
                        if let report = model.report {
                            if category == 0 { Picker("방향", selection: $direction) { Text("나를 팔로우하지 않음").tag(0); Text("내가 팔로우하지 않음").tag(1) }.pickerStyle(.menu) }
                            Text(category == 1 ? (report.baseline ? "첫 기록은 비교 기준이에요. 다음 완료된 명단부터 변화를 확인할 수 있어요." : "이전 명단에서 사라진 계정이에요. 삭제·비활성화 등으로도 사라질 수 있어요. 연속 미관측 표시는 두 번의 비교에서 보이지 않은 계정이에요.") : category == 2 ? "서로 팔로우하는 계정이에요." : "완료된 두 명단을 비교한 결과예요.").font(.caption).foregroundStyle(.secondary)
                            TextField("이름 또는 사용자 이름 검색", text: $search).textFieldStyle(.roundedBorder)
                            Text("\(members.count)명").font(.caption).foregroundStyle(.secondary)
                            if members.isEmpty { EmptyCard("표시할 계정이 없어요", search.isEmpty ? "현재 완료된 명단 기준이에요." : "검색어를 바꿔보세요.") }
                            ForEach(members) { member in
                                HStack { VStack(alignment: .leading, spacing: 4) { Text(member.displayName).font(.headline); Text("@" + member.username).font(.caption).foregroundStyle(.secondary) }; Spacer(); if category == 1 && report.repeatedAbsences.contains(where: { $0.id == member.id }) { Text("연속 미관측").font(.caption2).foregroundStyle(.secondary) } }.padding(16).background(.background, in: RoundedRectangle(cornerRadius: 16))
                            }
                        } else { EmptyCard("첫 명단을 기다리고 있어요", "전체 명단을 끝까지 읽은 뒤 비교 결과를 표시해요. 일부 명단으로 언팔로우를 판단하지 않아요.") { Button("첫 명단 읽기") { Task { await model.relationships() } }.buttonStyle(.bordered).disabled(model.busy) } }
                    }
                } else { EmptyCard("연결된 계정이 없어요", "추적 탭에서 SNS를 연결하면 읽을 수 있는 명단으로 관계를 비교해요.") }
            }.padding(24)
        }.background(TrackerStyle.background)
    }
}
